/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources.share;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.web.rest.resources.dashboards.Dashboard;
import org.saiku.web.rest.resources.dashboards.DashboardTile;
import org.saiku.web.rest.resources.dashboards.TileQuery;
import org.saiku.web.security.share.ShareTokenAuthFilter.ShareGuestDetails;
import org.saiku.web.service.SessionService;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;

/** Unit tests for {@link ShareViewResource}. */
public class ShareViewResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShareViewResource resource;
    private StubDatasources datasources;
    private StubAiQuery aiQuery;
    private ShareTokenStore tokenStore;
    private Dashboard dash;

    @Before
    public void setUp() {
        resource = new ShareViewResource();
        datasources = new StubDatasources();
        aiQuery = new StubAiQuery();
        resource.setDatasourceService(datasources);
        resource.setSessionService(new StubSessionService());
        resource.setAiQueryResource(aiQuery);
        tokenStore = new ShareTokenStore(null);
        resource.setShareTokenStore(tokenStore);
        dash = new Dashboard();
        dash.id = "d1";
        dash.name = "Board";
    }

    @After
    public void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static final class StubDatasources extends DatasourceService {
        String fileData;

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            if ("gone.saikudash".equals(path)) {
                throw new IllegalStateException("deleted");
            }
            return fileData;
        }
    }

    private static final class StubSessionService extends SessionService {
        @Override
        public <T> T runAs(String username, List<String> roles, java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    private static final class StubAiQuery extends org.saiku.web.rest.resources.AiQueryResource {
        String lastFormat;

        @Override
        public Response executeAi(AiQueryRequest req, String format) {
            lastFormat = format;
            return Response.ok(Map.of("status", "OK")).build();
        }

        @Override
        public Response executeSaved(org.saiku.service.olap.ai.AiSavedQueryRequest req, String format) {
            lastFormat = format;
            return Response.ok(Map.of("status", "SAVED")).build();
        }
    }

    private void asGuest(String dashboardPath) {
        ShareToken token = tokenStore.create(dashboardPath, "owner", List.of("ROLE_USER"), 3600_000L, null);
        org.springframework.security.authentication.TestingAuthenticationToken auth =
                new org.springframework.security.authentication.TestingAuthenticationToken("guest", token.token);
        auth.setDetails(new ShareGuestDetails(token.token, dashboardPath, "owner", List.of("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
    }

    private void storeDashboard() throws Exception {
        datasources.fileData = MAPPER.writeValueAsString(dash);
    }

    @Test
    public void without_a_guest_everything_is_invalid() {
        assertEquals(401, resource.dashboard().getStatus());
        assertEquals(401, resource.tileQuery("t1").getStatus());
    }

    @Test
    public void dashboard_serves_the_pinned_layout() throws Exception {
        asGuest("d1.saikudash");
        storeDashboard();
        Response response = resource.dashboard();
        assertEquals(200, response.getStatus());
        assertEquals("nosniff", response.getMetadata().getFirst("X-Content-Type-Options"));
    }

    @Test
    public void dashboard_404s_when_the_file_is_gone() {
        asGuest("gone.saikudash");
        assertEquals(404, resource.dashboard().getStatus());
    }

    @Test
    public void dashboard_404s_when_the_path_is_not_a_dashboard() {
        asGuest("other.saikunb");
        assertEquals(404, resource.dashboard().getStatus());
    }

    @Test
    public void dashboard_404s_when_the_file_is_unparseable() {
        asGuest("d1.saikudash");
        datasources.fileData = "not json";
        assertEquals(404, resource.dashboard().getStatus());
    }

    @Test
    public void tile_query_runs_the_inline_query_as_the_owner() throws Exception {
        DashboardTile tile = new DashboardTile();
        tile.id = "t1";
        TileQuery q = new TileQuery();
        q.kind = "inline";
        q.body = new AiQueryRequest();
        tile.query = q;
        dash.layout.tiles = List.of(tile);
        storeDashboard();
        asGuest("d1.saikudash");

        Response response = resource.tileQuery("t1");

        assertEquals(200, response.getStatus());
        assertEquals("records", aiQuery.lastFormat);
    }

    @Test
    public void tile_query_runs_a_referenced_saved_query() throws Exception {
        DashboardTile tile = new DashboardTile();
        tile.id = "t2";
        TileQuery q = new TileQuery();
        q.kind = "reference";
        q.path = "queries/saved.saiku";
        tile.query = q;
        dash.layout.tiles = List.of(tile);
        storeDashboard();
        asGuest("d1.saikudash");

        Response response = resource.tileQuery("t2");

        assertEquals(200, response.getStatus());
        assertEquals("SAVED", ((Map<?, ?>) response.getEntity()).get("status"));
    }

    @Test
    public void tile_query_rejects_an_unknown_tile() throws Exception {
        storeDashboard();
        asGuest("d1.saikudash");
        assertEquals(404, resource.tileQuery("missing").getStatus());
    }

    @Test
    public void tile_query_404s_for_a_tile_without_a_query() throws Exception {
        DashboardTile tile = new DashboardTile();
        tile.id = "t3";
        dash.layout.tiles = List.of(tile);
        storeDashboard();
        asGuest("d1.saikudash");

        Response response = resource.tileQuery("t3");

        assertEquals(404, response.getStatus());
    }

    @Test
    public void tile_query_404s_for_an_unknown_tile_on_an_empty_dashboard() throws Exception {
        // "{}" deserialises to a Dashboard whose layout/tiles defaults are
        // non-null but empty, so the tile lookup misses and the endpoint 404s.
        datasources.fileData = "{}";
        asGuest("d1.saikudash");
        assertEquals(404, resource.tileQuery("t1").getStatus());
    }
}
