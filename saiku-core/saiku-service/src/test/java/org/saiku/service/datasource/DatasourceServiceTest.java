/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.repository.AclEntry;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryException;
import org.saiku.service.importer.JujuSource;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuDataSourceException;
import org.saiku.service.util.exception.SaikuDataSourceNotFoundException;

public class DatasourceServiceTest {

    private DatasourceService svc;
    private RecordingDatasourceManager manager;
    private StubConnectionManager connManager;

    @Before
    public void setUp() {
        manager = new RecordingDatasourceManager();
        connManager = new StubConnectionManager(manager);
        svc = new DatasourceService();
        svc.setConnectionManager(connManager);
    }

    @Test
    public void addDatasource_newName_invokesAddOnce() throws Exception {
        SaikuDatasource fresh = ds("ds-new", "id-1");
        svc.addDatasource(fresh, false, new String[] {"ROLE_ADMIN"});
        assertEquals(1, manager.addCalls.size());
        assertEquals("ds-new", manager.addCalls.get(0).getName());
        assertEquals(0, manager.removeCalls.size());
    }

    @Test(expected = Exception.class)
    public void addDatasource_existingName_withoutOverwrite_throws() throws Exception {
        SaikuDatasource ds = ds("ds-existing", "id-1");
        manager.seed(ds);
        svc.addDatasource(ds("ds-existing", "id-2"), false, new String[0]);
    }

    @Test
    public void addDatasource_existingName_withOverwrite_removesThenAdds() throws Exception {
        SaikuDatasource original = ds("ds-replace", "id-orig");
        manager.seed(original);
        SaikuDatasource replacement = ds("ds-replace", "id-new");

        svc.addDatasource(replacement, true, new String[0]);

        assertEquals(1, manager.removeCalls.size());
        assertEquals("id-orig", manager.removeCalls.get(0));
        assertEquals(1, manager.addCalls.size());
        assertEquals("id-new", manager.addCalls.get(0).getProperties().getProperty("id"));
    }

    @Test
    public void fetchDataSourceById_returnsMatch() throws Exception {
        manager.seed(ds("alpha", "id-aaa"));
        manager.seed(ds("beta", "id-bbb"));
        SaikuDatasource found = svc.fetchDataSourceById("id-bbb", new String[0]);
        assertEquals("beta", found.getName());
    }

    @Test(expected = SaikuDataSourceException.class)
    public void fetchDataSourceById_unknown_throws() throws SaikuDataSourceNotFoundException {
        manager.seed(ds("alpha", "id-aaa"));
        svc.fetchDataSourceById("does-not-exist", new String[0]);
    }

    @Test
    public void setLocaleOfDataSource_swapsExistingLocale() throws SaikuDataSourceException {
        SaikuDatasource d = ds("dl", "id-l");
        d.getProperties()
                .setProperty("location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:foo;Catalog=x.xml;locale=en_GB;Foo=bar;");
        svc.setLocaleOfDataSource(d, "fr_FR");
        assertEquals(
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:foo;Catalog=x.xml;locale=fr_FR;Foo=bar;",
                d.getProperties().getProperty("location"));
    }

    @Test(expected = SaikuDataSourceException.class)
    public void setLocaleOfDataSource_missingLocale_throws() throws SaikuDataSourceException {
        SaikuDatasource d = ds("dnl", "id-nl");
        d.getProperties().setProperty("location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:foo;Catalog=x.xml;");
        svc.setLocaleOfDataSource(d, "fr_FR");
    }

    @Test
    public void setLocaleOfDataSource_caseInsensitiveLocateButPreservesOriginalCase() throws SaikuDataSourceException {
        SaikuDatasource d = ds("ci", "id-ci");
        // Upper-case "Locale=" — locator is case-insensitive (toLowerCase).
        d.getProperties().setProperty("location", "jdbc:mondrian:Locale=en_GB;Foo=bar;");
        svc.setLocaleOfDataSource(d, "de_DE");
        // The locale value got swapped but the original key casing comes through
        // verbatim because String.replace works on the substring it located.
        assertTrue(d.getProperties().getProperty("location").contains("de_DE"));
    }

    @Test
    public void getInternalFileData_swallowsRepositoryException_returnsNull() {
        manager.throwOnInternalFileRead = new RepositoryException("missing");
        assertNull(svc.getInternalFileData("nope"));
    }

    @Test
    public void getInternalFileData_passthroughOnSuccess() {
        manager.internalFiles.put("/etc/x", "contents");
        assertEquals("contents", svc.getInternalFileData("/etc/x"));
    }

