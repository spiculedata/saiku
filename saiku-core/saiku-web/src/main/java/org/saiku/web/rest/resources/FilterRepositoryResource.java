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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qmino.miredot.annotations.ReturnType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.saiku.olap.dto.filter.SaikuFilter;
import org.saiku.service.ISessionService;
import org.saiku.service.olap.OlapQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@RestController
@RequestMapping("/saiku/{username}/filters")
@XmlAccessorType(XmlAccessType.NONE)
public class FilterRepositoryResource {

    private static final Logger log = LoggerFactory.getLogger(FilterRepositoryResource.class);

    private static final String SETTINGS_FILE = "settings.properties";
    private static final String FILTER_FILENAME = "saiku.filters";

    private OlapQueryService olapQueryService;
    private ISessionService sessionService;

    // @Autowired
    public void setOlapQueryService(OlapQueryService olapqs) {
        olapQueryService = olapqs;
    }

    // @Autowired
    public void setSessionService(ISessionService ss) {
        sessionService = ss;
    }

    private Map<String, SaikuFilter> getFiltersInternal() throws Exception {
        return getFiltersInternal(null);
    }

    private Map<String, SaikuFilter> getFiltersInternal(String query) {
        Map<String, SaikuFilter> allFilters = new HashMap<>();
        if (StringUtils.isNotBlank(query)) {
            allFilters = olapQueryService.getValidFilters(query, allFilters);
        }
        return null;
    }

    /**
     * Get filternames as JSON.
     */
    @GetMapping(path = "/names/", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.lang.List<String>")
    public ResponseEntity<?> getSavedFilterNames(@RequestParam(name = "queryname", required = false) String queryName) {
        try {
            Map<String, SaikuFilter> allFilters = getFiltersInternal(queryName);
            List<String> filternames = new ArrayList<>(allFilters.keySet());
            Collections.sort(filternames);
            return ResponseEntity.ok(filternames);

        } catch (Exception e) {
            log.error("Cannot filter names", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get Saved Filters as JSON.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getSavedFilters(
            @RequestParam(name = "query", required = false) String queryName,
            @RequestParam(name = "filtername", required = false) String filterName) {
        try {
            Map<String, SaikuFilter> allFilters = new HashMap<>();
            if (StringUtils.isNotBlank(queryName)) {
                allFilters = getFiltersInternal(queryName);
            } else if (StringUtils.isNotBlank(filterName)) {
                allFilters = getFiltersInternal();
                Map<String, SaikuFilter> singleFilter = new HashMap<>();
                if (allFilters.containsKey(filterName)) {
                    singleFilter.put(filterName, allFilters.get(filterName));
                    allFilters = singleFilter;
                }
            } else {
                allFilters = getFiltersInternal();
            }
            return ResponseEntity.ok(allFilters);
        } catch (Exception e) {
            log.error("Cannot get filter details", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Save filter
     */
    @PostMapping(path = "/{filtername}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("org.saiku.olap.dto.filter.SaikuFilter")
    public ResponseEntity<?> saveFilter(@RequestParam("filter") String filterJSON) {
        try {

            ObjectMapper mapper = new ObjectMapper();
            mapper.setVisibilityChecker(
                    mapper.getVisibilityChecker().withFieldVisibility(JsonAutoDetect.Visibility.ANY));
            SaikuFilter filter = mapper.readValue(filterJSON, SaikuFilter.class);
            String username =
                    sessionService.getAllSessionObjects().get("username").toString();
            filter.setOwner(username);
            Map<String, SaikuFilter> filters = getFiltersInternal();
            filters.put(filter.getName(), filter);
            return ResponseEntity.ok(filter);
        } catch (Exception e) {
            log.error("Cannot save filter (" + filterJSON + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
