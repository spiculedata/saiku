/*
 *   Copyright 2014 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.saiku.service.datasource;

import jakarta.servlet.http.HttpSession;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.*;
import org.saiku.repository.PathNotFoundException;
import org.saiku.repository.RepositoryException;
import org.saiku.service.importer.JujuSource;
import org.saiku.service.user.UserService;
import org.saiku.service.util.security.authentication.PasswordProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.session.HttpSessionCreatedEvent;

/**
 * A Datasource Manager for the Saiku Repository API layer.
 */
public class RepositoryDatasourceManager implements IDatasourceManager, ApplicationListener<HttpSessionCreatedEvent> {
    public static final String ORBIS_WORKSPACE_DIR = "workspace";
    public static final String SAIKU_AUTH_PRINCIPAL = "SAIKU_AUTH_PRINCIPAL";

    /**
     * Per-workspace datasource caches. Outer key is the Saiku workspace name
     * (see {@link #ORBIS_WORKSPACE_DIR} session attribute); inner map is the
     * usual datasource-name → SaikuDatasource lookup.
     *
     * <p>Previously this was a single flat map. That meant every refresh /
     * mutation operated globally — a refresh issued by tenant A clobbered
     * tenant B's view. The per-workspace structure makes refresh + mutation
     * tenant-local; tenants only ever see entries for their own workspace.
     *
     * <p>Inner maps are loaded lazily on first access per workspace
     * (see {@link #datasourcesForCurrentWorkspace()}).
     */
    private final Map<String, Map<String, SaikuDatasource>> tenantDatasources = new ConcurrentHashMap<>();

    /**
     * Default workspace name when no session attribute is set (boot, scheduled
     * tasks, single-tenant deployments with {@code workspaces=false}). Mirrors
     * the legacy {@code unknown} directory the engine has always used as the
     * unscoped default.
     */
    private static final String DEFAULT_WORKSPACE = "unknown";

