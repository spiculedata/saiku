/*
 *   Copyright 2015 OSBI Ltd
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
package org.saiku.web.rest.resources;

import com.qmino.miredot.annotations.ReturnType;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.database.dto.SaikuUser;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.log.LogExtractor;
import org.saiku.repository.RepositoryException;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.importer.JujuSource;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuDataSourceException;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.DataSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AdminResource for the Saiku 3.0+ Admin console
 */
@Path("/saiku/admin")
public class AdminResource {

    private DatasourceService datasourceService;

    private UserService userService;
    private static final Logger log = LoggerFactory.getLogger(DataSourceResource.class);
    private OlapDiscoverService olapDiscoverService;
    private LogExtractor logExtractor;

    public LogExtractor getLogExtractor() {
        return logExtractor;
    }

    public void setLogExtractor(LogExtractor logExtractor) {
        this.logExtractor = logExtractor;
    }

    public void setOlapDiscoverService(OlapDiscoverService olapDiscoverService) {
        this.olapDiscoverService = olapDiscoverService;
    }

    public void setDatasourceService(DatasourceService ds) {
        datasourceService = ds;
    }

    public void setUserService(UserService us) {
        userService = us;
    }

    private IDatasourceManager repositoryDatasourceManager;

    public IDatasourceManager getRepositoryDatasourceManager() {
        return repositoryDatasourceManager;
    }

    public void setRepositoryDatasourceManager(IDatasourceManager repositoryDatasourceManager) {
        this.repositoryDatasourceManager = repositoryDatasourceManager;
    }

