/*
 * Copyright 2012 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.saiku.service.datasource;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.AclEntry;
import org.saiku.repository.IRepositoryObject;
import org.saiku.service.util.exception.SaikuDataSourceException;
import org.saiku.service.util.exception.SaikuDataSourceNotFoundException;

public class DatasourceService implements Serializable {

    private static final long serialVersionUID = -4407446633148181669L;

    private transient IDatasourceManager datasources;

    private IConnectionManager connectionManager;

    public void setConnectionManager(IConnectionManager ic) {
        connectionManager = ic;
        datasources = ic.getDataSourceManager();
    }

    public IConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public void addDatasource(SaikuDatasource datasource, boolean overwrite, String[] roles) throws Exception {
        SaikuDatasource ds = getDatasources(roles).get(datasource.getName());

        if (ds == null) {
            datasources.addDatasource(datasource);
        } else {
            if (overwrite) {
                datasources.removeDatasource(ds.getProperties().getProperty("id"));
                datasources.addDatasource(datasource);
            } else {
                throw new Exception("Datasource Name Already Exists!");
            }
        }
    }

    public SaikuDatasource fetchDataSourceById(String id, String[] roles) throws SaikuDataSourceNotFoundException {
        SaikuDatasource saikuDatasource = null;
        Map<String, SaikuDatasource> datasources = this.getDatasources(roles);
        for (SaikuDatasource currentDatasource : datasources.values()) {
            if (currentDatasource.getProperties().getProperty("id").equals(id)) {
                saikuDatasource = currentDatasource;
                break;
            }
        }
        if (saikuDatasource == null) {
            throw new SaikuDataSourceException("Could not find data source with id: " + id);
        }
        return saikuDatasource;
    }

    public void setLocaleOfDataSource(SaikuDatasource dataSource, String newLocale) throws SaikuDataSourceException {
        String location = dataSource.getProperties().getProperty("location");
        String oldLocale = getOldLocale(location);
        String newLocation = location.replace(oldLocale, newLocale);
        dataSource.getProperties().setProperty("location", newLocation);
    }

    private String getOldLocale(String location) throws SaikuDataSourceException {
        String referenceText = "locale=";
        int start = location.toLowerCase().indexOf(referenceText);
        if (start == -1) {
            throw new SaikuDataSourceException(
                    "Could not find old locale because there is currently no locale defined in the data source");
        } else {
            start += referenceText.length();
            int end = location.indexOf(";", start);
            return location.substring(start, end);
        }
    }

    public void setDatasource(SaikuDatasource datasource) throws Exception {
        datasources.setDatasource(datasource);
    }

    public void removeDatasource(String datasourceId) {
        datasources.removeDatasource(datasourceId);
    }

    public void removeSchema(String schemaId) {
        datasources.removeSchema(schemaId);
    }

    public SaikuDatasource getDatasource(String datasourceName) {
        return datasources.getDatasource(datasourceName);
    }

    public Map<String, SaikuDatasource> getDatasources(String[] roles) {
        return datasources.getDatasources(roles);
    }

    public List<MondrianSchema> getAvailableSchema() {
        return datasources.getMondrianSchema();
    }

    public void addSchema(String schema, String path, String name) throws Exception {
        datasources.addSchema(schema, path, name);
    }

    /**
     * Persist an arbitrary internal file (UTF-8 string content) at {@code path}. Used by the
     * schema-generation save path to write the {@code <schemaName>.generated.json} sidecar
     * alongside the XML. {@code type} is a JCR-style node type hint (e.g. {@code nt:saikufiles});
     * the filesystem implementation ignores it.
     *
     * @return underlying repo status string (non-null), mirroring the manager's contract
     */
    public String saveInternalFile(String path, String content, String type) {
        return datasources.saveInternalFile(path, content, type);
    }

    /**
     * Read an internal file's contents. Returns {@code null} if the file does not exist (the
     * repository layer propagates RepositoryException for genuine I/O errors).
     *
     * <p>Used by the schema-generation re-run path to load a previously-persisted sidecar JSON.
     */
    public String getInternalFileData(String path) {
        try {
            return datasources.getInternalFileData(path);
        } catch (org.saiku.repository.RepositoryException e) {
            // Treat repository errors as "not present" for the caller's convenience; true I/O
            // faults are logged at the manager layer.
            return null;
        }
    }

    /**
     * Delete an internal file (no ACL gate — internal files are server-managed).
     * Best-effort: a missing file is a no-op. Used to purge a dashboard's
     * sidecar comment / history JSONL when the dashboard itself is deleted.
     */
    public void removeInternalFile(String path) {
        datasources.removeInternalFile(path);
    }

    public String saveFile(String content, String path, String name, List<String> roles) {
        return datasources.saveFile(path, content, name, roles);
    }

    public String removeFile(String path, String name, List<String> roles) {
        return datasources.removeFile(path, name, roles);
    }

    public String moveFile(String source, String target, String name, List<String> roles) {
        return datasources.moveFile(source, target, name, roles);
    }

    public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
        return datasources.getFiles(type, username, roles);
    }

    public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles, String path) {
        return datasources.getFiles(type, username, roles, path);
    }

    public String getFileData(String path, String username, List<String> roles) {
        return datasources.getFileData(path, username, roles);
    }

    public void importLegacySchema() {}

    public void importLegacyDatasources() {}

    public void importLegacyUsers() {}

    public void setResourceACL(String file, String acl, String username, List<String> roles) {
        datasources.setACL(file, acl, username, roles);
    }

    public AclEntry getResourceACL(String file, String username, List<String> roles) {
        return datasources.getACL(file, username, roles);
    }

    public byte[] exportRepository() {
        return datasources.exportRepository();
    }

    public void restoreRepository(byte[] data) {
        datasources.restoreRepository(data);
    }

    public void restoreLegacyFiles(byte[] data) {
        datasources.restoreLegacyFiles(data);
    }

    public boolean hasHomeDirectory(String name) {
        return datasources.hasHomeDirectory(name);
    }

    public void createUserHome(String user) {
        datasources.createUser(user);
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        stream.defaultReadObject();
        datasources = connectionManager.getDataSourceManager();
    }
}
