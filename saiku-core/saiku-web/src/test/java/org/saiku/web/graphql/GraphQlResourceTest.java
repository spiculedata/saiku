/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.graphql;

import static org.junit.Assert.assertEquals;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.Test;

/** Unit tests for {@link GraphQlResource} endpoint plumbing. */
public class GraphQlResourceTest {

    private static final class StubGraphQlService extends SaikuGraphQlService {
        String lastQuery;
        int refreshes;

        @Override
        public Map<String, Object> execute(
                String query, Map<String, Object> variables, String operationName, Map<String, Object> extensions) {
            lastQuery = query;
            return Map.of("data", Map.of("ok", true));
        }

        @Override
        public synchronized void refresh() {
            refreshes++;
        }

        @Override
        public String getSdl() {
            return "type Query { ok: Boolean }";
        }
    }

    private static GraphQlResource wired(StubGraphQlService service) {
        GraphQlResource resource = new GraphQlResource();
        resource.setGraphQlService(service);
        return resource;
    }

    @Test
    public void post_executes_the_query_with_variables_and_operation_name() {
        StubGraphQlService service = new StubGraphQlService();
        GraphQlResource resource = wired(service);

        Response response = resource.execute(Map.of(
                "query",
                "{ ok }",
                "operationName",
                "op",
                "variables",
                Map.of("a", 1),
                "extensions",
                Map.of("persistedQueryHash", "h")));

        assertEquals(200, response.getStatus());
        assertEquals("{ ok }", service.lastQuery);
    }

    @Test
    public void post_without_a_service_reports_unavailable() {
        Response response = new GraphQlResource().execute(Map.of("query", "{ ok }"));
        assertEquals(503, response.getStatus());
    }

    @Test
    public void get_parses_variables_and_extensions_json() {
        StubGraphQlService service = new StubGraphQlService();
        GraphQlResource resource = wired(service);

        Response response = resource.executeGet("{ ok }", "{\"a\":2}", "op", "{\"h\":\"x\"}");

        assertEquals(200, response.getStatus());
        assertEquals("{ ok }", service.lastQuery);
    }

    @Test
    public void get_rejects_malformed_variables_json() {
        GraphQlResource resource = wired(new StubGraphQlService());

        Response response = resource.executeGet("{ ok }", "not-json", null, null);

        assertEquals(400, response.getStatus());
    }

    @Test
    public void get_rejects_malformed_extensions_json() {
        GraphQlResource resource = wired(new StubGraphQlService());

        Response response = resource.executeGet("{ ok }", null, null, "[1,2");

        assertEquals(400, response.getStatus());
    }

    @Test
    public void get_tolerates_blank_variables_and_extensions() {
        StubGraphQlService service = new StubGraphQlService();
        GraphQlResource resource = wired(service);

        Response response = resource.executeGet("{ ok }", "  ", null, " ");

        assertEquals(200, response.getStatus());
    }

    @Test
    public void get_without_a_service_reports_unavailable() {
        Response response = new GraphQlResource().executeGet("{ ok }", null, null, null);
        assertEquals(503, response.getStatus());
    }

    @Test
    public void refresh_requires_the_service() {
        Response unavailable = new GraphQlResource().refresh();
        assertEquals(503, unavailable.getStatus());

        StubGraphQlService service = new StubGraphQlService();
        Response ok = wired(service).refresh();
        assertEquals(200, ok.getStatus());
        assertEquals(1, service.refreshes);
    }

    @Test
    public void schema_serves_the_sdl() {
        StubGraphQlService service = new StubGraphQlService();
        Response response = wired(service).schema();

        assertEquals(200, response.getStatus());
        assertEquals("type Query { ok: Boolean }", response.getEntity());
    }

    @Test
    public void schema_without_a_service_reports_unavailable() {
        Response response = new GraphQlResource().schema();
        assertEquals(503, response.getStatus());
    }

    @Test
    public void a_custom_mapper_is_accepted() {
        GraphQlResource resource = wired(new StubGraphQlService());
        resource.setMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(200, resource.executeGet("{ ok }", null, null, null).getStatus());
    }
}
