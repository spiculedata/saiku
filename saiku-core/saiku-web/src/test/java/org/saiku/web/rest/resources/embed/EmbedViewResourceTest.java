package org.saiku.web.rest.resources.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiSavedQueryRequest;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.security.embed.EmbedAuthFilter.EmbedGuestDetails;
import org.saiku.web.service.SessionService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Resource-level coverage of {@link EmbedViewResource} — the guest read
 * surface. Verifies that the resource correctly reads
 * {@link EmbedGuestDetails} from the SecurityContext, refuses requests
 * whose pinned kind doesn't match the endpoint, runs queries under the
 * pinned owner's scope via {@code runAs}, and stamps the defence-in-depth
 * response headers on every reply.
 */
public class EmbedViewResourceTest {

    private StubDatasourceService ds;
    private StubSessionService session;
    private StubAiQueryResource ai;
    private EmbedViewResource resource;

    @Before
    public void setUp() {
        ds = new StubDatasourceService();
        session = new StubSessionService();
        ai = new StubAiQueryResource();
        resource = new EmbedViewResource();
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setAiQueryResource(ai);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------------------- query ---------------------------- */

    @Test
    public void query_executes_saved_request_with_pinned_path() {
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.query("homes/admin/sales.saiku");

        assertEquals(200, r.getStatus());
        // executeSaved received the resource path verbatim from the
        // pinned details — the URI param was ignored as documented.
        assertNotNull(ai.lastSavedRequest);
        assertEquals("/homes/admin/sales.saiku", ai.lastSavedRequest.getPath());
        // Ran under the owner's scope.
        assertEquals("admin", session.lastRunAsUser);
        assertEquals(List.of("ROLE_ADMIN"), session.lastRunAsRoles);
        assertHardenedHeaders(r);
    }

    @Test
    public void query_with_no_guest_returns_401() {
        // No SecurityContext set up at all.
        Response r = resource.query("homes/admin/x.saiku");
        assertEquals(401, r.getStatus());
        assertHardenedHeaders(r);
    }

    @Test
    public void query_refuses_when_kind_is_dashboard() {
        // A dashboard-pinned guest must NOT be able to call the /query endpoint;
        // the auth filter normally catches this but defence-in-depth at the
        // resource boundary protects against a future filter regression.
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r = resource.query("homes/admin/exec.saikudash");
        assertEquals(401, r.getStatus());
    }

    @Test
    public void query_refuses_when_pinned_path_doesnt_end_dot_saiku() {
        // Belt-and-suspenders: even if a malformed token somehow lands in the
        // store with a wrong-suffix path, the resource refuses to execute.
        pinGuest("query", "/homes/admin/wrong.saikudash", "admin", List.of());

        Response r = resource.query("homes/admin/wrong.saikudash");
        assertEquals(401, r.getStatus());
    }

    /* -------------------------- dashboard -------------------------- */

    @Test
    public void dashboard_returns_loaded_dashboard_json() {
        ds.fileContent = "{\"id\":\"d-1\",\"name\":\"Exec\",\"version\":1}";
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.dashboard("homes/admin/exec.saikudash");
        assertEquals(200, r.getStatus());
        // Dashboard loaded as owner — both the user and the roles are the
        // pinned snapshot, not whoever sits in the unrelated session map.
        assertEquals("admin", ds.lastReadUser);
        assertEquals(List.of("ROLE_ADMIN"), ds.lastReadRoles);
        assertHardenedHeaders(r);
    }

    @Test
    public void dashboard_missing_file_is_404() {
        ds.fileContent = null;
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r = resource.dashboard("homes/admin/exec.saikudash");
        assertEquals(404, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("NOT_FOUND", body.get("status"));
    }

    @Test
    public void dashboard_refuses_when_kind_is_query() {
        pinGuest("query", "/homes/admin/q.saiku", "admin", List.of());

        Response r = resource.dashboard("homes/admin/q.saiku");
        assertEquals(401, r.getStatus());
    }

    /* --------------------------- helpers ---------------------------- */

    private void pinGuest(String kind, String path, String user, List<String> roles) {
        EmbedGuestDetails details =
                new EmbedGuestDetails("anonymous-token-id".equals(kind) ? null : "token-xyz", kind, path, user, roles);
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "embed-guest", details, List.of(new SimpleGrantedAuthority("ROLE_EMBED_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void assertHardenedHeaders(Response r) {
        assertEquals("nosniff", r.getHeaderString("X-Content-Type-Options"));
        assertEquals("no-referrer", r.getHeaderString("Referrer-Policy"));
        String cc = r.getHeaderString("Cache-Control");
        assertTrue("Cache-Control must include no-store, got: " + cc, cc != null && cc.contains("no-store"));
    }

    /* --------------------------- stubs ---------------------------- */

    /** Captures the runAs call so the test can assert the pinned identity. */
    private static class StubSessionService extends SessionService {
        String lastRunAsUser;
        List<String> lastRunAsRoles;

        @Override
        public <T> T runAs(String username, List<String> roles, Supplier<T> action) {
            lastRunAsUser = username;
            lastRunAsRoles = roles;
            return action.get();
        }
    }

    /** Captures the saved-request path and the user/roles that read the file. */
    private static class StubDatasourceService extends DatasourceService {
        String fileContent = "{}";
        String lastReadUser;
        List<String> lastReadRoles;

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            lastReadUser = username;
            lastReadRoles = roles;
            return fileContent;
        }
    }

    /** Records the request the resource handed off — verifies the path was
     *  read from pinned details, not URI params. */
    private static class StubAiQueryResource extends AiQueryResource {
        AiSavedQueryRequest lastSavedRequest;

        @Override
        public Response executeSaved(AiSavedQueryRequest body) {
            lastSavedRequest = body;
            return Response.ok(Map.of("status", "OK", "cells", List.of()))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build();
        }
    }
}