    /**
     * Allowlist for datasource names (saiku#1906, CWE-22): the connection name flows straight
     * into filesystem paths — {@code <datadir>/datasources/<name>.sds} and, for CSV datasources,
     * {@code <name>-csv.json} — with no sanitisation, so a name carrying {@code ../} segments (or
     * a Windows drive letter / UNC / ADS colon) can escape the datadir entirely. Must start with
     * a Unicode letter or digit, then Unicode letters/digits plus space / dot / underscore /
     * parens / hyphen, max 128 chars.
     *
     * <p>saiku#1906 SEC follow-up: the original ASCII-only {@code [A-Za-z0-9 ._-]} rejected real,
     * already-stored datasource names on re-save (admin update, cube-designer save-and-attach) —
     * accented/international names and parenthesised names especially. Widened to Unicode letters
     * and digits ({@code \p{L}}/{@code \p{N}}, which are Unicode-aware by definition — no
     * {@code UNICODE_CHARACTER_CLASS} flag needed) plus parens. Still an allowlist: every
     * path/URL/JSON metacharacter ({@code / \ : * ? " < > |}), control chars, {@code '}, and
     * {@code & # , @ ; =} stay excluded — the name also flows into a
     * {@code mondrian://…/<name>.xml} URL and a quoted CSV JSON, so punctuation stays
     * conservative; this only widens enough to stop breaking real names. Deliberately ALLOWS
     * internal spaces: existing datasource names may already contain them, so this is a
     * path-safety filter, not a strict identifier rule.
     */
    private static final Pattern DATASOURCE_NAME_PATTERN =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} ._()-]{0,127}$");

    public IConnectionManager connectionManager;
    private ScopedRepo sessionRegistry;
    private boolean workspaces;
    private UserService userService;
    private static final Logger log = LoggerFactory.getLogger(RepositoryDatasourceManager.class);
    private String configurationpath;
    private String datadir;
    private IRepositoryManager irm;
    private String foodmartdir;
    private String foodmartschema;
    private String foodmarturl;
    private PasswordProvider repopasswordprovider;
    private String oldpassword;
    private String earthquakeurl;
    private String earthquakedir;
    private String earthquakeschema;
    private String defaultRole;
    private String externalparameters;
    private String type;
    private String separator = "/";
    private String host;
    private String port;
    private String username;
    private String password;
    private String database;
    private String datasourceProcessor;
    private String connectionProcessor;

    public void setConnectionManager(IConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    // Whenever a new Spring Security Session
    public void onApplicationEvent(HttpSessionCreatedEvent sessionEvent) {
        // Reload the datasources
        loadDatasources(checkForExternalDataSourceProperties());
    }

    public void load() {
        Properties ext = checkForExternalDataSourceProperties();

        // Phase 2: Jackrabbit content-repo backend deleted; only classpath remains.
        separator = "/";
        log.debug("init datadir= " + datadir);
        irm = createRepositoryManager();
        log.debug("2nd init datadir= " + datadir);

        // Perform the repository manager startup routines
        try {
            irm.start(userService);
            this.saveInternalFile("/etc" + separator + ".repo_version", "d20f0bea-681a-11e5-9d70-feff819cdc9f", null);
        } catch (RepositoryException e) {
            log.error("Could not start repo", e);
        }

        // saiku#1844: teach Mondrian to read schemas out of THIS repository. Every data source
        // the admin UI writes carries Catalog=mondrian://<schema> (see DataSourceMapper), a
        // scheme Mondrian's stock file handler has never been able to resolve — so the cube
        // never loaded. Installed after irm.start() so the reader is usable the moment
        // loadDatasources() below triggers the first connection.
        SaikuVirtualFileHandler.install(this::readRepositoryFileQuietly);
        // saiku#1845: and confine file: catalogs to the Saiku data directories, so a data source
        // can't be pointed at an arbitrary host file.
        SaikuVirtualFileHandler.setFileGuard(SchemaFileAccessGuard.fromEnvironment(datadir));

        // Load the datasources
        loadDatasources(ext);
    }

    /**
     * Factory hook for the underlying {@link IRepositoryManager}. The default
     * implementation returns a {@link FilesystemRepositoryManager} configured
     * from this service's properties — the long-standing OSS behaviour.
     *
     * <p>Downstream distributions (e.g. Saiku Cloud) override this method to
     * substitute a wrapping implementation that, say, routes specific path
     * prefixes through a Postgres-backed store while leaving everything else
     * on the filesystem. Subclasses typically call {@code super.createRepositoryManager()}
     * to obtain the filesystem instance and wrap it.
     *
     * <p>Kept {@code protected} so the hook is invisible to bean consumers but
     * available to subclasses; private state read here ({@code datadir},
     * {@code defaultRole}, {@code sessionRegistry}, {@code workspaces}) stays
     * encapsulated.
     */
    protected IRepositoryManager createRepositoryManager() {
        return FilesystemRepositoryManager.getFilesystemRepositoryManager(
                cleanse(datadir), defaultRole, sessionRegistry, workspaces);
    }

    public void setRepositoryManager(IRepositoryManager irm) {
        this.irm = irm;
    }

    public Properties checkForExternalDataSourceProperties() {
        Properties p = new Properties();
        if (externalparameters == null || externalparameters.isEmpty()) {
            // No external-params file configured — return empty. The
            // legacy implementation hit `new FileInputStream(null)` here
            // which throws NPE, NOT IOException. The catch below would
            // miss it and the NPE would escape. The pre-refactor flow
            // happened to call load() once at boot with externalparameters
            // always set; the lazy datasource cache can hit this earlier
            // in the bean-graph init (saiku-cloud tenant scoping uses it).
            return p;
        }
        // try-with-resources so the external-params file handle is always closed (saiku#1191).
        try (InputStream input = new FileInputStream(externalparameters)) {
            p.load(input);
        } catch (IOException e) {
            log.debug("file did not exist");
        }
        return p;
    }

    public String[] getAvailablePropertiesKeys() {
        Properties p = new Properties();

        // try-with-resources so the external-params file handle is always closed (saiku#1191).
        try (InputStream input = new FileInputStream(externalparameters)) {
            p.load(input);
        } catch (IOException e) {
            log.debug("file did not exist");
        }

        String[] arr = p.keySet().toArray(new String[p.keySet().size()]);

        ArrayList<String> newlist = new ArrayList<>();
        for (String str : arr) {
            String[] s = str.split("\\.");
            newlist.add(s[1]);
        }
        Set<String> unique = new HashSet<>(newlist);

        return unique.toArray(new String[unique.size()]);
    }

    public void unload() {
        irm.shutdown();
    }

    public SaikuDatasource addDatasource(SaikuDatasource datasource) throws Exception {
        DataSource ds = new DataSource(datasource);
        // saiku#1906: defence-in-depth behind FilesystemRepositoryManager's own path-traversal
        // guard — reject the name here, at the one chokepoint every write below (csv json,
        // workspace mondrian catalog path, and the final .sds descriptor) keys off.
        validateDatasourceName(ds.getName());

        // saiku#1864: the load path decorates every name as `<workspace>_<storedName>`
        // (FilesystemRepositoryManager.getAllDataSources). Nothing undid that here, so a client
        // that read a datasource, changed a field and wrote it back saved `unknown_foo.sds`
        // ALONGSIDE the original `foo.sds` — a duplicate sharing the same id, with the original
        // name still serving the old catalog. Strip the decoration so read-modify-write lands on
        // the datasource it came from.
        ds.setName(DatasourceNameDecoration.undecorate(ds.getName(), currentWorkspaceKey()));

        if (ds.getCsv() != null && ds.getCsv().equals("true")) {
            String split[] = ds.getLocation().split("=");
            String loc = split[2];
            if (split[2].startsWith("mondrian:")) {
                split[2] = "mondrian:/" + getDatadir() + "datasources/" + ds.getName() + "-csv.json;Catalog";
            } else {
                split[2] = getDatadir() + "datasources/" + ds.getName() + "-csv.json;Catalog";
                split[2] = split[2].replace('\\', '/');
                split[2] = split[2].replaceAll("[/]+", "/");
            }

            for (int i = 0; i < split.length - 1; i++) {
                split[i] = split[i] + "=";
            }

            ds.setLocation(StringUtils.join(split));

            log.debug("LOC IS: " + loc);
            String path = loc.substring(0, loc.lastIndexOf(";"));

            log.debug("PATH IS: " + path);
            path = path.replace("\\", "/");
            path = path.replaceAll("[/]+", "/");

            log.debug("Trimmed path is: " + path);
            if (!datadir.equals("${CLASSPATH_REPO_PATH_UNPARSED}")) {
                path = path.replaceFirst(getDatadir(), "");
            }

            // When using Jackrabbit, paths should follow JCR standards
            if (this.type.equals("jackrabbit")) {
                if (!path.startsWith("mondrian://")) {
                    String oldHomePrefix = "/homes/";
                    String newHomePrefix = "mondrian://homes/home:";

                    path = newHomePrefix + path.substring(oldHomePrefix.length());
                }
            }

            boolean f = true;

            if (new File(getDatadir() + path).exists() && new File(getDatadir() + path).isDirectory()) {
                f = false;
            }

            path = path.replace("\\", "/");
            path = path.replaceAll("[/]+", "/");

            if (!path.startsWith("mondrian:")) {
                String pathToSave = getDatadir() + path;

                pathToSave = pathToSave.replace("\\", "/");
                pathToSave = pathToSave.replaceAll("[/]+", "/");

                irm.saveInternalFile(
                        this.getCSVJson(f, ds.getName(), pathToSave),
                        separator + "datasources" + separator + ds.getName() + "-csv.json",
                        null);
            } else {
                irm.saveInternalFile(
                        this.getCSVJson(f, ds.getName(), path),
                        separator + "datasources" + separator + ds.getName() + "-csv.json",
                        null);
            }

            irm.saveDataSource(ds, separator + "datasources" + separator + ds.getName() + ".sds", "fixme");

            // Cache under the decorated name — the key a reload would produce (saiku#1864).
            String name = decoratedName(ds.getName());

            // Adding the connection before refreshing it
            // Preserve the incoming type (OLAP/OSSIE) rather than hard-coding OLAP — Ossie
            // datasources need their type carried through so the connection factory picks the
            // right ISaikuConnection subclass.
            SaikuDatasource sds = new SaikuDatasource(name, datasource.getType(), datasource.getProperties());
            datasourcesForCurrentWorkspace().put(name, sds);

            // In a workspace environment it is necessary to prefix the datasource name with the workspace name
            connectionManager.refreshConnection(name);
        } else {
            irm.saveDataSource(ds, separator + "datasources" + separator + ds.getName() + ".sds", "fixme");
        }

        // The FILE is stored undecorated (see the strip at the top of this method), but the cache
        // has to be keyed the way a reload would key it — decorated — or every lookup between now
        // and the next restart misses (saiku#1864).
        String name = decoratedName(ds.getName());
        SaikuDatasource sds = new SaikuDatasource(name, SaikuDatasource.Type.OLAP, datasource.getProperties());

        // Cache per-workspace — see datasourcesForCurrentWorkspace() for the
        // tenant-scoping rationale.
        datasourcesForCurrentWorkspace().put(name, sds);

        return datasource;
    }

    /**
     * Reject any datasource name that could escape the datadir once concatenated into a file path
     * (see {@link #DATASOURCE_NAME_PATTERN}), or that could silently fail to persist because the
     * resulting filename is too long for the underlying filesystem. Fail-closed: null,
     * non-matching, or over-length names are all rejected.
     *
     * <p>saiku#1906 SEC follow-up (data loss): {@link #DATASOURCE_NAME_PATTERN} caps at 128
     * Unicode code points, but {@code saveDataSource} writes the name as UTF-8 bytes in a
     * filename — 128 CJK/astral characters can already be 380+ UTF-8 bytes, past ext4's
     * 255-byte {@code NAME_MAX} once the {@code -csv.json} suffix or a workspace prefix is
     * added. {@code saveDataSource} swallows the resulting IOException, so without this check a
     * REST caller would see 200 OK while the datasource silently vanishes on the next restart.
     * 200 bytes leaves headroom for both.
     *
     * <p>saiku#1906 SEC follow-up (CWE-117): the rejection message deliberately does NOT echo the
     * raw name. This exception's message ends up in a REST 500 body and in {@code log.error} call
     * sites downstream, and the whole point of this check is that the name isn't trusted yet — an
     * attacker-supplied name containing a newline would otherwise be log-line injection.
     */
    private static void validateDatasourceName(String name) {
        if (name == null
                || !DATASOURCE_NAME_PATTERN.matcher(name).matches()
                || name.getBytes(StandardCharsets.UTF_8).length > 200) {
            throw new IllegalArgumentException("Illegal datasource name");
        }
    }

    /**
     * The name a stored datasource is surfaced under, i.e. what {@code getAllDataSources} will call
     * it after the next load. Keep this the inverse of {@link DatasourceNameDecoration#undecorate}.
     */
    private String decoratedName(String storedName) {
        String workspace = currentWorkspaceKey();
        if (storedName == null || workspace == null || workspace.isEmpty()) {
            return storedName;
        }
        return storedName.startsWith(workspace + "_") ? storedName : workspace + "_" + storedName;
    }

    public SaikuDatasource setDatasource(SaikuDatasource datasource) throws Exception {
        return addDatasource(datasource);
    }

    public List<SaikuDatasource> addDatasources(List<SaikuDatasource> dsources) {
        for (SaikuDatasource datasource : dsources) {
            DataSource ds = new DataSource(datasource);

            try {
                // saiku#1906: same allowlist chokepoint as addDatasource() — this bulk path
                // built the .sds file path off the name with no validation at all. The
                // FilesystemRepositoryManager-side createNode() backstop only protects the
                // filesystem impl; a non-filesystem IRepositoryManager (e.g. Saiku Cloud's
                // Postgres-backed store) wouldn't get it, so validate here too.
                validateDatasourceName(ds.getName());
                irm.saveDataSource(ds, separator + "datasources" + separator + ds.getName() + ".sds", "fixme");
                datasourcesForCurrentWorkspace().put(datasource.getName(), datasource);

            } catch (IllegalArgumentException | RepositoryException e) {
                log.error("Could not add data source: {}", datasource.getName(), e);
            }
        }
        return dsources;
    }

    public boolean removeDatasource(String datasourceId) {
        List<DataSource> ds = null;
        try {
            ds = irm.getAllDataSources();
        } catch (RepositoryException e) {
            log.error("Could not get all data sources");
        }

        if (ds != null) {
            for (DataSource data : ds) {
                if (data.getId().equals(datasourceId)) {
                    datasourcesForCurrentWorkspace().remove(data.getName());
                    String path = data.getPath();
                    if (!datadir.equals("${CLASSPATH_REPO_PATH_UNPARSED}")) {
                        path = path.replaceFirst(datadir, "");
                    }
                    irm.deleteFile(path);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean removeSchema(String schemaName) {
        List<org.saiku.database.dto.MondrianSchema> s = null;
        try {
            s = irm.getAllSchema();
        } catch (RepositoryException e) {
            log.error("Could not get All Schema", e);
        }

        if (s != null) {
            for (MondrianSchema data : s) {
                if (data.getName().equals(schemaName)) {
                    irm.deleteFile(data.getPath());
                    break;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public Map<String, SaikuDatasource> getDatasources(String[] roles) {
        return datasourcesForCurrentWorkspace();
    }

    public SaikuDatasource getDatasource(String datasourceName) {
        return lookup(datasourcesForCurrentWorkspace(), datasourceName);
    }

    /**
     * Resolve a datasource by name, accepting it with OR without the workspace decoration.
     *
     * <p>saiku#1869: every datasource is surfaced as {@code <workspace>_<storedName>} — in
     * single-tenant OSS that is always {@code unknown_}, a multi-tenant artefact carrying no
     * information. It is baked into saved queries, dashboards and apps, both as the connection name
     * and inside MDX unique names ({@code [unknown_foodmart].[FoodMart]...}), so it cannot simply be
     * dropped without breaking every existing install.
     *
     * <p>Accepting both spellings here is step one, and it is deliberately harmless on its own:
     * nothing is renamed yet, so this only widens what already resolves. It is what lets the
     * decoration be switched off later without a migration — old content keeps resolving through
     * the alias, and if some path is missed it degrades to a lookup that still works rather than a
     * connection that cannot be found.
     *
     * <p>Every by-name path funnels through here ({@code getConnection}, {@code getOlapConnection},
     * {@code refreshConnection}, discover, query), which is why the alias lives at this one point
     * rather than at each caller.
     */
    private SaikuDatasource lookup(Map<String, SaikuDatasource> pool, String datasourceName) {
        if (pool == null || datasourceName == null) {
            return null;
        }
        SaikuDatasource exact = pool.get(datasourceName);
        if (exact != null) {
            return exact;
        }
        String workspace = currentWorkspaceKey();
        // Asked for "foodmart" but stored/keyed as "unknown_foodmart", or vice versa.
        SaikuDatasource decorated = pool.get(DatasourceNameDecoration.decorate(datasourceName, workspace));
        if (decorated != null) {
            return decorated;
        }
        return pool.get(DatasourceNameDecoration.undecorate(datasourceName, workspace));
    }

    @Override
    public SaikuDatasource getDatasource(String datasourceName, boolean refresh) {
        Map<String, SaikuDatasource> current = datasourcesForCurrentWorkspace();
        if (!refresh) {
            if (current.size() > 0) {
                return lookup(current, datasourceName);
            }
        } else {
            return getDatasource(datasourceName);
        }
        return null;
    }

    public void addSchema(String file, String path, String name) throws Exception {
        irm.saveInternalFile(file, path, "nt:mondrianschema");
    }

    public List<MondrianSchema> getMondrianSchema() {
        try {
            return irm.getAllSchema();
        } catch (RepositoryException e) {
            log.error("Could not get all Schema", e);
        }
        return null;
    }

    public MondrianSchema getMondrianSchema(String catalog) {
        // return irm.getMondrianSchema();
        return null;
    }

    public RepositoryFile getFile(String file) {
        return irm.getFile(file);
    }

    public String getFileData(String file, String username, List<String> roles) {
        try {
            return irm.getFile(file, username, roles);
        } catch (RepositoryException e) {
            log.error("Could not get file " + file, e);
        }
        return null;
    }

    public String getInternalFileData(String file) throws RepositoryException {

        return irm.getInternalFile(file);
    }

    /**
     * Repository read that reports "absent" instead of throwing — the shape
     * {@link SaikuVirtualFileHandler} needs.
     *
     * <p>saiku#1844. A miss here is ordinary: {@link MondrianCatalogResolver} probes more than one
     * candidate path per catalog and expects null for the ones that don't exist. Mondrian is
     * mid-connection when this runs, and a thrown {@link RepositoryException} would surface as an
     * opaque schema-load failure rather than the resolver simply trying its next candidate.
     */
    private String readRepositoryFileQuietly(String path) {
        try {
            return irm.getInternalFile(path);
        } catch (RepositoryException e) {
            log.debug("No repository file at {}", path, e);
            return null;
        }
    }

    public InputStream getBinaryInternalFileData(String file) throws RepositoryException {

        return irm.getBinaryInternalFile(file);
    }

    public String saveFile(String path, Object content, String user, List<String> roles) {
        try {
            irm.saveFile(content, path, user, "nt:saikufiles", roles);
            return "Save Okay";
        } catch (RepositoryException e) {
            log.error("Save Failed", e);
            return "Save Failed: " + e.getLocalizedMessage();
        }
    }

    public String removeFile(String path, String user, List<String> roles) {
        try {
            irm.removeFile(path, user, roles);
            return "Remove Okay";
        } catch (RepositoryException e) {
            log.error("Save Failed", e);
            return "Save Failed: " + e.getLocalizedMessage();
        }
    }

    public String moveFile(String source, String target, String user, List<String> roles) {
        try {
            irm.moveFile(source, target, user, roles);
            return "Move Okay";
        } catch (RepositoryException e) {
            log.error("Move Failed", e);
            return "Move Failed: " + e.getLocalizedMessage();
        }
    }

    public String saveInternalFile(String path, Object content, String type) {
        try {
            irm.saveInternalFile(content, path, type);
            return "Save Okay";
        } catch (RepositoryException e) {
            log.error("saveInternalFile failed for {}", path, e);
            return "Save Failed: " + e.getLocalizedMessage();
        }
    }

    public String saveBinaryInternalFile(String path, InputStream content, String type) {
        try {
            irm.saveBinaryInternalFile(content, path, type);
            return "Save Okay";
        } catch (RepositoryException e) {
            log.error("saveBinaryInternalFile failed for {}", path, e);
            return "Save Failed: " + e.getLocalizedMessage();
        }
    }

    public void removeInternalFile(String filePath) {
        try {
            irm.removeInternalFile(filePath);
        } catch (RepositoryException e) {
            log.error("Remove file failed: {}", filePath, e);
        }
    }

    public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
        return irm.getAllFiles(type, username, roles);
    }

    public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles, String path) {
        try {
            return irm.getAllFiles(type, username, roles, path);
        } catch (RepositoryException e) {
            log.error("Get failed", e);
        }
        return null;
    }

    public void createUser(String username) {
        try {
            irm.createUser(username);
        } catch (RepositoryException e) {
            log.error("Create User Failed", e);
        }
    }

    public void deleteFolder(String folder) {
        try {
            irm.deleteFolder(folder);
        } catch (RepositoryException e) {
            log.error("Delete User Failed", e);
        }
    }

    public AclEntry getACL(String object, String username, List<String> roles) {
        return irm.getACL(object, username, roles);
    }

    public void setACL(String object, String acl, String username, List<String> roles) {
        try {
            irm.setACL(object, acl, username, roles);
        } catch (RepositoryException e) {
            log.error("Set ACL Failed", e);
        }
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    public List<MondrianSchema> getInternalFilesOfFileType(String type) {
        try {
            return irm.getInternalFilesOfFileType(type);
        } catch (RepositoryException e) {
            log.error("Get internal file failed", e);
        }
        return null;
    }

    public void createFileMixin(String type) throws RepositoryException {
        irm.createFileMixin(type);
    }

    public byte[] exportRepository() {
        try {
            return irm.exportRepository();

        } catch (RepositoryException e) {
            log.error("could not export repository", e);
        } catch (IOException e) {
            log.error("could not export repository IO issue", e);
        }
        return null;
    }

    public void restoreRepository(byte[] data) {
        try {
            irm.restoreRepository(data);
        } catch (Exception e) {
            log.error("Could not restore export", e);
        }
    }

    public boolean hasHomeDirectory(String name) {
        try {
            Object eturn = irm.getHomeFolder(name);
            return eturn != null;
        } catch (PathNotFoundException e) {
            return false;
        } catch (RepositoryException e) {
            log.error("could not get home directory");
        }
        return false;
    }

    public void restoreLegacyFiles(byte[] data) {}

    public Object getRepository() {
        return irm.getRepositoryObject();
    }

    public void setConfigurationpath(String configurationpath) {
        this.configurationpath = configurationpath;
    }

    public String getConfigurationpath() {
        return configurationpath;
    }

    public void setDatadir(String datadir) {
        datadir = datadir.replaceFirst(":", ":/");

        this.datadir = datadir;
    }

    /**
     * @return the current request's workspace name from the bound
     *         {@link HttpSession}, or {@link #DEFAULT_WORKSPACE} when no
     *         session is in scope or the attribute is unset. Cleansed in
     *         the same way as {@code FilesystemRepositoryManager.cleanse()}
     *         so the value is filesystem-safe.
     */
    private String currentWorkspaceKey() {
        if (!workspaces) {
            return DEFAULT_WORKSPACE;
        }
        try {
            jakarta.servlet.http.HttpSession session = getSession();
            if (session == null) {
                return DEFAULT_WORKSPACE;
            }
            Object attr = session.getAttribute(ORBIS_WORKSPACE_DIR);
            if (attr == null) {
                return DEFAULT_WORKSPACE;
            }
            String workspace = ((String) attr).trim();
            return workspace.isEmpty() ? DEFAULT_WORKSPACE : cleanse(workspace);
        } catch (Exception e) {
            log.debug("could not resolve current workspace, falling back to default", e);
            return DEFAULT_WORKSPACE;
        }
    }

    /**
     * Returns the inner datasource map for the current request's workspace,
     * loading it lazily on first access.
     *
     * <p>Lazy + per-workspace is the load-bearing change: the legacy global
     * map was clobbered every time any tenant ran {@code /admin/discover/refresh}.
     * With per-workspace caching, that operation only affects the caller's
     * workspace; other tenants keep serving from their own warm caches.
     *
     * <p>Returns an empty map during bean wiring (before {@link #load()} has
     * run): the IRepositoryManager isn't initialised yet and trying to scan
     * the filesystem would NPE on {@code externalparameters}. The legacy
     * empty-flat-map happened to short-circuit this; the lazy-load path
     * needs an explicit guard.
     */
    private Map<String, SaikuDatasource> datasourcesForCurrentWorkspace() {
        if (irm == null) {
            // Bean init is still in progress (connectionManager → userService
            // → datasourceService → here). Hand back an empty map; load() will
            // populate the default workspace once it runs.
            return new ConcurrentHashMap<>();
        }
        return tenantDatasources.computeIfAbsent(
                currentWorkspaceKey(), k -> loadDatasourcesFor(k, checkForExternalDataSourceProperties()));
    }

    public String getDatadir() {
        if (this.type.equals("classpath")) {
            if (this.workspaces) {
                try {
                    if (getSession().getAttribute(ORBIS_WORKSPACE_DIR) != null) {
                        String workspace = (String) getSession().getAttribute(ORBIS_WORKSPACE_DIR);
                        if (!workspace.equals("")) {
                            workspace = cleanse(workspace);
                        }
                        log.debug("Workspace directory set to:" + datadir + workspace);
                        return cleanse(datadir) + workspace;
                    } else {
                        log.debug("Workspace directory set to:" + datadir + "unknown/");
                        return cleanse(datadir) + "unknown/";
                    }

                } catch (Exception e) {
                    return cleanse(datadir) + "unknown/";
                }
            } else {
                return cleanse(datadir);
            }
        } else {
            return "/";
        }
    }

    private String getCookieUsername() {
        String cookieUsername = null;
        jakarta.servlet.http.HttpSession session =
                getSession(); // Use a variable instead of a method call for debugging purposes

        if (session != null && workspaces && session.getAttribute(SAIKU_AUTH_PRINCIPAL) != null) {
            cookieUsername = (String) session.getAttribute(SAIKU_AUTH_PRINCIPAL);
        }

        if (cookieUsername != null && cookieUsername.trim().length() == 0) {
            cookieUsername = null;
        }

        return cookieUsername;
    }

    private String getworkspacedir() {
        try {
            if (this.workspaces && getSession().getAttribute(ORBIS_WORKSPACE_DIR) != null) {
                String workspace = (String) getSession().getAttribute(ORBIS_WORKSPACE_DIR);
                if (!workspace.equals("")) {
                    workspace = cleanse(workspace);
                }
                log.debug("Workspace directory set to:" + workspace);
                return workspace;
            } else if (this.workspaces) {
                log.debug("Workspace directory set to: unknown/");
                return "unknown/";
            } else {
                return "";
            }

        } catch (Exception e) {
            return "unknown/";
        }
    }

    public String cleanse(String workspace) {
        workspace = workspace.replace("\\", "/");
        workspace = workspace.replaceAll("[/]+", "/");

        if (!workspace.endsWith("/")) {
            return workspace + "/";
        }

        return workspace;
    }

    public void setFoodmartdir(String foodmartdir) {
        this.foodmartdir = foodmartdir;
    }

    public String getFoodmartdir() {
        return foodmartdir;
    }

    public void setFoodmartschema(String foodmartschema) {
        this.foodmartschema = foodmartschema;
    }

    public String getFoodmartschema() {
        return foodmartschema;
    }

    public void setFoodmarturl(String foodmarturl) {
        this.foodmarturl = foodmarturl;
    }

    public String getFoodmarturl() {
        return foodmarturl;
    }

    public String getEarthquakeUrl() {
        return earthquakeurl;
    }

    public String getEarthquakeDir() {
        return earthquakedir;
    }

    public String getEarthquakeSchema() {
        return earthquakeschema;
    }

    public void setEarthquakeUrl(String earthquakeurl) {
        this.earthquakeurl = earthquakeurl;
    }

    public void setEarthquakeDir(String earthquakedir) {
        this.earthquakedir = earthquakedir;
    }

    public void setEarthquakeSchema(String earthquakeschema) {
        this.earthquakeschema = earthquakeschema;
    }

    @Override
    public void setExternalPropertiesFile(String file) {
        this.externalparameters = file;
    }

    public void setRepoPasswordProvider(PasswordProvider passwordProvider) {
        this.repopasswordprovider = passwordProvider;
    }

    public PasswordProvider getRepopasswordprovider() {
        return repopasswordprovider;
    }

    public void setOldRepoPassword(String password) {
        this.oldpassword = password;
    }

    public String getOldRepopassword() {
        return oldpassword;
    }

    public void setDefaultRole(String defaultRole) {
        this.defaultRole = defaultRole;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getType() {
        return this.type;
    }

    private String getCSVJson(boolean file, String name, String path) {
        path = path.replace("\\", "/");
        path = path.replaceAll("[/]+", "/");

        String p;
        if (!file) {
            p = "directory: '" + path + "'\n";

            return "{\n" + "version: '1.0',\n"
                    + "defaultSchema: '"
                    + name + "',\n" + "schemas: [\n"
                    + "{\n"
                    + "name: '"
                    + name + "',\n" + "type: 'custom',\n"
                    + "factory: 'org.apache.calcite.adapter.csv.CsvSchemaFactory',\n"
                    + "operand: {\n"
                    + p
                    + "}\n"
                    + "}\n"
                    + "]\n"
                    + "}";
        } else {
            p = "file: '" + path + "',";

            return "{\n" + "version: '1.0',\n"
                    + "defaultSchema: '"
                    + name + "',\n" + "schemas: [\n"
                    + "{\n"
                    + "name: '"
                    + name + "',\n" + "tables:[{\n"
                    + "name: '"
                    + name + "1',\n" + "type: 'custom',\n"
                    + "factory: 'org.apache.calcite.adapter.csv.CsvTableFactory',\n"
                    + "operand: {\n"
                    + p
                    + "flavor: 'scannable'\n"
                    + "}\n"
                    + "}]}\n"
                    + "]\n"
                    + "}";
        }
    }

    public HttpSession getSession() {
        return sessionRegistry.getSession();
    }

    public void setSessionRegistry(ScopedRepo sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public void setWorkspaces(String workspaces) {
        this.workspaces = Boolean.parseBoolean(workspaces);
    }

    public List<JujuSource> getJujuDatasources() {
        return java.util.Collections.emptyList();
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public void setDatasourceProcessor(String datasourceProcessor) {
        this.datasourceProcessor = datasourceProcessor;
    }

    public void setConnectionProcessor(String connectionProcessor) {
        this.connectionProcessor = connectionProcessor;
    }

    /**
     * Refresh the cached datasources for the CURRENT request's workspace
     * only. Other workspaces' caches are left untouched.
     *
     * <p>Used by the {@code /admin/discover/refresh} REST endpoint via
     * {@link #onApplicationEvent(HttpSessionCreatedEvent)} and any other
     * caller that wants the current tenant's view re-built from disk.
     */
    private void loadDatasources(Properties ext) {
        String workspace = currentWorkspaceKey();
        Map<String, SaikuDatasource> fresh = loadDatasourcesFor(workspace, ext);
        tenantDatasources.put(workspace, fresh);
    }

    /**
     * Workspace-scoped load helper. Resolves SDS files against
     * {@code irm.getAllDataSources()} — which (after the
     * {@code ScopedRepo} fix) reads the current request's workspace
     * — and builds the inner cache map. Returns a new map rather
     * than mutating in place so the caller controls visibility.
     */
    private Map<String, SaikuDatasource> loadDatasourcesFor(String workspace, Properties ext) {
        Map<String, SaikuDatasource> built = new ConcurrentHashMap<>();
        List<DataSource> exporteddatasources = null;

        try {
            exporteddatasources = irm.getAllDataSources();
        } catch (RepositoryException e1) {
            log.error("Could not export data sources for workspace=" + workspace, e1);
        }

        if (exporteddatasources != null) {
            for (DataSource file : exporteddatasources) {
                try {
                    if (file.getName() != null && file.getType() != null) {
                        SaikuDatasource.Type t =
                                SaikuDatasource.Type.valueOf(file.getType().toUpperCase());
                        SaikuDatasource ds =
                                new SaikuDatasource(file.getName(), t, setupDataSourceProperties(file, ext));
                        built.put(file.getName(), ds);
                    }
                } catch (Exception e) {
                    log.error("Failed to add datasource (workspace=" + workspace + ")", e);
                }
            }
        }
        return built;
    }

    private Properties setupDataSourceProperties(DataSource file, Properties ext) {
        Properties props = new Properties();

        // DataSource driver
        if (file.getDriver() != null) {
            props.put("driver", file.getDriver());
        } else if (file.getPropertyKey() != null
                && ext.containsKey("datasource." + file.getPropertyKey() + ".driver")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".driver");
            props.put("driver", p);
        }

        // DataSource location
        if (file.getPropertyKey() != null && ext.containsKey("datasource." + file.getPropertyKey() + ".location")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".location");
            if (ext.containsKey("datasource." + file.getPropertyKey() + ".schemaoverride")) {
                String[] spl = p.split(";");
                spl[1] = "Catalog=mondrian://" + file.getSchema();
                StringBuilder sb = new StringBuilder();
                for (String str : spl) {
                    sb.append(str + ";");
                }
                props.put("location", sb.toString());
            } else {
                props.put("location", p);
            }
        } else if (file.getLocation() != null) {
            props.put("location", file.getLocation());
        }

        // DataSource username
        if (file.getUsername() != null && file.getPropertyKey() == null) {
            props.put("username", file.getUsername());
        } else if (file.getPropertyKey() != null
                && ext.containsKey("datasource." + file.getPropertyKey() + ".username")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".username");
            props.put("username", p);
        }

        // DataSource password
        if (file.getPassword() != null && file.getPropertyKey() == null) {
            props.put("password", file.getPassword());
        } else if (file.getPropertyKey() != null
                && ext.containsKey("datasource." + file.getPropertyKey() + ".password")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".password");
            props.put("password", p);
        }

        // DataSource path
        if (file.getPath() != null) {
            props.put("path", file.getPath());
        } else if (file.getPropertyKey() != null && ext.containsKey("datasource." + file.getPropertyKey() + ".path")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".path");
            props.put("path", p);
        }

        // DataSource id
        if (file.getId() != null) {
            props.put("id", file.getId());
        }

        // Some security properties
        if (file.getSecurityenabled() != null) {
            props.put("security.enabled", file.getSecurityenabled());
        } else if (file.getPropertyKey() != null
                && ext.containsKey("datasource." + file.getPropertyKey() + ".security.enabled")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".security.enabled");
            props.put("security.enabled", p);
        }

        if (file.getSecuritytype() != null) {
            props.put("security.type", file.getSecuritytype());
        } else if (file.getPropertyKey() != null
                && ext.containsKey("datasource." + file.getPropertyKey() + ".security.type")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".security.type");
            props.put("security.type", p);
        }

        if (file.getSecuritymapping() != null) {
            props.put("security.mapping", file.getSecuritymapping());
        } else if (file.getPropertyKey() != null
                && ext.containsKey("datasource." + file.getPropertyKey() + ".security.mapping")) {
            String p = ext.getProperty("datasource." + file.getPropertyKey() + ".security.mapping");
            props.put("security.mapping", p);
        }

        if (file.getAdvanced() != null) {
            props.put("advanced", file.getAdvanced());
        }

        // CSV flag
        if (file.getCsv() != null) {
            props.put("csv", file.getCsv());
        }

        if (file.getEnabled() != null) {
            props.put("enabled", file.getEnabled());
        }

        if (file.getPropertyKey() != null) {
            props.put("propertykey", file.getPropertyKey());
        }

        // Ossie-only property: path to the YAML file. Read straight from the DTO — no
        // externalparameters override support today; a future slice can add
        // datasource.<key>.ossieYaml if operators need it.
        if (file.getOssieYaml() != null) {
            props.put("ossieYaml", file.getOssieYaml());
        }
        // schema captures the Ossie model name for OSSIE datasources — same semantic as
        // Mondrian's catalog. Existing load path drops it on the floor.
        if (file.getSchema() != null) {
            props.put("schema", file.getSchema());
        }

        if (datasourceProcessor != null) {
            props.put(ISaikuConnection.DATASOURCE_PROCESSORS, datasourceProcessor);
        }

        if (connectionProcessor != null) {
            props.put(ISaikuConnection.CONNECTION_PROCESSORS, connectionProcessor);
        }

        return props;
    }
}
