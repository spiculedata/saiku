/*
 *   Copyright 2012 OSBI Ltd
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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.DataSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data Source Manipulation Utility Endpoints
 */
@Path("/saiku/{username}/org.saiku.datasources")
public class DataSourceResource {

    private static final Logger log = LoggerFactory.getLogger(DataSourceResource.class);
    private DatasourceService datasourceService;
    private UserService userService;

    public void setDatasourceService(DatasourceService ds) {
        datasourceService = ds;
    }

    /**
     * Get Data Sources available on the server.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Collection<SaikuDatasource> getDatasources() {
        try {
            return datasourceService
                    .getDatasources(userService.getCurrentUserRoles())
                    .values();
        } catch (SaikuServiceException e) {
            log.error(this.getClass().getName(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Delete available data source from the server.
     */
    @DeleteMapping("/{datasource}")
    public HttpStatus deleteDatasource(@PathVariable("datasource") String datasourceName) {
        datasourceService.removeDatasource(datasourceName);
        return HttpStatus.GONE;
    }

    /**
     * Get a specific data source from the server by ID.
     */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public ResponseEntity<?> getDatasourceById(@PathVariable("id") String id) {
        try {
            SaikuDatasource saikuDatasource = null;
            Map<String, SaikuDatasource> datasources =
                    datasourceService.getDatasources(userService.getCurrentUserRoles());
            for (SaikuDatasource currentDatasource : datasources.values()) {
                if (currentDatasource.getProperties().getProperty("id").equals(id)) {
                    saikuDatasource = currentDatasource;
                    break;
                }
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DataSourceMapper(saikuDatasource));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    @PutMapping(
            path = "/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public ResponseEntity<?> updateDatasourceLocale(@RequestBody String locale, @PathVariable("id") String id) {
        boolean overwrite = true;
        try {
            SaikuDatasource saikuDatasource = null;
            Map<String, SaikuDatasource> datasources =
                    datasourceService.getDatasources(userService.getCurrentUserRoles());
            for (SaikuDatasource currentDatasource : datasources.values()) {
                if (currentDatasource.getProperties().getProperty("id").equals(id)) {
                    saikuDatasource = currentDatasource;
                    changeLocale(saikuDatasource, locale);
                    datasourceService.addDatasource(saikuDatasource, overwrite, userService.getCurrentUserRoles());
                    break;
                }
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(saikuDatasource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getLocalizedMessage());
        }
    }

    private void changeLocale(SaikuDatasource saikuDatasource, String newLocale) {
        String location = saikuDatasource.getProperties().getProperty("location");
        String oldLocale = getOldLocale(location);
        String newLocation = location.replace(oldLocale, newLocale);
        saikuDatasource.getProperties().setProperty("location", newLocation);
    }

    private String getOldLocale(String location) {
        String referenceText = "locale=";
        int start = location.toLowerCase().indexOf(referenceText);
        if (start == -1) {
            return "no locale!";
        } else {
            start += referenceText.length();
            int end = location.indexOf(";", start);
            return location.substring(start, end);
        }
    }

    public UserService getUserService() {
        return userService;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }
}
