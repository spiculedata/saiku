/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.AclEntry;
import org.saiku.repository.IRepositoryObject;
import org.saiku.service.ISessionService;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.importer.JujuSource;
import org.saiku.service.user.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2326 (CWE-284/524/863) reversion guard for the AI schema cache's
 * visibility fingerprint: {@link SecurityAwareConnectionManager#visibilityKey}.
 *
 * <p>The AI schema cache is keyed by this fingerprint; before the fix it did
 * not exist and the cache was cube-keyed only, so the first caller's
 * role-scoped snapshot (hidden hierarchies, real member unique names) was
 * served to every later caller. The fingerprint must be STABLE across users
 * with the same Spring authorities (they share one cache entry), DIFFERENT
 * across callers whose cube visibility differs, per-principal for credential
 * passthrough, and must never collapse an unclassifiable security-enabled
 * datasource onto a shared key (fail closed).
 */
public class SecurityAwareConnectionManagerVisibilityKeyTest {

    @Before
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static SecurityAwareConnectionManager manager(FakeDatasourceManager ds, Map<String, Object> session) {
        SecurityAwareConnectionManager mgr = new SecurityAwareConnectionManager();
        mgr.setDataSourceManager(ds);
        mgr.setSessionService(new FakeSessionService(session));
        return mgr;
    }

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths = new ArrayList<>();
        for (String a : authorities) {
            auths.add(new SimpleGrantedAuthority(a));
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    private static SaikuDatasource datasource(String name, boolean securityEnabled) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, Boolean.toString(securityEnabled));
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, props);
    }

    private static SaikuDatasource passthroughDatasource(String name) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        props.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE);
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, props);
    }

    @Test
    public void visibilityKey_isStableAcrossUsersWithSameAuthorities() {
        FakeDatasourceManager ds = new FakeDatasourceManager();
        ds.seed(datasource("foodmart", true));
        authenticateAs("alice", "ROLE_USER", "ROLE_California manager");
        String aliceKey = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        authenticateAs("bob", "ROLE_USER", "ROLE_California manager");
        String bobKey = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        assertEquals("same role set shares the fingerprint (and the cache entry)", aliceKey, bobKey);
        assertTrue("a role fingerprint key", aliceKey.startsWith("roles:"));
    }

    @Test
    public void visibilityKey_differsAcrossRoleSets() {
        FakeDatasourceManager ds = new FakeDatasourceManager();
        ds.seed(datasource("foodmart", true));
        authenticateAs("alice", "ROLE_USER", "ROLE_California manager");
        String managerKey = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        authenticateAs("admin-user", "ROLE_USER", "ROLE_ADMIN");
        String adminKey = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        authenticateAs("plain", "ROLE_USER");
        String plainKey = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        assertNotEquals("California manager vs admin", managerKey, adminKey);
        assertNotEquals("restricted vs no-role", managerKey, plainKey);
        assertNotEquals("admin vs no-role", adminKey, plainKey);
    }

    @Test
    public void visibilityKey_securityDisabledCollapsesToSharedKey() {
        FakeDatasourceManager ds = new FakeDatasourceManager();
        ds.seed(datasource("foodmart", false));
        authenticateAs("alice", "ROLE_USER");
        String first = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        authenticateAs("bob", "ROLE_ADMIN");
        String second = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        assertEquals("unsecured datasource: one shared key for everyone", "", first);
        assertEquals(first, second);
    }

    @Test
    public void visibilityKey_failClosed_whenDatasourceUnresolvable() {
        // The name resolves to nothing (decoration the repository never saw,
        // unknown datasource, repository hiccup): must NOT collapse onto the
        // shared key — per identity instead.
        FakeDatasourceManager ds = new FakeDatasourceManager();
        ds.seed(datasource("foodmart", true));
        authenticateAs("alice", "ROLE_USER", "ROLE_California manager");
        String alice = manager(ds, new HashMap<>()).visibilityKey("mystery_ds");
        authenticateAs("bob", "ROLE_USER", "ROLE_California manager");
        String bob = manager(ds, new HashMap<>()).visibilityKey("mystery_ds");
        assertTrue("fail closed: per-identity key, not a shared fingerprint", alice.startsWith("user:"));
        assertNotEquals("two identities never share an unclassifiable datasource", alice, bob);
    }

    @Test
    public void visibilityKey_passthroughKeysPerPrincipal() {
        FakeDatasourceManager ds = new FakeDatasourceManager();
        ds.seed(passthroughDatasource("warehouse"));
        authenticateAs("alice", "ROLE_USER");
        String alice = manager(ds, new HashMap<>()).visibilityKey("warehouse");
        authenticateAs("bob", "ROLE_USER");
        String bob = manager(ds, new HashMap<>()).visibilityKey("warehouse");
        assertNotEquals("passthrough connects with the caller's own credentials", alice, bob);
        authenticateAs("alice", "ROLE_USER");
        assertEquals(alice, manager(ds, new HashMap<>()).visibilityKey("warehouse"));
    }

    @Test
    public void visibilityKey_noAuthoritiesAtAll_neverShared() {
        FakeDatasourceManager ds = new FakeDatasourceManager();
        ds.seed(datasource("foodmart", true));
        String key = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        assertTrue("no caller identity and no roles: still fail closed", key.startsWith("user:"));
        // Two different principals with an empty authority list still differ.
        authenticateAs("alice");
        String alice = manager(ds, new HashMap<>()).visibilityKey("foodmart");
        assertNotEquals(key, alice);
    }

    /** Minimal IDatasourceManager — only getDatasource / getDatasources matter here. */
    private static final class FakeDatasourceManager implements IDatasourceManager {

        private final Map<String, SaikuDatasource> store = new LinkedHashMap<>();

        void seed(SaikuDatasource ds) {
            store.put(ds.getName(), ds);
        }

        @Override
        public void load() {}

        @Override
        public void unload() {}

        @Override
        public SaikuDatasource addDatasource(SaikuDatasource datasource) {
            store.put(datasource.getName(), datasource);
            return datasource;
        }

        @Override
        public SaikuDatasource setDatasource(SaikuDatasource datasource) {
            store.put(datasource.getName(), datasource);
            return datasource;
        }

        @Override
        public List<SaikuDatasource> addDatasources(List<SaikuDatasource> datasources) {
            return Collections.emptyList();
        }

        @Override
        public boolean removeDatasource(String datasourceName) {
            return store.remove(datasourceName) != null;
        }

        @Override
        public boolean removeSchema(String schemaName) {
            return false;
        }

        @Override
        public Map<String, SaikuDatasource> getDatasources(String[] roles) {
            return new HashMap<>(store);
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
        public void addSchema(String file, String path, String name) {}

        @Override
        public List<MondrianSchema> getMondrianSchema() {
            return Collections.emptyList();
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
            return null;
        }

        @Override
        public String getInternalFileData(String file) {
            return null;
        }

        @Override
        public java.io.InputStream getBinaryInternalFileData(String file) {
            return null;
        }

        @Override
        public String saveFile(String path, Object content, String user, List<String> roles) {
            return null;
        }

        @Override
        public String removeFile(String path, String user, List<String> roles) {
            return null;
        }

        @Override
        public String moveFile(String source, String target, String user, List<String> roles) {
            return null;
        }

        @Override
        public String saveInternalFile(String path, Object content, String type) {
            return null;
        }

        @Override
        public String saveBinaryInternalFile(String path, java.io.InputStream content, String type) {
            return null;
        }

        @Override
        public void removeInternalFile(String filePath) {}

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
            return Collections.emptyList();
        }

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles, String path) {
            return Collections.emptyList();
        }

        @Override
        public void createUser(String user) {}

        @Override
        public void deleteFolder(String folder) {}

        @Override
        public AclEntry getACL(String object, String username, List<String> roles) {
            return null;
        }

        @Override
        public void setACL(String object, String acl, String username, List<String> roles) {}

        @Override
        public void setUserService(UserService userService) {}

        @Override
        public List<MondrianSchema> getInternalFilesOfFileType(String type) {
            return Collections.emptyList();
        }

        @Override
        public void createFileMixin(String type) {}

        @Override
        public byte[] exportRepository() {
            return new byte[0];
        }

        @Override
        public void restoreRepository(byte[] data) {}

        @Override
        public boolean hasHomeDirectory(String name) {
            return false;
        }

        @Override
        public void restoreLegacyFiles(byte[] data) {}

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
            return Collections.emptyList();
        }

        @Override
        public String getType() {
            return "test";
        }
    }

    /** Minimal ISessionService fake — only getAllSessionObjects() is read. */
    private static final class FakeSessionService implements ISessionService {
        private final Map<String, Object> session;

        FakeSessionService(Map<String, Object> session) {
            this.session = session;
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return session;
        }

        @Override
        public Map<String, Object> login(HttpServletRequest req, String username, String password) {
            return null;
        }

        @Override
        public void logout(HttpServletRequest req) {}

        @Override
        public void authenticate(HttpServletRequest req, String username, String password) {}

        @Override
        public Map<String, Object> getSession() {
            return session;
        }

        @Override
        public void clearSessions(HttpServletRequest req, String username, String password) {}
    }
}