    @GetMapping(path = "/datasources", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.lang.List<SaikuDatasource>")
    public ResponseEntity<?> getAvailableDataSources() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<DataSourceMapper> l = new ArrayList<>();

        try {
            for (SaikuDatasource d : datasourceService
                    .getDatasources(userService.getCurrentUserRoles())
                    .values()) {
                l.add(new DataSourceMapper(d));
            }
            return ResponseEntity.ok(l);
        } catch (SaikuServiceException e) {
            log.error(this.getClass().getName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @PutMapping(
            path = "/datasources/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public ResponseEntity<?> updateDatasource(@RequestBody DataSourceMapper json, @PathVariable("id") String id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            datasourceService.addDatasource(json.toSaikuDataSource(), true, userService.getCurrentUserRoles());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @GetMapping(path = "/datasources/{id}/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.List<SaikuConnection>")
    public ResponseEntity<?> refreshDatasource(@PathVariable("id") String id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            olapDiscoverService.refreshConnection(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(olapDiscoverService.getConnection(id));
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @PostMapping(
            path = "/datasources",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public ResponseEntity<?> createDatasource(@RequestBody DataSourceMapper json) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            datasourceService.addDatasource(json.toSaikuDataSource(), false, userService.getCurrentUserRoles());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        } catch (Exception e) {
            log.error("Error adding data source", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @DeleteMapping("/datasources/{id}")
    public ResponseEntity<?> deleteDatasource(@PathVariable("id") String id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        datasourceService.removeDatasource(id);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(datasourceService.getDatasources(userService.getCurrentUserRoles()));
    }

    @GetMapping(path = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.List<MondrianSchema>")
    public ResponseEntity<?> getAvailableSchema() {

        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(datasourceService.getAvailableSchema());
    }

    @PutMapping(
            path = "/schema/{id}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.List<MondrianSchema>")
    public ResponseEntity<?> uploadSchemaPut(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "name") String name,
            @PathVariable("id") String id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String path = "/datasources/" + name + ".xml";
        String schema;
        try {
            schema = getStringFromInputStream(file.getInputStream());
        } catch (IOException ioe) {
            log.error("Error reading uploaded schema: " + name, ioe);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ioe.getLocalizedMessage());
        }
        try {
            datasourceService.addSchema(schema, path, name);
            return ResponseEntity.ok(datasourceService.getAvailableSchema());
        } catch (Exception e) {
            log.error("Error uploading schema: " + name, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @PostMapping(
            path = "/schema/{id}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.List<MondrianSchema>")
    public ResponseEntity<?> uploadSchema(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "name") String name,
            @PathVariable("id") String id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String path = "/datasources/" + name + ".xml";
        String schema;
        try {
            schema = getStringFromInputStream(file.getInputStream());
        } catch (IOException ioe) {
            log.error("Error reading uploaded schema: " + name, ioe);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ioe.getLocalizedMessage());
        }
        try {
            datasourceService.addSchema(schema, path, name);
            return ResponseEntity.ok(datasourceService.getAvailableSchema());
        } catch (Exception e) {
            log.error("Error uploading schema: " + name, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @PutMapping(
            path = "/datasources/{datasourceName}/locale",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public ResponseEntity<?> updateDatasourceLocale(
            @RequestBody String locale, @PathVariable("datasourceName") String datasourceName) {
        try {
            boolean overwrite = true;
            SaikuDatasource saikuDatasource = datasourceService.getDatasource(datasourceName);
            datasourceService.setLocaleOfDataSource(saikuDatasource, locale);
            datasourceService.addDatasource(saikuDatasource, overwrite, userService.getCurrentUserRoles());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DataSourceMapper(saikuDatasource));
        } catch (SaikuDataSourceException e) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(e.getLocalizedMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @GetMapping(path = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.List<SaikuUser>")
    public ResponseEntity<?> getExistingUsers() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(userService.getUsers());
    }

    @DeleteMapping("/schema/{id}")
    @ReturnType("java.util.List<MondrianSchema>")
    public ResponseEntity<?> deleteSchema(@PathVariable("id") String id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        datasourceService.removeSchema(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(datasourceService.getAvailableSchema());
    }

    @GetMapping(path = "/schema/{id}", produces = MediaType.APPLICATION_XML_VALUE)
    @ReturnType("MondrianSchema")
    public ResponseEntity<?> getSavedSchema(@PathVariable("id") String id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String p = "";
        for (MondrianSchema s : datasourceService.getAvailableSchema()) {
            if (s.getName().equals(id)) {

                try {
                    p = repositoryDatasourceManager.getInternalFileData(s.getPath());
                } catch (RepositoryException e) {
                    // swallow; same semantics as previous code which built an unused response
                }
                break;
            }
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("content-disposition", "attachment; filename = " + id)
                .body(p.getBytes());
    }

    @GetMapping("/datasource/import")
    public ResponseEntity<?> importLegacyDatasources() {

        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        datasourceService.importLegacyDatasources();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/schema/import")
    public ResponseEntity<?> importLegacySchema() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        datasourceService.importLegacySchema();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/users/import")
    public ResponseEntity<?> importLegacyUsers() {

        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        datasourceService.importLegacyUsers();
        return ResponseEntity.ok().build();
    }

    @GetMapping(path = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.database.dto.SaikuUser")
    public ResponseEntity<?> getUserDetails(@PathVariable("id") int id) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(userService.getUser(id));
    }

    @PutMapping(
            path = "/users/{username}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.database.dto.SaikuUser")
    public ResponseEntity<?> updateUserDetails(
            @RequestBody SaikuUser jsonString, @PathVariable("username") String userName) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            if (jsonString.getPassword() == null || jsonString.getPassword().equals("")) {
                return Response.ok()
                        .entity(userService.updateUser(jsonString, false))
                        .build();
            } else {
                return Response.ok()
                        .entity(userService.updateUser(jsonString, true))
                        .build();
            }
        } catch (IllegalArgumentException policy) {
            // Password-policy violation — surface as 400 so the UI can display.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(policy.getMessage())
                    .build();
        }
    }

    @PostMapping(
            path = "/users",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.database.dto.SaikuUser")
    public ResponseEntity<?> createUserDetails(@RequestBody SaikuUser jsonString) {

        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            return Response.ok().entity(userService.addUser(jsonString)).build();
        } catch (IllegalArgumentException policy) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(policy.getMessage())
                    .build();
        }
    }

    @DeleteMapping(path = "/users/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> removeUser(@PathVariable("username") String username) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        userService.removeUser(username);
        return ResponseEntity.ok().build();
    }

    private static String getStringFromInputStream(InputStream is) {

        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();

        String line;
        try {

            br = new BufferedReader(new InputStreamReader(is));
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

        } catch (IOException e) {
            log.error("IO Exception when reading from input stream", e);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    log.error("IO Exception closing input stream", e);
                }
            }
        }

        return sb.toString();
    }

    @GetMapping(path = "/version", produces = MediaType.TEXT_PLAIN_VALUE)
    @ReturnType("java.lang.String")
    public ResponseEntity<?> getVersion() {
        Properties prop = new Properties();
        String version = "";
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        InputStream is = classloader.getResourceAsStream("org/saiku/web/rest/resources/version.properties");
        try {
            prop.load(is);
            version = prop.getProperty("VERSION");
        } catch (IOException ex) {
            log.error("IO Exception when reading input stream", ex);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(version);
    }

    @GetMapping(path = "/backup", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> getBackup() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        StreamingResponseBody body = new StreamingResponseBody() {
            @Override
            public void writeTo(OutputStream output) throws IOException {
                BufferedOutputStream bus = new BufferedOutputStream(output);
                bus.write(datasourceService.exportRepository());
                bus.flush();
            }
        };
        return ResponseEntity.ok().body(body);
    }

    @PostMapping(
            path = "/restore",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> postRestore(@RequestParam("file") MultipartFile file) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            byte[] bytes = IOUtils.toByteArray(file.getInputStream());
            datasourceService.restoreRepository(bytes);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("Restore Ok");
        } catch (IOException e) {
            log.error("Error reading restore file", e);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Restore Ok");
    }

    @PostMapping(
            path = "/legacyfiles",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> postRestoreFiles(@RequestParam("file") MultipartFile file) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            byte[] bytes = IOUtils.toByteArray(file.getInputStream());
            datasourceService.restoreLegacyFiles(bytes);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("Restore Ok");
        } catch (IOException e) {
            log.error("Error reading restore file", e);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Restore Ok");
    }

    @GetMapping(path = "/log/{logname}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> getLogFile(@PathVariable("logname") String logname) {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            return ResponseEntity.ok(logExtractor.readLog(logname));
        } catch (IOException e) {
            log.error("Could not read log file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Could not read log file");
        }
    }

    @GetMapping(path = "/datakeys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPropertiesKeys() {
        return ResponseEntity.ok(repositoryDatasourceManager.getAvailablePropertiesKeys());
    }

    @GetMapping(path = "/attacheddatasources", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getDataSources() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<JujuSource> list = repositoryDatasourceManager.getJujuDatasources();

        return ResponseEntity.ok(list);
    }
}