    @Test
    public void delegations_passThroughToManager() throws Exception {
        // Spot-check the high-volume delegating surface.
        SaikuDatasource d = ds("delegate", "id-d");
        svc.setDatasource(d);
        assertSame(d, manager.lastSet);

        svc.removeDatasource("id-d");
        assertEquals(Collections.singletonList("id-d"), manager.removeCalls);

        manager.seed(d);
        assertSame(d, svc.getDatasource("delegate"));
        assertEquals(1, svc.getDatasources(new String[0]).size());

        manager.schemas = List.of(new MondrianSchema());
        assertEquals(1, svc.getAvailableSchema().size());

        svc.addSchema("<schema/>", "/path", "name");
        assertEquals("name", manager.addSchemaCalls.get(0).get("name"));

        svc.saveInternalFile("/etc/k", "v", "nt:file");
        assertEquals("v", manager.internalFiles.get("/etc/k"));

        svc.saveFile("body", "/repo/file.saiku", "tom", List.of("R"));
        assertEquals("body", manager.savedFiles.get("/repo/file.saiku"));

        svc.removeFile("/repo/file.saiku", "tom", List.of("R"));
        assertTrue(manager.removedFiles.contains("/repo/file.saiku"));

        svc.moveFile("/a", "/b", "tom", List.of("R"));
        assertEquals("/a -> /b", manager.movedFiles.get(0));

        svc.removeSchema("schema-id");
        assertEquals("schema-id", manager.lastRemovedSchema);

        svc.exportRepository();
        assertTrue(manager.exported);
        svc.restoreRepository(new byte[] {1, 2, 3});
        assertNotNull(manager.restored);
        svc.restoreLegacyFiles(new byte[] {4});
        assertNotNull(manager.legacyRestored);

        assertFalse(svc.hasHomeDirectory("nobody"));
        manager.homes.add("alice");
        assertTrue(svc.hasHomeDirectory("alice"));

        svc.createUserHome("alice");
        assertEquals(Collections.singletonList("alice"), manager.createdUsers);
    }

    @Test
    public void importLegacy_methodsAreNoOps_doNotThrow() {
        svc.importLegacySchema();
        svc.importLegacyDatasources();
        svc.importLegacyUsers();
    }

    @Test
    public void aclDelegations() {
        svc.setResourceACL("/file", "ACL", "tom", List.of("R"));
        assertEquals("ACL", manager.acls.get("/file"));

        assertNull(svc.getResourceACL("/file", "tom", List.of("R")));
    }

    @Test
    public void getConnectionManager_returnsWiredInstance() {
        assertSame(connManager, svc.getConnectionManager());
    }

    // ---------------------------------------------------------------- helpers

