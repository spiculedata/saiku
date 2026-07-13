package org.saiku.web.rest.resources.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSavedQueryRequest;
import org.saiku.service.olap.ai.audit.AiAuditEntry;
import org.saiku.service.olap.ai.audit.AiAuditLog;
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
    private StubAuditLog audit;
    private EmbedViewResource resource;

    @Before
    public void setUp() {
        ds = new StubDatasourceService();
        session = new StubSessionService();
        ai = new StubAiQueryResource();
        audit = new StubAuditLog();
        resource = new EmbedViewResource();
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setAiQueryResource(ai);
        resource.setAuditLog(audit);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------------------- query ---------------------------- */

    @Test
    public void query_executes_saved_request_with_pinned_path() {
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.query("homes/admin/sales.saiku", null);

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
        Response r = resource.query("homes/admin/x.saiku", null);
        assertEquals(401, r.getStatus());
        assertHardenedHeaders(r);
    }

    @Test
    public void query_refuses_when_kind_is_dashboard() {
        // A dashboard-pinned guest must NOT be able to call the /query endpoint;
        // the auth filter normally catches this but defence-in-depth at the
        // resource boundary protects against a future filter regression.
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r = resource.query("homes/admin/exec.saikudash", null);
        assertEquals(401, r.getStatus());
    }

    @Test
    public void query_refuses_when_pinned_path_doesnt_end_dot_saiku() {
        // Belt-and-suspenders: even if a malformed token somehow lands in the
        // store with a wrong-suffix path, the resource refuses to execute.
        pinGuest("query", "/homes/admin/wrong.saikudash", "admin", List.of());

        Response r = resource.query("homes/admin/wrong.saikudash", null);
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

    /* ----------------- saiku-cloud#948 force-on header ----------------- */

    @Test
    public void query_response_stamps_force_on_header_when_token_policy_is_force_on() {
        pinGuestWithPolicy(
                "query",
                "/homes/admin/sales.saiku",
                "admin",
                List.of("ROLE_ADMIN"),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.FORCE_ON);

        Response r = resource.query("homes/admin/sales.saiku", null);

        assertEquals(200, r.getStatus());
        assertEquals(
                "FORCE_ON",
                r.getHeaderString(org.saiku.web.rest.resources.embed.EmbedViewResource.REDACTION_POLICY_HEADER));
    }

    @Test
    public void query_response_omits_header_for_tenant_default_policy() {
        // Default policy → no header → gateway falls back to tenant tier
        // config. Pinned so we never leak a TENANT_DEFAULT value that
        // could confuse a gateway expecting only FORCE_ON.
        pinGuestWithPolicy(
                "query",
                "/homes/admin/clean.saiku",
                "admin",
                List.of(),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT);

        Response r = resource.query("homes/admin/clean.saiku", null);

        assertEquals(200, r.getStatus());
        assertNull(
                "TENANT_DEFAULT must NOT stamp the policy header",
                r.getHeaderString(org.saiku.web.rest.resources.embed.EmbedViewResource.REDACTION_POLICY_HEADER));
    }

    @Test
    public void dashboard_response_stamps_force_on_header() {
        // Dashboard path covered separately from query.
        ds.fileContent = "{\"id\":\"d-1\",\"name\":\"Exec\",\"version\":1}";
        pinGuestWithPolicy(
                "dashboard",
                "/homes/admin/exec.saikudash",
                "admin",
                List.of("ROLE_ADMIN"),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.FORCE_ON);

        Response r = resource.dashboard("homes/admin/exec.saikudash");

        assertEquals(200, r.getStatus());
        assertEquals(
                "FORCE_ON",
                r.getHeaderString(org.saiku.web.rest.resources.embed.EmbedViewResource.REDACTION_POLICY_HEADER));
    }

    /* ----------------- saiku#1104: forced RLS filters + audit ----------------- */

    @Test
    public void inline_tile_injects_forced_filters() {
        ds.fileContent = "{\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{}}}]}}";
        pinGuestJwt(
                "dashboard",
                "/homes/admin/exec.saikudash",
                "admin",
                List.of("ROLE_ADMIN"),
                "u_1",
                "[{\"dimension\":\"Customer\",\"hierarchy\":\"Customer\",\"level\":\"Customer\","
                        + "\"op\":\"in\",\"members\":[\"[Customer].[acme]\"]}]");

        Response r = resource.tileQuery("homes/admin/exec.saikudash", "t1", null);

        assertEquals(200, r.getStatus());
        assertNotNull("executeAi was called for the inline tile", ai.lastAiRequest);
        boolean injected = ai.lastAiRequest.getFilters().stream().anyMatch(f -> "Customer".equals(f.getDimension()));
        assertTrue("forced RLS filter must be injected into the inline query", injected);
    }

    @Test
    public void saved_query_forwards_forced_filters_to_executeSaved() {
        // saiku#1104: forced RLS filters now RIDE the saved query's forcedFilters channel — where
        // executeSaved applies them or fails closed. The embed no longer blanket-refuses; it forwards
        // the JWT claim so a QUERYMODEL saved query runs WITH the restriction.
        pinGuestJwt(
                "query",
                "/homes/admin/sales.saiku",
                "admin",
                List.of(),
                "u_1",
                "[{\"dimension\":\"Customer\",\"level\":\"Customer\",\"members\":[\"[Customer].[acme]\"]}]");

        Response r = resource.query("homes/admin/sales.saiku", null);

        assertEquals(200, r.getStatus());
        assertNotNull("executeSaved must be invoked with the forced filters", ai.lastSavedRequest);
        assertEquals(
                "the JWT forced filter must be forwarded on the forcedFilters channel",
                1,
                ai.lastSavedRequest.getForcedFilters().size());
        assertEquals("Customer", ai.lastSavedRequest.getForcedFilters().get(0).getDimension());
    }

    @Test
    public void saved_query_forced_filters_unappliable_passes_through_fail_closed() {
        // When executeSaved can't apply the RLS filter (MDX-mode / unresolvable dim) it returns
        // 403 RLS_UNAPPLIED; the embed surfaces that fail-closed status verbatim.
        ai.savedResponseOverride = Response.status(403)
                .entity(Map.of("status", "RLS_UNAPPLIED", "error", "x"))
                .build();
        pinGuestJwt(
                "query",
                "/homes/admin/sales.saiku",
                "admin",
                List.of(),
                "u_1",
                "[{\"dimension\":\"Customer\",\"level\":\"Customer\",\"members\":[\"[Customer].[acme]\"]}]");

        Response r = resource.query("homes/admin/sales.saiku", null);

        assertEquals(403, r.getStatus());
    }

    @Test
    public void saved_query_malformed_forced_filter_claim_fails_closed() {
        // A forced-filter JWT claim that isn't valid filter JSON must fail closed, never execute.
        pinGuestJwt("query", "/homes/admin/sales.saiku", "admin", List.of(), "u_1", "{not-an-array");

        Response r = resource.query("homes/admin/sales.saiku", null);

        assertEquals(403, r.getStatus());
        assertNull("a malformed RLS claim must NOT execute", ai.lastSavedRequest);
    }

    @Test
    public void reference_tile_forwards_forced_filters_to_executeSaved() {
        ds.fileContent = "{\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":"
                + "{\"kind\":\"reference\",\"path\":\"/homes/admin/q.saiku\"}}]}}";
        pinGuestJwt(
                "dashboard",
                "/homes/admin/exec.saikudash",
                "admin",
                List.of(),
                "u_1",
                "[{\"dimension\":\"Customer\",\"level\":\"Customer\",\"members\":[\"[Customer].[acme]\"]}]");

        Response r = resource.tileQuery("homes/admin/exec.saikudash", "t1", null);

        assertEquals(200, r.getStatus());
        assertNotNull("reference tile must forward forced filters to executeSaved", ai.lastSavedRequest);
        assertEquals(1, ai.lastSavedRequest.getForcedFilters().size());
    }

    @Test
    public void embed_query_audits_the_jwt_sub() {
        pinGuestJwt("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"), "u_1", null);

        resource.query("homes/admin/sales.saiku", null);

        assertEquals(1, audit.records.size());
        AiAuditEntry e = audit.records.get(0);
        assertEquals("u_1", e.sub);
        assertEquals("admin", e.user);
        assertEquals(AiAuditEntry.OUTCOME_SUCCESS, e.outcome);
        assertTrue(e.endpoint.contains("/embed/"));
    }

    /* --------------------------- helpers ---------------------------- */

    private void pinGuestJwt(
            String kind, String path, String user, List<String> roles, String sub, String forcedFiltersJson) {
        EmbedGuestDetails details = new EmbedGuestDetails(
                "jwt-token",
                kind,
                path,
                user,
                roles,
                org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT,
                sub,
                forcedFiltersJson);
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "embed-guest", details, List.of(new SimpleGrantedAuthority("ROLE_EMBED_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void pinGuest(String kind, String path, String user, List<String> roles) {
        EmbedGuestDetails details =
                new EmbedGuestDetails("anonymous-token-id".equals(kind) ? null : "token-xyz", kind, path, user, roles);
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "embed-guest", details, List.of(new SimpleGrantedAuthority("ROLE_EMBED_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** saiku-cloud#948 — pin with an explicit redaction policy. */
    private void pinGuestWithPolicy(
            String kind,
            String path,
            String user,
            List<String> roles,
            org.saiku.web.embed.EmbedToken.RedactionPolicy policy) {
        EmbedGuestDetails details = new EmbedGuestDetails("token-xyz", kind, path, user, roles, policy);
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
        String lastSavedFormat;
        AiQueryRequest lastAiRequest;
        // Override the saved-query response to simulate executeSaved's own RLS fail-closed (403).
        Response savedResponseOverride;

        @Override
        public Response executeSaved(AiSavedQueryRequest body, String format) {
            lastSavedRequest = body;
            lastSavedFormat = format;
            if (savedResponseOverride != null) {
                return savedResponseOverride;
            }
            return Response.ok(Map.of("status", "OK", "cells", List.of(), "format", format))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build();
        }

        @Override
        public Response executeAi(AiQueryRequest body, String format) {
            lastAiRequest = body;
            return Response.ok(Map.of("status", "OK", "data", List.of()))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /** Captures audit entries without touching disk (overrides record). */
    private static class StubAuditLog extends AiAuditLog {
        final List<AiAuditEntry> records = new java.util.ArrayList<>();

        StubAuditLog() {
            super(java.nio.file.Paths.get("unused-audit.jsonl"), true);
        }

        @Override
        public void record(AiAuditEntry e) {
            records.add(e);
        }
    }
}
