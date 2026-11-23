/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.apps.TilePluginRegistry;
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

    /** Marker body of the seeded {@code records-bars} plugin.html — asserted verbatim. */
    private static final String PLUGIN_HTML_MARKER = "<div id=\"root\">RECORDS-BARS-PLUGIN</div>";

    @Before
    public void setUp() throws Exception {
        ds = new StubDatasourceService();
        session = new StubSessionService();
        ai = new StubAiQueryResource();
        audit = new StubAuditLog();
        resource = new EmbedViewResource();
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setAiQueryResource(ai);
        resource.setAuditLog(audit);
        resource.setPluginRegistry(seedPluginRegistry());
    }

    /** A real {@link TilePluginRegistry} (final class — not stubbable) rooted at a temp dir holding
     *  one valid {@code records-bars} bundle, so the plugin-html tests exercise the actual scan +
     *  slug validation + in-root html read rather than a mock. */
    private static TilePluginRegistry seedPluginRegistry() throws Exception {
        Path root = Files.createTempDirectory("saiku-tile-plugins");
        root.toFile().deleteOnExit();
        Path bundle = Files.createDirectory(root.resolve("records-bars"));
        Files.writeString(
                bundle.resolve("plugin.json"),
                "{\"id\":\"records-bars\",\"label\":\"Records bars\"}",
                StandardCharsets.UTF_8);
        Files.writeString(bundle.resolve("plugin.html"), PLUGIN_HTML_MARKER, StandardCharsets.UTF_8);
        return new TilePluginRegistry(root);
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

    /* ------------ saiku#1946: bare saved-query override scope ------------ */

    private static final String CA = "[Geography].[Geography].[State].&[CA]";
    private static final String WA = "[Geography].[Geography].[State].&[WA]";
    private static final String SF = "[Geography].[Geography].[City].&[San Francisco]";
    private static final String NY = "[Geography].[Geography].[State].&[NY]";
    private static final String FRESNO = "[Store].[Store].[Store Name].&[Fresno]";

    /** A saved query whose authored slicer publishes exactly CA + WA at State. */
    private static final String SAVED_QUERY_WITH_SLICER = "{"
            + "\"name\":\"sales\",\"type\":\"QUERYMODEL\",\"queryModel\":{\"axes\":{\"FILTER\":{"
            + "\"location\":\"FILTER\",\"nonEmpty\":false,\"hierarchies\":[{"
            + "\"name\":\"[Geography].[Geography]\",\"caption\":\"Geography\",\"dimension\":\"Geography\","
            + "\"levels\":{\"State\":{\"name\":\"State\",\"caption\":\"State\",\"aggregators\":[],"
            + "\"selection\":{\"type\":\"INCLUSION\",\"members\":["
            + "{\"name\":\"" + CA + "\",\"uniqueName\":\"" + CA + "\",\"caption\":\"CA\"},"
            + "{\"name\":\"" + WA + "\",\"uniqueName\":\"" + WA + "\",\"caption\":\"WA\"}"
            + "]}}}}]}}}}";

    private static EmbedViewResource.TileQueryOverrides overrides(AiFilterSelection... filters) {
        EmbedViewResource.TileQueryOverrides o = new EmbedViewResource.TileQueryOverrides();
        o.filters = new ArrayList<>(Arrays.asList(filters));
        return o;
    }

    private static AiFilterSelection filter(String dim, String hier, String level, String... members) {
        return new AiFilterSelection(dim, hier, level, new ArrayList<>(Arrays.asList(members)));
    }

    @Test
    public void queryFiltered_narrowsOverrideToTheAuthoredSlicerMembers() {
        ds.fileContent = SAVED_QUERY_WITH_SLICER;
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.queryFiltered(
                "homes/admin/sales.saiku", null, overrides(filter("Geography", "Geography", "State", CA)));

        assertEquals(200, r.getStatus());
        assertNotNull(ai.lastSavedRequest.getFilters());
        assertEquals(1, ai.lastSavedRequest.getFilters().size());
        assertEquals(List.of(CA), ai.lastSavedRequest.getFilters().get(0).getMembers());
    }

    @Test
    public void queryFiltered_dropsMembersOutsideTheAuthoredSlicer() {
        ds.fileContent = SAVED_QUERY_WITH_SLICER;
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.queryFiltered(
                "homes/admin/sales.saiku", null, overrides(filter("Geography", "Geography", "State", CA, NY)));

        assertEquals(200, r.getStatus());
        assertEquals(List.of(CA), ai.lastSavedRequest.getFilters().get(0).getMembers());
    }

    @Test
    public void queryFiltered_dropsOverrideOnAnUndeclaredAxis() {
        // saiku#1946 exploit (b): re-pointing an arbitrary non-forced axis. The saved query's slicer
        // is the only declared scope, so an override on anything else never reaches executeSaved.
        ds.fileContent = SAVED_QUERY_WITH_SLICER;
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.queryFiltered(
                "homes/admin/sales.saiku", null, overrides(filter("Store", "Store", "Store Name", FRESNO)));

        assertEquals(200, r.getStatus());
        assertTrue(ai.lastSavedRequest.getFilters() == null
                || ai.lastSavedRequest.getFilters().isEmpty());
    }

    @Test
    public void queryFiltered_dropsAFinerLevelOfTheAuthoredHierarchy() {
        // Adding a City level beside the authored State slicer would surface individual city rows
        // under a roll-up the author published — dropped fail-closed.
        ds.fileContent = SAVED_QUERY_WITH_SLICER;
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.queryFiltered(
                "homes/admin/sales.saiku", null, overrides(filter("Geography", "Geography", "City", SF)));

        assertEquals(200, r.getStatus());
        assertTrue(ai.lastSavedRequest.getFilters() == null
                || ai.lastSavedRequest.getFilters().isEmpty());
    }

    @Test
    public void queryFiltered_unreadableOrMdxSavedQueryForwardsNoOverrides() {
        // MDX-mode (or unparseable) saved query ⇒ no authored scope ⇒ the guest's overrides are
        // dropped and the query runs as authored. Fail-closed, never fail-open.
        ds.fileContent =
                "{\"name\":\"m\",\"type\":\"MDX\",\"mdx\":\"SELECT [Measures].[Unit Sales] ON 0 FROM [Sales]\"}";
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.queryFiltered(
                "homes/admin/sales.saiku", null, overrides(filter("Geography", "Geography", "State", CA)));

        assertEquals(200, r.getStatus());
        assertTrue(ai.lastSavedRequest.getFilters() == null
                || ai.lastSavedRequest.getFilters().isEmpty());
    }

    @Test
    public void queryFiltered_stillAppliesForcedRlsFiltersAlongsideScopedOverrides() {
        // The scope gate is presentation-scope only — it must not weaken the forced-RLS channel.
        ds.fileContent = SAVED_QUERY_WITH_SLICER;
        pinGuestJwt(
                "query",
                "/homes/admin/sales.saiku",
                "admin",
                List.of("ROLE_ADMIN"),
                "end-user",
                "[{\"dimension\":\"Store\",\"hierarchy\":\"Store\",\"level\":\"Store Name\","
                        + "\"members\":[\"[Store].[Store].[Store Name].&[San Jose]\"]}]");

        resource.queryFiltered(
                "homes/admin/sales.saiku", null, overrides(filter("Geography", "Geography", "State", CA)));

        assertNotNull(ai.lastSavedRequest.getForcedFilters());
        assertEquals(1, ai.lastSavedRequest.getForcedFilters().size());
        assertEquals(1, ai.lastSavedRequest.getFilters().size());
        assertEquals(List.of(CA), ai.lastSavedRequest.getFilters().get(0).getMembers());
    }

    @Test
    public void queryFiltered_scopedOverrideNeverTouchesTheOwnerScope() {
        ds.fileContent = SAVED_QUERY_WITH_SLICER;
        pinGuest("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"));

        resource.queryFiltered(
                "homes/admin/sales.saiku", null, overrides(filter("Geography", "Geography", "State", CA)));

        assertEquals("admin", session.lastRunAsUser);
        assertEquals(List.of("ROLE_ADMIN"), session.lastRunAsRoles);
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

    /* ---------- saiku#1441: token-scoped plugin-html (arbitrary-JS exfil fix) ---------- */

    // An app page with ONE plugin tile referencing the installed "records-bars" plugin.
    private static final String APP_PLUGIN_TILE = "{\"id\":\"a-1\",\"name\":\"Portal\",\"pages\":[{\"id\":\"page-1\","
            + "\"grid\":{\"tiles\":[{\"id\":\"t1\",\"type\":\"custom\",\"custom\":{\"renderer\":\"plugin\","
            + "\"options\":{\"pluginId\":\"records-bars\"}}}]}}]}";

    @Test
    public void app_plugin_html_returns_registry_html_for_referenced_plugin() {
        // The pinned app references records-bars AND it is installed -> serve the admin registry HTML.
        ds.fileContent = APP_PLUGIN_TILE;
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of("ROLE_ADMIN"));

        Response r = resource.appPluginHtml("homes/admin/portal.saikuapp", "records-bars");

        assertEquals(200, r.getStatus());
        assertEquals(PLUGIN_HTML_MARKER, r.getEntity());
        assertEquals("text/html", r.getMediaType().toString());
        assertHardenedHeaders(r);
    }

    @Test
    public void app_plugin_html_404_when_plugin_not_referenced_by_app() {
        // records-bars is INSTALLED but the pinned app does NOT reference it (inline tile only).
        // A guest must not be able to fetch an installed-but-unreferenced plugin (probe defence).
        ds.fileContent = APP_INLINE_TILE;
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of());

        Response r = resource.appPluginHtml("homes/admin/portal.saikuapp", "records-bars");
        assertEquals(404, r.getStatus());
    }

    @Test
    public void app_plugin_html_404_when_referenced_plugin_not_installed() {
        // The app references a plugin id that is NOT installed in the registry -> 404 (absent).
        String appRefsMissing = APP_PLUGIN_TILE.replace("records-bars", "not-installed");
        ds.fileContent = appRefsMissing;
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of());

        Response r = resource.appPluginHtml("homes/admin/portal.saikuapp", "not-installed");
        assertEquals(404, r.getStatus());
    }

    @Test
    public void app_plugin_html_refuses_when_kind_is_dashboard() {
        // Defence in depth behind the auth filter's per-kind pin: a dashboard token can't fetch
        // app plugin HTML.
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r = resource.appPluginHtml("homes/admin/portal.saikuapp", "records-bars");
        assertEquals(401, r.getStatus());
    }

    @Test
    public void app_plugin_html_404_for_slug_invalid_id() {
        // A slug-invalid id is rejected before the registry is touched (never a filesystem probe).
        ds.fileContent = APP_PLUGIN_TILE;
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of());

        Response r = resource.appPluginHtml("homes/admin/portal.saikuapp", "../../etc/passwd");
        assertEquals(404, r.getStatus());
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
    // saiku#1911: a dashboard that DECLARES a Product filter target in its filter panel, so a client
    // override on (Product,Product,Product) is authorised. The queried tile "t1" is an empty inline.
    private static final String DASH_INLINE_TILE_PRODUCT_DECLARED =
            "{\"filterPanel\":{\"filters\":[{\"dimension\":\"Product\",\"hierarchy\":\"Product\",\"level\":\"Product\"}]},"
                    + "\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{}}}]}}";
    // saiku#1911: declares a Product filter target AND the authored inline query already filters
    // Product ∈ {widgets}. Used to prove an empty-members override is a NO-OP, not a delete.
    private static final String DASH_INLINE_TILE_AUTHORED_PRODUCT =
            "{\"filterPanel\":{\"filters\":[{\"dimension\":\"Product\",\"hierarchy\":\"Product\",\"level\":\"Product\"}]},"
                    + "\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{\"filters\":"
                    + "[{\"dimension\":\"Product\",\"hierarchy\":\"Product\",\"level\":\"Product\",\"op\":\"in\","
                    + "\"members\":[\"[Product].[widgets]\"]}]}}}]}}";
    // saiku#1911: an app whose page declares a Product filter tile (id f1) alongside the queried
    // inline tile t1, so a client override on Product is authorised on the app surface too.
    private static final String APP_INLINE_TILE_PRODUCT_DECLARED =
            "{\"id\":\"a-1\",\"name\":\"Portal\",\"pages\":[{\"id\":\"page-1\",\"grid\":{\"tiles\":["
                    + "{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{}}},"
                    + "{\"id\":\"f1\",\"type\":\"filter\",\"target\":{\"dimension\":\"Product\","
                    + "\"hierarchy\":\"Product\",\"level\":\"Product\"}}]}}]}";
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
        // Legitimate: client narrows a DIFFERENT axis (Product) that the dashboard DECLARES as a
        // filter target. Both the forced Customer RLS and the client's Product narrowing apply.
        // (saiku#1911: the Product target must be declared — an undeclared override is now rejected;
        // see dashboard_inline_override_on_undeclared_axis_is_rejected.)
        ds.fileContent = DASH_INLINE_TILE_PRODUCT_DECLARED;
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
    public void dashboard_inline_client_cannot_widen_forced_rls_via_different_level() {
        // saiku#1911 sub-fix #1 (inline half): the client override targets a DIFFERENT level
        // ("Customer Country") of the FORCED hierarchy (Customer). It passes mergeFilterOverrides via
        // the forced-hierarchy gate, but applyForcedFilters must clear it by HIERARCHY (not just the
        // exact dim/hier/level axis) so the ONLY Customer filter reaching the engine is the forced
        // level "Customer" = {acme}. Reverting the sameHierarchy removal to sameAxis leaves the
        // client's Country/USA filter in place → two Customer filters → onlyFilterFor fails.
        ds.fileContent = DASH_INLINE_TILE;
        pinGuestJwt("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.tileQuery(
                "homes/admin/exec.saikudash",
                "t1",
                overrides(inLevel("Customer", "Customer", "Customer Country", "[Customer].[Country].[USA]")));

        assertEquals(200, r.getStatus());
        AiFilterSelection cust = onlyFilterFor(ai.lastAiRequest.getFilters(), "Customer");
        assertEquals("forced level must win over a different-level client override", "Customer", cust.getLevel());
        assertEquals(List.of("[Customer].[acme]"), cust.getMembers());
        assertFalse(
                "the different-level client override must never reach the engine",
                cust.getMembers().contains("[Customer].[Country].[USA]"));
    }

    @Test
    public void dashboard_inline_forced_filter_with_null_dimension_fails_closed() {
        // saiku#1911 SEC nit: a forced RLS filter whose dimension is null used to be SILENTLY skipped
        // (fail-open — the query ran unfiltered). It must now throw so the tile fails closed and no
        // query executes.
        ds.fileContent = DASH_INLINE_TILE;
        pinGuestJwt(
                "dashboard",
                "/homes/admin/exec.saikudash",
                "admin",
                List.of(),
                "u_1",
                "[{\"level\":\"Customer\",\"members\":[\"[Customer].[acme]\"]}]"); // no "dimension"

        Response r = resource.tileQuery("homes/admin/exec.saikudash", "t1", null);

        assertTrue("a dimensionless forced RLS filter must fail closed (non-2xx)", r.getStatus() >= 400);
        assertNull("no query may execute when a forced RLS filter can't be applied", ai.lastAiRequest);
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
        // Legitimate: Product is declared by a filter tile on the app page (saiku#1911).
        ds.fileContent = APP_INLINE_TILE_PRODUCT_DECLARED;
        pinGuestJwt("app", "/homes/admin/portal.saikuapp", "admin", List.of(), "u_1", FORCE_CUSTOMER_ACME);

        Response r = resource.appTileQuery(
                "homes/admin/portal.saikuapp", "page-1", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        List<AiFilterSelection> fs = ai.lastAiRequest.getFilters();
        assertEquals(List.of("[Customer].[acme]"), onlyFilterFor(fs, "Customer").getMembers());
        assertEquals(
                List.of("[Product].[widgets]"), onlyFilterFor(fs, "Product").getMembers());
    }

    /* ---- saiku#1911: declared-target gate + empty-members-is-a-no-op (exploit (b)) ---- */

    @Test
    public void dashboard_inline_override_on_undeclared_axis_is_rejected() {
        // Exploit (b): the dashboard declares NO filter targets, yet the guest overrides Product.
        // PRE-FIX mergeFilterOverrides accepted an override on ANY axis, so Product reached the
        // engine. The fix drops any override that isn't an author-declared target (and isn't a
        // forced-RLS axis) — Product must never reach the executed query.
        ds.fileContent = DASH_INLINE_TILE; // no filterPanel / filter tiles → nothing declared
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        assertNotNull(ai.lastAiRequest);
        assertFalse(
                "an override on an UNDECLARED axis must be rejected (not reach the engine)",
                ai.lastAiRequest.getFilters().stream().anyMatch(f -> "Product".equals(f.getDimension())));
    }

    @Test
    public void app_inline_override_on_undeclared_axis_is_rejected() {
        // Same declared-target gate on the app-page inline tile (shared runTileQuery).
        ds.fileContent = APP_INLINE_TILE; // no filter tile → nothing declared
        pinGuest("app", "/homes/admin/portal.saikuapp", "admin", List.of());

        Response r = resource.appTileQuery(
                "homes/admin/portal.saikuapp", "page-1", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        assertNotNull(ai.lastAiRequest);
        assertFalse(
                "an undeclared override must be rejected on the app surface too",
                ai.lastAiRequest.getFilters().stream().anyMatch(f -> "Product".equals(f.getDimension())));
    }

    @Test
    public void dashboard_inline_empty_members_override_does_not_delete_authored_filter() {
        // Exploit (b): the author's tile filters Product ∈ {widgets}. The guest sends an override on
        // Product with NO members ("clear filter"). PRE-FIX mergeFilterOverrides removed the authored
        // filter then skipped re-adding it — silently STRIPPING the author's slice. The fix treats an
        // empty-members override as a NO-OP: the authored Product ∈ {widgets} must survive intact.
        ds.fileContent = DASH_INLINE_TILE_AUTHORED_PRODUCT;
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r = resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product")));

        assertEquals(200, r.getStatus());
        AiFilterSelection prod = onlyFilterFor(ai.lastAiRequest.getFilters(), "Product");
        assertEquals(
                "an empty-members override must NOT delete the author's filter",
                List.of("[Product].[widgets]"),
                prod.getMembers());
    }

    @Test
    public void dashboard_inline_declared_override_replaces_authored_filter() {
        // Positive / no-regression: a NON-empty override on a DECLARED target legitimately replaces
        // the author's selection (narrowing within the author's exposed filter) — the intended flow.
        ds.fileContent = DASH_INLINE_TILE_AUTHORED_PRODUCT;
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product", "[Product].[gadgets]")));

        assertEquals(200, r.getStatus());
        AiFilterSelection prod = onlyFilterFor(ai.lastAiRequest.getFilters(), "Product");
        assertEquals(
                "a declared, non-empty override replaces the authored selection",
                List.of("[Product].[gadgets]"),
                prod.getMembers());
    }

    /* ---- saiku#1911: remaining declared-target sources (SEC finding) + cubeCompatible ---- */

    @Test
    public void dashboard_inline_declared_via_top_level_filters_list_is_authorised() {
        // Declared via the LEGACY top-level Dashboard.filters list (pre-filterPanel dashboards),
        // not the unified filterPanel and not a filter tile. declaredTargetsForDashboard must still
        // pick this source up.
        ds.fileContent = "{\"filters\":[{\"dimension\":\"Product\",\"hierarchy\":\"Product\",\"level\":\"Product\"}],"
                + "\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{}}}]}}";
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        assertEquals(
                "a target declared via the legacy dash.filters list must authorise the override",
                List.of("[Product].[widgets]"),
                onlyFilterFor(ai.lastAiRequest.getFilters(), "Product").getMembers());
    }

    @Test
    public void dashboard_inline_declared_via_layout_filter_tile_is_authorised() {
        // Declared via a type:"filter" TILE sitting in dash.layout.tiles (the pre-filterPanel widget
        // model) rather than the unified filterPanel.
        ds.fileContent = "{\"layout\":{\"tiles\":["
                + "{\"id\":\"t1\",\"query\":{\"kind\":\"inline\",\"body\":{}}},"
                + "{\"id\":\"f1\",\"type\":\"filter\",\"target\":{\"dimension\":\"Product\","
                + "\"hierarchy\":\"Product\",\"level\":\"Product\"}}]}}";
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        assertEquals(
                "a target declared via a dashboard filter TILE must authorise the override",
                List.of("[Product].[widgets]"),
                onlyFilterFor(ai.lastAiRequest.getFilters(), "Product").getMembers());
    }

    @Test
    public void dashboard_inline_declared_target_on_a_different_cube_is_rejected() {
        // The filter panel declares a Product target scoped to a DIFFERENT cube than the queried
        // tile's own cube — cubeCompatible must reject the match so a filter authored for cube A
        // can't authorise an override on a cube-B tile.
        ds.fileContent = "{\"filterPanel\":{\"filters\":[{\"dimension\":\"Product\",\"hierarchy\":\"Product\","
                + "\"level\":\"Product\",\"cube\":{\"connectionName\":\"foodmart\",\"catalog\":\"FoodMart\","
                + "\"schema\":\"FoodMart\",\"cubeName\":\"OtherCube\"}}]},"
                + "\"layout\":{\"tiles\":[{\"id\":\"t1\",\"cube\":{\"connectionName\":\"foodmart\","
                + "\"catalog\":\"FoodMart\",\"schema\":\"FoodMart\",\"cubeName\":\"Sales\"},"
                + "\"query\":{\"kind\":\"inline\",\"body\":{}}}]}}";
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        assertNotNull(ai.lastAiRequest);
        assertFalse(
                "a declared target scoped to a DIFFERENT cube must not authorise the override",
                ai.lastAiRequest.getFilters().stream().anyMatch(f -> "Product".equals(f.getDimension())));
    }

    @Test
    public void dashboard_inline_declared_target_on_the_same_cube_is_authorised() {
        // Same shape as above but the filter panel's cube MATCHES the queried tile's cube exactly —
        // proves cubeCompatible's equality branch (not just its null/null fallback) authorises.
        ds.fileContent = "{\"filterPanel\":{\"filters\":[{\"dimension\":\"Product\",\"hierarchy\":\"Product\","
                + "\"level\":\"Product\",\"cube\":{\"connectionName\":\"foodmart\",\"catalog\":\"FoodMart\","
                + "\"schema\":\"FoodMart\",\"cubeName\":\"Sales\"}}]},"
                + "\"layout\":{\"tiles\":[{\"id\":\"t1\",\"cube\":{\"connectionName\":\"foodmart\","
                + "\"catalog\":\"FoodMart\",\"schema\":\"FoodMart\",\"cubeName\":\"Sales\"},"
                + "\"query\":{\"kind\":\"inline\",\"body\":{}}}]}}";
        pinGuest("dashboard", "/homes/admin/exec.saikudash", "admin", List.of());

        Response r =
                resource.tileQuery("homes/admin/exec.saikudash", "t1", overrides(in("Product", "[Product].[widgets]")));

        assertEquals(200, r.getStatus());
        assertEquals(
                "a declared target scoped to the SAME cube must authorise the override",
                List.of("[Product].[widgets]"),
                onlyFilterFor(ai.lastAiRequest.getFilters(), "Product").getMembers());
    }

    /* --------------------------- helpers ---------------------------- */

    /** op:"in" client filter on dim (dim=hierarchy=level for the test cube). */
    private static AiFilterSelection in(String dim, String... members) {
        AiFilterSelection f = new AiFilterSelection(dim, dim, dim, new ArrayList<>(Arrays.asList(members)));
        f.setOp("in");
        return f;
    }

    /** op:"in" client filter with an explicit (dim, hier, level) — used to prove a client override on
     *  a DIFFERENT level of a forced hierarchy is still clamped. */
    private static AiFilterSelection inLevel(String dim, String hier, String level, String... members) {
        AiFilterSelection f = new AiFilterSelection(dim, hier, level, new ArrayList<>(Arrays.asList(members)));
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
