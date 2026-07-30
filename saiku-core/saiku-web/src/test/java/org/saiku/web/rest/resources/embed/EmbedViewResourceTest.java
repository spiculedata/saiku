package org.saiku.web.rest.resources.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiFilterSelection;
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

    /* ------------------- saiku#1441: app embed (RLS/PII) ------------------- */

    // Minimal .saikuapp docs: one page, one tile. The tile shape is identical to
    // a dashboard tile, so the SAME guarded runTileQuery executes it.
    private static final String APP_INLINE_TILE = "{\"id\":\"a-1\",\"name\":\"Portal\",\"pages\":[{\"id\":\"page-1\","
            + "\"grid\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{}}}]}}]}";
    private static final String APP_REFERENCE_TILE =
            "{\"id\":\"a-1\",\"name\":\"Portal\",\"pages\":[{\"id\":\"page-1\",\"grid\":{\"tiles\":[{\"id\":\"t1\","
                    + "\"query\":{\"kind\":\"reference\",\"path\":\"/homes/admin/q.saiku\"}}]}}]}";

    @Test
    public void app_returns_loaded_app_json_owner_scoped() {
        ds.fileContent = APP_INLINE_TILE;
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.app("homes/admin/portal.saikuapp");

        assertEquals(200, r.getStatus());
        // App loaded as the pinned owner — never whoever sits in the session map.
        assertEquals("admin", ds.lastReadUser);
        assertEquals(List.of("ROLE_ADMIN"), ds.lastReadRoles);
        assertHardenedHeaders(r);
    }

    @Test
    public void app_refuses_when_kind_is_dashboard() {
        // A dashboard-pinned token must NOT reach the /app endpoint (defence in
        // depth behind the auth filter's per-kind pin).
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r = resource.app("homes/admin/exec.saikudash");
        assertEquals(401, r.getStatus());
    }

    @Test
    public void app_missing_file_is_404() {
        ds.fileContent = null;
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of());

        Response r = resource.app("homes/admin/portal.saikuapp");
        assertEquals(404, r.getStatus());
    }

    @Test
    public void app_response_stamps_force_on_header_when_token_policy_is_force_on() {
        // saiku-cloud#948 / #940: a PII-elevated app token still emits the
        // gateway redaction header on the app-doc read.
        ds.fileContent = APP_INLINE_TILE;
        pinGuestWithPolicy(
                "app",
                "/homes/admin/portal.saikuapp",
                "admin",
                List.of("ROLE_ADMIN"),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.FORCE_ON);

        Response r = resource.app("homes/admin/portal.saikuapp");

        assertEquals(200, r.getStatus());
        assertEquals(
                "FORCE_ON",
                r.getHeaderString(org.saiku.web.rest.resources.embed.EmbedViewResource.REDACTION_POLICY_HEADER));
    }

    @Test
    public void app_tile_injects_forced_filters() {
        // An app-page inline tile must have the token's forced RLS filter injected
        // before execution — exactly like the dashboard inline tile.
        ds.fileContent = APP_INLINE_TILE;
        pinGuestJwt(
                "app",
                "/homes/admin/portal.saikuapp",
                "admin",
                List.of("ROLE_ADMIN"),
                "u_1",
                "[{\"dimension\":\"Customer\",\"hierarchy\":\"Customer\",\"level\":\"Customer\","
                        + "\"op\":\"in\",\"members\":[\"[Customer].[acme]\"]}]");

        Response r = resource.appTileQuery("homes/admin/portal.saikuapp", "page-1", "t1", null);

        assertEquals(200, r.getStatus());
        assertNotNull("executeAi was called for the inline app tile", ai.lastAiRequest);
        boolean injected = ai.lastAiRequest.getFilters().stream().anyMatch(f -> "Customer".equals(f.getDimension()));
        assertTrue("forced RLS filter must be injected into the app-page inline query", injected);
    }

    @Test
    public void app_tile_reference_forwards_forced_filters_to_executeSaved() {
        ds.fileContent = APP_REFERENCE_TILE;
        pinGuestJwt(
                "app",
                "/homes/admin/portal.saikuapp",
                "admin",
                List.of(),
                "u_1",
                "[{\"dimension\":\"Customer\",\"level\":\"Customer\",\"members\":[\"[Customer].[acme]\"]}]");

        Response r = resource.appTileQuery("homes/admin/portal.saikuapp", "page-1", "t1", null);

        assertEquals(200, r.getStatus());
        assertNotNull("reference app tile must forward forced filters to executeSaved", ai.lastSavedRequest);
        assertEquals(1, ai.lastSavedRequest.getForcedFilters().size());
        assertEquals("Customer", ai.lastSavedRequest.getForcedFilters().get(0).getDimension());
    }

    @Test
    public void app_tile_forced_filters_unappliable_passes_through_fail_closed() {
        // When executeSaved can't apply the RLS filter it returns 403 RLS_UNAPPLIED;
        // the app embed surfaces that fail-closed status verbatim — no leak.
        ds.fileContent = APP_REFERENCE_TILE;
        ai.savedResponseOverride = Response.status(403)
                .entity(Map.of("status", "RLS_UNAPPLIED", "error", "x"))
                .build();
        pinGuestJwt(
                "app",
                "/homes/admin/portal.saikuapp",
                "admin",
                List.of(),
                "u_1",
                "[{\"dimension\":\"Customer\",\"level\":\"Customer\",\"members\":[\"[Customer].[acme]\"]}]");

        Response r = resource.appTileQuery("homes/admin/portal.saikuapp", "page-1", "t1", null);

        assertEquals(403, r.getStatus());
    }

    @Test
    public void app_tile_malformed_forced_filter_claim_fails_closed() {
        // A malformed forced-filter claim must NEVER degrade to an unfiltered query.
        // The inline injection throws before executeAi, so the tile fails closed
        // (non-2xx) and no query runs.
        ds.fileContent = APP_INLINE_TILE;
        pinGuestJwt("app", "/homes/admin/portal.saikuapp", "admin", List.of(), "u_1", "{not-an-array");

        Response r = resource.appTileQuery("homes/admin/portal.saikuapp", "page-1", "t1", null);

        assertTrue("a malformed RLS claim must fail closed (non-2xx)", r.getStatus() >= 400);
        assertNull("a malformed RLS claim must NOT execute the app tile query", ai.lastAiRequest);
    }

    @Test
    public void app_tile_unknown_tile_is_404() {
        ds.fileContent = APP_INLINE_TILE;
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of());

        Response r = resource.appTileQuery("homes/admin/portal.saikuapp", "page-1", "no-such", null);
        assertEquals(404, r.getStatus());
        assertNull("an unknown tile must never execute a query", ai.lastAiRequest);
    }

    @Test
    public void app_tile_refuses_when_kind_is_dashboard() {
        // A dashboard-pinned token must not run app tile queries.
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r = resource.appTileQuery("homes/admin/portal.saikuapp", "page-1", "t1", null);
        assertEquals(401, r.getStatus());
    }

    /* ------ security review: forced-RLS vs client-override collision (inline) ------ */
    // The bug: on the inline path, forced RLS filters were injected FIRST then the
    // client override REMOVED the same-axis forced filter and appended the client's
    // members. A client could therefore widen (union in extra members) or strip
    // (empty members) the RLS restriction. These prove the fix — forced filters are
    // applied LAST and authoritatively; a client can only NARROW within the forced set.
    // Covered for BOTH the dashboard inline tile and the app-page inline tile.

    private static final String DASH_INLINE_TILE =
            "{\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{}}}]}}";
    // Forced: Customer ∈ {acme}
    private static final String FORCE_CUSTOMER_ACME =
            "[{\"dimension\":\"Customer\",\"hierarchy\":\"Customer\",\"level\":\"Customer\",\"op\":\"in\","
                    + "\"members\":[\"[Customer].[acme]\"]}]";
    // Forced: Customer ∈ {acme, bigcorp} — used for the legitimate-narrow test
    private static final String FORCE_CUSTOMER_ACME_BIGCORP =
            "[{\"dimension\":\"Customer\",\"hierarchy\":\"Customer\",\"level\":\"Customer\",\"op\":\"in\","
                    + "\"members\":[\"[Customer].[acme]\",\"[Customer].[bigcorp]\"]}]";

    @Test
    public void dashboard_inline_client_cannot_widen_forced_rls() {
        // Exploit (a): client override widens Customer to {acme, competitor}. competitor must
        // never reach the engine — the executed filter stays Customer ∈ {acme}.
        ds.fileContent = DASH_INLINE_TILE;
        pinGuestJwt("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.tileQuery(
                "homes/admin/exec.saikudash",
                "t1",
                overrides(in("Customer", "[Customer].[acme]", "[Customer].[competitor]")));

        assertEquals(200, r.getStatus());
        AiFilterSelection cust = onlyFilterFor(ai.lastAiRequest.getFilters(), "Customer");
        assertEquals(List.of("[Customer].[acme]"), cust.getMembers());
        assertFalse(
                "competitor must never reach the engine (RLS widen blocked)",
                cust.getMembers().contains("[Customer].[competitor]"));
    }

    @Test
    public void dashboard_inline_client_cannot_strip_forced_rls() {
        // Exploit (b): client override with members:[] to strip the slicer. Forced Customer ∈
        // {acme} must still be enforced.
        ds.fileContent = DASH_INLINE_TILE;
        pinGuestJwt("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Customer")));

        assertEquals(200, r.getStatus());
        AiFilterSelection cust = onlyFilterFor(ai.lastAiRequest.getFilters(), "Customer");
        assertEquals("RLS strip blocked — forced members re-applied", List.of("[Customer].[acme]"), cust.getMembers());
    }

    @Test
    public void dashboard_inline_client_cannot_escape_forced_rls_via_operator() {
        // Exploit variant: client tries op:"not_in" on the forced axis to invert it. The
        // non-"in" operator on a forced axis discards the client contribution — forced applies
        // verbatim as op:"in" {acme}.
        ds.fileContent = DASH_INLINE_TILE;
        pinGuestJwt("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.tileQuery(
                "homes/admin/exec.saikudash", "t1", overrides(notIn("Customer", "[Customer].[acme]")));

        assertEquals(200, r.getStatus());
        AiFilterSelection cust = onlyFilterFor(ai.lastAiRequest.getFilters(), "Customer");
        assertEquals("forced op must win", "in", cust.getOp());
        assertEquals(List.of("[Customer].[acme]"), cust.getMembers());
    }

    @Test
    public void dashboard_inline_client_narrow_within_forced_set_is_honoured() {
        // Legitimate: forced Customer ∈ {acme, bigcorp}; client narrows to {acme}. The narrow is
        // honoured because it stays WITHIN the forced set.
        ds.fileContent = DASH_INLINE_TILE;
        pinGuestJwt("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME_BIGCORP);

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Customer", "[Customer].[acme]")));

        assertEquals(200, r.getStatus());
        AiFilterSelection cust = onlyFilterFor(ai.lastAiRequest.getFilters(), "Customer");
        assertEquals(List.of("[Customer].[acme]"), cust.getMembers());
    }

    @Test
    public void dashboard_inline_client_narrows_different_axis_keeps_both() {
        // Exploit (c) / legitimate: client narrows a DIFFERENT axis (Product). Both the forced
        // Customer RLS and the client's Product narrowing apply.
        ds.fileContent = DASH_INLINE_TILE;
        pinGuestJwt("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        List<AiFilterSelection> fs = ai.lastAiRequest.getFilters();
        assertEquals(List.of("[Customer].[acme]"), onlyFilterFor(fs, "Customer").getMembers());
        assertEquals(
                List.of("[Product].[widgets]"), onlyFilterFor(fs, "Product").getMembers());
    }

    @Test
    public void app_inline_client_cannot_widen_forced_rls() {
        // Same widen exploit, via the app-page inline tile — the shared runTileQuery must block it.
        ds.fileContent = APP_INLINE_TILE;
        pinGuestJwt("app", "/homes/admin/portal.saikuapp", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.appTileQuery(
                "homes/admin/portal.saikuapp",
                "page-1",
                "t1",
                overrides(in("Customer", "[Customer].[acme]", "[Customer].[competitor]")));

        assertEquals(200, r.getStatus());
        AiFilterSelection cust = onlyFilterFor(ai.lastAiRequest.getFilters(), "Customer");
        assertEquals(List.of("[Customer].[acme]"), cust.getMembers());
        assertFalse(cust.getMembers().contains("[Customer].[competitor]"));
    }

    @Test
    public void app_inline_client_cannot_strip_forced_rls() {
        ds.fileContent = APP_INLINE_TILE;
        pinGuestJwt("app", "/homes/admin/portal.saikuapp", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.appTileQuery("homes/admin/portal.saikuapp", "page-1", "t1", overrides(in("Customer")));

        assertEquals(200, r.getStatus());
        AiFilterSelection cust = onlyFilterFor(ai.lastAiRequest.getFilters(), "Customer");
        assertEquals(List.of("[Customer].[acme]"), cust.getMembers());
    }

    @Test
    public void app_inline_client_narrows_different_axis_keeps_both() {
        ds.fileContent = APP_INLINE_TILE;
        pinGuestJwt("app", "/homes/admin/portal.saikuapp", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.appTileQuery(
                "homes/admin/portal.saikuapp", "page-1", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        List<AiFilterSelection> fs = ai.lastAiRequest.getFilters();
        assertEquals(List.of("[Customer].[acme]"), onlyFilterFor(fs, "Customer").getMembers());
        assertEquals(
                List.of("[Product].[widgets]"), onlyFilterFor(fs, "Product").getMembers());
    }

    /* --------------------------- helpers ---------------------------- */

    /** Build a TileQueryOverrides from client filter selections. */
    private static EmbedViewResource.TileQueryOverrides overrides(AiFilterSelection... fs) {
        EmbedViewResource.TileQueryOverrides o = new EmbedViewResource.TileQueryOverrides();
        o.filters = new ArrayList<>(Arrays.asList(fs));
        return o;
    }

    /** op:"in" client filter on dim (dim=hierarchy=level for the test cube). */
    private static AiFilterSelection in(String dim, String... members) {
        AiFilterSelection f = new AiFilterSelection(dim, dim, dim, new ArrayList<>(Arrays.asList(members)));
        f.setOp("in");
        return f;
    }

    /** op:"not_in" client filter — used to prove an operator swap can't escape the RLS clamp. */
    private static AiFilterSelection notIn(String dim, String... members) {
        AiFilterSelection f = new AiFilterSelection(dim, dim, dim, new ArrayList<>(Arrays.asList(members)));
        f.setOp("not_in");
        return f;
    }

    /** Assert exactly one filter targets {@code dim} (executeAi rejects duplicate hierarchies) and
     *  return it. */
    private static AiFilterSelection onlyFilterFor(List<AiFilterSelection> filters, String dim) {
        List<AiFilterSelection> matches = new ArrayList<>();
        for (AiFilterSelection f : filters) {
            if (dim.equals(f.getDimension())) matches.add(f);
        }
        assertEquals("exactly one " + dim + " slicer (executeAi rejects duplicate hierarchies)", 1, matches.size());
        return matches.get(0);
    }

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
