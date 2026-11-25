/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.olap.dto.filter.SaikuFilter;
import org.saiku.service.ISessionService;
import org.saiku.service.olap.OlapQueryService;
import org.saiku.web.service.SessionService;

/** Unit tests for {@link FilterRepositoryResource}. */
public class FilterRepositoryResourceTest {

    private static final class StubOlapQueryService extends OlapQueryService {
        @Override
        public Map<String, SaikuFilter> getValidFilters(String query, Map<String, SaikuFilter> filters) {
            return new HashMap<>();
        }
    }

    private static final class StubSessionService extends SessionService implements ISessionService {
        private final Map<String, Object> objects = new HashMap<>();

        StubSessionService() {
            objects.put("username", "admin");
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return objects;
        }
    }

    private static FilterRepositoryResource resource() {
        FilterRepositoryResource resource = new FilterRepositoryResource();
        resource.setOlapQueryService(new StubOlapQueryService());
        resource.setSessionService(new StubSessionService());
        return resource;
    }

    @Test
    public void saved_filter_names_report_a_server_error() {
        // getFiltersInternal() always returns null in the current implementation,
        // so both read endpoints degrade to a 500 whose body is the root cause.
        Response response = resource().getSavedFilterNames("q1");
        assertEquals(500, response.getStatus());
    }

    @Test
    public void saved_filter_names_tolerate_a_missing_query_name() {
        Response response = resource().getSavedFilterNames(null);
        assertEquals(500, response.getStatus());
    }

    @Test
    public void saved_filters_degrade_per_lookup_mode() {
        FilterRepositoryResource resource = resource();
        // The query-scoped mode passes the (null) map straight through as the
        // entity; the other two modes dereference it and hit the error path.
        assertEquals(200, resource.getSavedFilters("q1", null).getStatus());
        assertNull(resource.getSavedFilters("q1", null).getEntity());
        assertEquals(500, resource.getSavedFilters(null, "f1").getStatus());
        // The unscoped mode also passes the null map straight through.
        assertEquals(200, resource.getSavedFilters(null, null).getStatus());
        assertNull(resource.getSavedFilters(null, null).getEntity());
    }

    @Test
    public void save_filter_rejects_unparseable_json() {
        Response response = resource().saveFilter("not-json");
        assertEquals(500, response.getStatus());
    }

    @Test
    public void save_filter_degrades_to_a_server_error() {
        String json = "{\"name\":\"My Filter\",\"uniqueName\":\"[Filter]\",\"description\":\"d\",\" hierarchy\":null}";
        Response response = resource().saveFilter(json);
        assertEquals(500, response.getStatus());
    }

    @Test
    public void save_filter_without_a_session_reports_a_server_error() {
        FilterRepositoryResource resource = new FilterRepositoryResource();
        resource.setOlapQueryService(new StubOlapQueryService());
        Response response = resource.saveFilter("{\"name\":\"f\"}");
        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().length() > 0);
    }
}