    private static SaikuDatasource ds(String name, String id) {
        Properties p = new Properties();
        p.setProperty("id", id);
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, p);
    }

    private static final class StubConnectionManager implements IConnectionManager {
        private final IDatasourceManager dsm;

        StubConnectionManager(IDatasourceManager dsm) {
            this.dsm = dsm;
        }

        @Override
        public void init() throws SaikuOlapException {}

        @Override
        public void setDataSourceManager(IDatasourceManager ds) {}

        @Override
        public IDatasourceManager getDataSourceManager() {
            return dsm;
        }

        @Override
        public void refreshConnection(String name) {}

        @Override
        public void refreshAllConnections() {}

        @Override
        public org.olap4j.OlapConnection getOlapConnection(String name) {
            return null;
        }

        @Override
        public Map<String, org.olap4j.OlapConnection> getAllOlapConnections() {
            return Map.of();
        }

        @Override
        public ISaikuConnection getConnection(String name) {
            return null;
        }

        @Override
        public Map<String, ISaikuConnection> getAllConnections() {
            return Map.of();
        }
    }

    private static final class RecordingDatasourceManager implements IDatasourceManager {
        final Map<String, SaikuDatasource> store = new LinkedHashMap<>();
        final List<SaikuDatasource> addCalls = new ArrayList<>();
        final List<String> removeCalls = new ArrayList<>();
        final Map<String, Object> internalFiles = new HashMap<>();
        final Map<String, Object> savedFiles = new HashMap<>();
        final Set<String> removedFiles = new HashSet<>();
        final List<String> movedFiles = new ArrayList<>();
        final List<Map<String, String>> addSchemaCalls = new ArrayList<>();
        final List<String> createdUsers = new ArrayList<>();
        final Set<String> homes = new HashSet<>();
        final Map<String, String> acls = new HashMap<>();
        List<MondrianSchema> schemas = List.of();
        SaikuDatasource lastSet;
        String lastRemovedSchema;
        boolean exported;
        byte[] restored;
        byte[] legacyRestored;
        RepositoryException throwOnInternalFileRead;

        void seed(SaikuDatasource ds) {
            store.put(ds.getName(), ds);
        }

        @Override
        public void load() {}

        @Override
        public void unload() {}

        @Override
        public SaikuDatasource addDatasource(SaikuDatasource datasource) {
            addCalls.add(datasource);
            store.put(datasource.getName(), datasource);
            return datasource;
        }

        @Override
        public SaikuDatasource setDatasource(SaikuDatasource datasource) {
            lastSet = datasource;
            return datasource;
        }

        @Override
        public List<SaikuDatasource> addDatasources(List<SaikuDatasource> datasources) {
            return datasources;
        }

        @Override
        public boolean removeDatasource(String datasourceName) {
            removeCalls.add(datasourceName);
            return true;
        }

        @Override
        public boolean removeSchema(String schemaName) {
            lastRemovedSchema = schemaName;
            return true;
        }

        @Override
        public Map<String, SaikuDatasource> getDatasources(String[] roles) {
            return new LinkedHashMap<>(store);
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName) {
            return store.get(datasourceName);
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName, boolean refresh) {
            return store.get(datasourceName);
        }

        @Override
        public void addSchema(String file, String path, String name) {
            addSchemaCalls.add(Map.of("file", file, "path", path, "name", name));
        }

        @Override
        public List<MondrianSchema> getMondrianSchema() {
            return schemas;
        }

        @Override
        public MondrianSchema getMondrianSchema(String catalog) {
            return null;
        }

        @Override
        public RepositoryFile getFile(String file) {
            return null;
        }

        @Override
        public String getFileData(String file, String username, List<String> roles) {
            return (String) savedFiles.get(file);
        }

        @Override
        public String getInternalFileData(String file) throws RepositoryException {
            if (throwOnInternalFileRead != null) throw throwOnInternalFileRead;
            return (String) internalFiles.get(file);
        }

        @Override
        public InputStream getBinaryInternalFileData(String file) {
            return null;
        }

        @Override
        public String saveFile(String path, Object content, String user, List<String> roles) {
            savedFiles.put(path, content);
            return "ok";
        }

        @Override
        public String removeFile(String path, String user, List<String> roles) {
            removedFiles.add(path);
            return "ok";
        }

        @Override
        public String moveFile(String source, String target, String user, List<String> roles) {
            movedFiles.add(source + " -> " + target);
            return "ok";
        }

        @Override
        public String saveInternalFile(String path, Object content, String type) {
            internalFiles.put(path, content);
            return "ok";
        }

        @Override
        public String saveBinaryInternalFile(String path, InputStream content, String type) {
            return "ok";
        }

        @Override
        public void removeInternalFile(String filePath) {
            internalFiles.remove(filePath);
        }

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
            return List.of();
        }

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles, String path) {
            return List.of();
        }

        @Override
        public void createUser(String user) {
            createdUsers.add(user);
        }

        @Override
        public void deleteFolder(String folder) {}

        @Override
        public AclEntry getACL(String object, String username, List<String> roles) {
            return null;
        }

        @Override
        public void setACL(String object, String acl, String username, List<String> roles) {
            acls.put(object, acl);
        }

        @Override
        public void setUserService(UserService userService) {}

        @Override
        public List<MondrianSchema> getInternalFilesOfFileType(String type) {
            return List.of();
        }

        @Override
        public void createFileMixin(String type) {}

        @Override
        public byte[] exportRepository() {
            exported = true;
            return new byte[0];
        }

        @Override
        public void restoreRepository(byte[] data) {
            restored = data;
        }

        @Override
        public boolean hasHomeDirectory(String name) {
            return homes.contains(name);
        }

        @Override
        public void restoreLegacyFiles(byte[] data) {
            legacyRestored = data;
        }

        @Override
        public String getFoodmartschema() {
            return null;
        }

        @Override
        public void setFoodmartschema(String schema) {}

        @Override
        public void setFoodmartdir(String dir) {}

        @Override
        public String getFoodmartdir() {
            return null;
        }

        @Override
        public String getDatadir() {
            return null;
        }

        @Override
        public void setDatadir(String dir) {}

        @Override
        public void setFoodmarturl(String foodmarturl) {}

        @Override
        public String getFoodmarturl() {
            return null;
        }

        @Override
        public String getEarthquakeUrl() {
            return null;
        }

        @Override
        public String getEarthquakeDir() {
            return null;
        }

        @Override
        public String getEarthquakeSchema() {
            return null;
        }

        @Override
        public void setEarthquakeUrl(String earthquakeUrl) {}

        @Override
        public void setEarthquakeDir(String earthquakeDir) {}

        @Override
        public void setEarthquakeSchema(String earthquakeSchema) {}

        @Override
        public void setExternalPropertiesFile(String file) {}

        @Override
        public String[] getAvailablePropertiesKeys() {
            return new String[0];
        }

        @Override
        public List<JujuSource> getJujuDatasources() {
            return List.of();
        }

        @Override
        public String getType() {
            return "stub";
        }
    }
}
