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
import java.util.*;
import org.saiku.repository.PathNotFoundException;
import org.saiku.repository.RepositoryException;
import org.apache.commons.lang.StringUtils;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.*;
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

    private final Map<String, SaikuDatasource> datasources =
            Collections.synchronizedMap(new HashMap<String, SaikuDatasource>());
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
        irm = FilesystemRepositoryManager.getFilesystemRepositoryManager(
                cleanse(datadir), defaultRole, sessionRegistry, workspaces);
        log.debug("2nd init datadir= " + datadir);

        // Perform the repository manager startup routines
        try {
            irm.start(userService);
            this.saveInternalFile("/etc" + separator + ".repo_version", "d20f0bea-681a-11e5-9d70-feff819cdc9f", null);
        } catch (RepositoryException e) {
            log.error("Could not start repo", e);
        }

        // Load the datasources
        loadDatasources(ext);
    }

    public void setRepositoryManager(IRepositoryManager irm) {
        this.irm = irm;
    }

    public Properties checkForExternalDataSourceProperties() {
        Properties p = new Properties();
        InputStream input;

        try {
            input = new FileInputStream(externalparameters);
            p.load(input);
        } catch (IOException e) {
            log.debug("file did not exist");
        }

        return p;
    }

    public String[] getAvailablePropertiesKeys() {
        Properties p = new Properties();
        InputStream input;

        try {
            input = new FileInputStream(externalparameters);
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

            String name = ds.getName();

            // Adding the connection before refreshing it
            SaikuDatasource sds = new SaikuDatasource(name, SaikuDatasource.Type.OLAP, datasource.getProperties());
            datasources.put(ds.getName(), sds);

            // In a workspace environment it is necessary to prefix the datasource name with the workspace name
            connectionManager.refreshConnection(name);
        } else {
            irm.saveDataSource(ds, separator + "datasources" + separator + ds.getName() + ".sds", "fixme");
        }

        String name = ds.getName();
        SaikuDatasource sds = new SaikuDatasource(name, SaikuDatasource.Type.OLAP, datasource.getProperties());

        // It stores the datasource name prefixed with the workspace name
        datasources.put(name, sds);

        return datasource;
    }

    public SaikuDatasource setDatasource(SaikuDatasource datasource) throws Exception {
        return addDatasource(datasource);
    }

    public List<SaikuDatasource> addDatasources(List<SaikuDatasource> dsources) {
        for (SaikuDatasource datasource : dsources) {
            DataSource ds = new DataSource(datasource);

            try {
                irm.saveDataSource(ds, separator + "datasources" + separator + ds.getName() + ".sds", "fixme");
                datasources.put(datasource.getName(), datasource);

            } catch (RepositoryException e) {
                log.error("Could not add data source" + datasource.getName(), e);
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
                    datasources.remove(data.getName());
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
        return datasources;
    }

    public SaikuDatasource getDatasource(String datasourceName) {
        return datasources.get(datasourceName);
    }

    @Override
    public SaikuDatasource getDatasource(String datasourceName, boolean refresh) {
        if (!refresh) {
            if (datasources.size() > 0) {
                return datasources.get(datasourceName);
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
            e.printStackTrace();
            return "Save Failed: " + e.getLocalizedMessage();
        }
    }

    public String saveBinaryInternalFile(String path, InputStream content, String type) {
        try {
            irm.saveBinaryInternalFile(content, path, type);
            return "Save Okay";
        } catch (RepositoryException e) {
            e.printStackTrace();
            return "Save Failed: " + e.getLocalizedMessage();
        }
    }

    public void removeInternalFile(String filePath) {
        try {
            irm.removeInternalFile(filePath);
        } catch (RepositoryException e) {
            log.error("Remove file failed: " + filePath);
            e.printStackTrace();
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

    private void loadDatasources(Properties ext) {
        datasources.clear();

        List<DataSource> exporteddatasources = null;

        try {
            exporteddatasources = irm.getAllDataSources();
        } catch (RepositoryException e1) {
            log.error("Could not export data sources", e1);
        }

        if (exporteddatasources != null) {
            int i = 0;
            while (i < exporteddatasources.size()) {
                DataSource file = exporteddatasources.get(i);

                try {
                    if (file.getName() != null && file.getType() != null) {

                        SaikuDatasource.Type t =
                                SaikuDatasource.Type.valueOf(file.getType().toUpperCase());
                        SaikuDatasource ds =
                                new SaikuDatasource(file.getName(), t, setupDataSourceProperties(file, ext));
                        datasources.put(file.getName(), ds);
                    }
                } catch (Exception e) {
                    // throw new SaikuServiceException("Failed to add datasource", e);
                    log.error("Failed to add datasource", e);
                }
                i++;
            }
        }
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

        if (datasourceProcessor != null) {
            props.put(ISaikuConnection.DATASOURCE_PROCESSORS, datasourceProcessor);
        }

        if (connectionProcessor != null) {
            props.put(ISaikuConnection.CONNECTION_PROCESSORS, connectionProcessor);
        }

        return props;
    }
}
