/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.Map;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSavedQueryRequest;
import org.saiku.service.olap.ai.audit.AiAuditEntry;
import org.saiku.service.olap.ai.audit.AiAuditLog;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.rest.resources.dashboards.Dashboard;
import org.saiku.web.rest.resources.dashboards.DashboardTile;
import org.saiku.web.rest.resources.dashboards.TileQuery;
import org.saiku.web.security.embed.EmbedAuthFilter.EmbedGuestDetails;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The ONLY data surface a {@code <saiku-embed>} guest reaches. Spring Security
 * rules grant {@code ROLE_EMBED_GUEST} access only to this prefix — every
 * other path stays {@code isFullyAuthenticated()}, so an embed guest can never
 * reach {@code /ai/query}, drillthrough, mutation, the repository, or the
 * mint endpoints. The role itself is established by {@code EmbedAuthFilter}
 * either from a valid opaque token OR a matching public-grant.
 *
 * <p>The resource the guest may see is pinned by
 * {@link EmbedGuestDetails#resourcePath} — read from the Authentication, never
 * from client input. Tile queries run under
 * {@link EmbedGuestDetails#ownerUser} + {@link EmbedGuestDetails#ownerRoles}
 * via {@link SessionService#runAs} — same delegation pattern as
 * {@code ShareViewResource} (saiku#941), so a publicly-granted dashboard
 * shows the perspective the grantor authorised, not the (often empty)
 * anonymous default.
 */
@Path("/saiku/api/embed")
public class EmbedViewResource {

    private static final Logger log = LoggerFactory.getLogger(EmbedViewResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatasourceService datasourceService;
    private SessionService sessionService;
    private AiQueryResource aiQueryResource;
    private AiAuditLog auditLog;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setAiQueryResource(AiQueryResource r) {
        this.aiQueryResource = r;
    }

    public void setAuditLog(AiAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /* ---------------------------- query ---------------------------- */

    /**
     * Run the saved query the token / public-grant pins. The {@code path}
     * matrix param is informational only — the filter has already validated
     * that it matches the pinned resource. JAX-RS forces us to expose it as
     * a path param so the URL shape lines up with the dashboard reader.
     */
    @GET
    @Path("/query/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response query(@PathParam("path") String pathParam, @jakarta.ws.rs.QueryParam("format") String formatParam) {
        EmbedGuestDetails g = guest();
        if (g == null || !"query".equals(g.resourceKind)) {
            return invalid();
        }
        // Defence-in-depth re-assert the suffix at the trust boundary even
        // though mint validated it.
        if (g.resourcePath == null || !g.resourcePath.endsWith(".saiku")) {
            return invalid();
        }
        // saiku#1104: a saved query can't have RLS forced-filters injected in
        // Phase 1 — fail closed rather than serve unfiltered rows.
        if (g.forcedFiltersJson != null) {
            audit(g, "/saiku/api/embed/query", AiAuditEntry.OUTCOME_DENIED);
            return forcedFilterUnsupported();
        }
        // Whitelist embed output formats — records (default) or matrix. Any other value falls
        // back to records rather than propagating an untrusted string to buildResponse.
        final String format = "matrix".equalsIgnoreCase(formatParam) ? "matrix" : "records";
        try {
            Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                AiSavedQueryRequest sreq = new AiSavedQueryRequest();
                sreq.setPath(g.resourcePath);
                return aiQueryResource.executeSaved(sreq, format);
            });
            audit(g, "/saiku/api/embed/query", AiAuditEntry.OUTCOME_SUCCESS);
            return withPolicyHeader(harden(result), g);
        } catch (RuntimeException e) {
            log.warn("embed-view query execution failed for {}", g.resourcePath, e);
            audit(g, "/saiku/api/embed/query", AiAuditEntry.OUTCOME_ERROR);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Query execution failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /* -------------------------- dashboard -------------------------- */

    /**
     * The pinned dashboard's layout so the Web Component can render its
     * tiles. Like the share-view dashboard endpoint, the dashboard body
     * itself is returned verbatim — the guest then issues one tile-query
     * per renderable tile via {@link #tileQuery}.
     */
    @GET
    @Path("/dashboard/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response dashboard(@PathParam("path") String pathParam) {
        EmbedGuestDetails g = guest();
        if (g == null || !"dashboard".equals(g.resourceKind)) {
            return invalid();
        }
        Dashboard dash = loadDashboard(g);
        if (dash == null) {
            return harden(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "Embedded dashboard is no longer available"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        return withPolicyHeader(
                harden(Response.ok(dash).type(MediaType.APPLICATION_JSON).build()), g);
    }

    /**
     * Run a single tile's authored query under the owner's scope. The guest
     * supplies the tile id only; the query body comes from the pinned
     * dashboard, never the client, so the guest can't pivot the cube or
     * select a different cellset.
     */
    @POST
    @Path("/dashboard/{path:.+}/tile/{tileId}/query")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response tileQuery(
            @PathParam("path") String pathParam, @PathParam("tileId") String tileId, TileQueryOverrides overrides) {
        EmbedGuestDetails g = guest();
        if (g == null || !"dashboard".equals(g.resourceKind)) {
            return invalid();
        }
        Dashboard dash = loadDashboard(g);
        if (dash == null || dash.layout == null || dash.layout.tiles == null) {
            return invalid();
        }
        DashboardTile tile = null;
        for (DashboardTile t : dash.layout.tiles) {
            if (tileId.equals(t.id)) {
                tile = t;
                break;
            }
        }
        if (tile == null || tile.query == null) {
            return harden(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "No such queryable tile"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        final TileQuery q = tile.query;
        // Runtime filter overrides from filter tiles. Guest supplies only the filter payload
        // (dimension/hierarchy/level/members) — the tile's authored query is untouched; we
        // splice the overrides into the request Filters via the same validated path.
        final java.util.List<AiFilterSelection> filterOverrides =
                overrides == null || overrides.filters == null ? java.util.Collections.emptyList() : overrides.filters;
        try {
            Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                if ("inline".equals(q.kind) && q.body != null) {
                    // saiku#1104: inject the JWT's forced RLS filters into the
                    // inline query before execution; they ride the standard
                    // (validated) slicer path in AiSchemaConverter.
                    injectForcedFilters(q.body, g);
                    // Filter-tile overrides splice on top of any authored / forced filters. If a
                    // filter tile drives the same dimension as an authored filter, the override wins.
                    mergeFilterOverrides(q.body, filterOverrides);
                    return aiQueryResource.executeAi(q.body, "records");
                } else if ("reference".equals(q.kind) && q.path != null) {
                    if (g.forcedFiltersJson != null) {
                        // Can't force-filter a saved/reference tile in Phase 1 —
                        // fail closed rather than serve unfiltered rows.
                        return forcedFilterUnsupported();
                    }
                    AiSavedQueryRequest sreq = new AiSavedQueryRequest();
                    sreq.setPath(q.path);
                    // Filter-tile overrides ride the AiSavedQueryRequest.filters channel — the
                    // AI query resource merges these via ThinQueryFilterMerge before execute.
                    if (!filterOverrides.isEmpty()) {
                        sreq.setFilters(filterOverrides);
                    }
                    // Dashboard tiles always render as records — tile renderers consume caption-keyed rows.
                    return aiQueryResource.executeSaved(sreq, "records");
                }
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("status", "VALIDATION_ERROR", "error", "Tile has no runnable query"))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            });
            audit(g, "/saiku/api/embed/dashboard/tile", outcomeFor(result.getStatus()));
            return withPolicyHeader(harden(result), g);
        } catch (RuntimeException e) {
            log.warn("embed-view tile query failed for {}/{}", g.resourcePath, tileId, e);
            audit(g, "/saiku/api/embed/dashboard/tile", AiAuditEntry.OUTCOME_ERROR);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Tile query failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /* ------------------------- filter members ---------------------- */

    /**
     * Return the distinct member captions available for a filter tile's declared
     * dimension/hierarchy/level. Called by the embed bundle when it renders a
     * &lt;EmbedFilterTile&gt; to populate the dropdown; the tile then POSTs the picked
     * members to {@link #tileQuery} as filter overrides.
     *
     * <p>Guest supplies only the tile id — the target dimension+hierarchy+level comes from the
     * pinned dashboard, so a guest can't fish for arbitrary members off the cube.
     */
    @GET
    @Path("/dashboard/{path:.+}/tile/{tileId}/members")
    @Produces(MediaType.APPLICATION_JSON)
    public Response tileMembers(
            @PathParam("path") String pathParam,
            @PathParam("tileId") String tileId,
            @jakarta.ws.rs.QueryParam("q") String q,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {
        EmbedGuestDetails g = guest();
        if (g == null || !"dashboard".equals(g.resourceKind)) {
            return invalid();
        }
        Dashboard dash = loadDashboard(g);
        if (dash == null || dash.layout == null || dash.layout.tiles == null) {
            return invalid();
        }
        DashboardTile tile = null;
        for (DashboardTile t : dash.layout.tiles) {
            if (tileId.equals(t.id)) {
                tile = t;
                break;
            }
        }
        if (tile == null || !"filter".equals(tile.type) || tile.target == null || tile.cube == null) {
            return harden(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "No such filter tile"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        final DashboardTile pinned = tile;
        try {
            Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                String cubeId = pinned.cube.getConnectionName() + "/" + pinned.cube.getCatalog() + "/"
                        + pinned.cube.getSchema() + "/" + pinned.cube.getCubeName();
                return aiQueryResource.searchMembers(
                        cubeId,
                        pinned.target.dimension,
                        pinned.target.hierarchy,
                        pinned.target.level,
                        q,
                        Math.max(1, Math.min(limit, 500)));
            });
            audit(g, "/saiku/api/embed/dashboard/tile/members", outcomeFor(result.getStatus()));
            return withPolicyHeader(harden(result), g);
        } catch (RuntimeException e) {
            log.warn("embed-view tile members failed for {}/{}", g.resourcePath, tileId, e);
            audit(g, "/saiku/api/embed/dashboard/tile/members", AiAuditEntry.OUTCOME_ERROR);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Members lookup failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /* --------------------------- helpers ---------------------------- */

    /**
     * Body accepted by {@link #tileQuery}. Optional filter list from filter tiles — merged onto
     * the tile's authored query before execution. Any other client-supplied field is ignored so
     * a guest can't pivot the cube or swap in an inline query.
     */
    public static class TileQueryOverrides {
        public java.util.List<AiFilterSelection> filters;
    }

    /**
     * Splice runtime filter overrides onto an AiQueryRequest before executeAi. For each
     * override, replace an existing filter that targets the same dimension/hierarchy/level
     * (case-insensitive); append if no match. Empty-members overrides are pruned before the
     * merge so a "clear filter" from a filter tile removes the corresponding slicer entirely.
     */
    private static void mergeFilterOverrides(AiQueryRequest req, java.util.List<AiFilterSelection> overrides) {
        if (req == null || overrides == null || overrides.isEmpty()) return;
        java.util.List<AiFilterSelection> current = req.getFilters();
        if (current == null) current = new java.util.ArrayList<>();
        for (AiFilterSelection o : overrides) {
            if (o == null || o.getDimension() == null) continue;
            java.util.Iterator<AiFilterSelection> it = current.iterator();
            while (it.hasNext()) {
                AiFilterSelection existing = it.next();
                if (existing == null) {
                    it.remove();
                    continue;
                }
                if (sameAxis(existing, o)) {
                    it.remove();
                }
            }
            // A filter with no members = "no restriction"; skip appending.
            if (o.getMembers() == null || o.getMembers().isEmpty()) continue;
            current.add(o);
        }
        req.setFilters(current);
    }

    private static boolean sameAxis(AiFilterSelection a, AiFilterSelection b) {
        return eqIgnoreCase(a.getDimension(), b.getDimension())
                && eqIgnoreCase(a.getHierarchy(), b.getHierarchy())
                && eqIgnoreCase(a.getLevel(), b.getLevel());
    }

    private static boolean eqIgnoreCase(String x, String y) {
        if (x == null) return y == null;
        return x.equalsIgnoreCase(y);
    }

    private Dashboard loadDashboard(EmbedGuestDetails g) {
        if (g.resourcePath == null || !g.resourcePath.endsWith(".saikudash")) {
            return null;
        }
        String raw;
        try {
            // Read as the owner — the token / public-grant authorises viewing
            // exactly this one dashboard under exactly the owner's data scope.
            raw = datasourceService.getFileData(g.resourcePath, g.ownerUser, g.ownerRoles);
        } catch (RuntimeException e) {
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Dashboard.class);
        } catch (Exception e) {
            log.error("embedded dashboard {} is unparseable", g.resourcePath, e);
            return null;
        }
    }

    private EmbedGuestDetails guest() {
        Authentication auth = SecurityContextHolder.getContext() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof EmbedGuestDetails) {
            return (EmbedGuestDetails) auth.getDetails();
        }
        return null;
    }

    private static Response invalid() {
        return harden(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("status", "EMBED_INVALID", "error", "Embed link is invalid or expired."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /**
     * saiku#1104 — append the JWT's forced RLS filters to an inline query. They
     * ride the standard validated slicer path (AiSchemaConverter), so a forced
     * filter can't inject bad MDX. A malformed claim must NOT degrade to an
     * unfiltered query — a parse failure throws, and the caller fails closed.
     */
    private void injectForcedFilters(AiQueryRequest body, EmbedGuestDetails g) {
        if (g.forcedFiltersJson == null || body == null) {
            return;
        }
        try {
            AiFilterSelection[] forced = MAPPER.readValue(g.forcedFiltersJson, AiFilterSelection[].class);
            body.getFilters().addAll(Arrays.asList(forced));
        } catch (Exception e) {
            throw new IllegalStateException("invalid embed forced-filters claim", e);
        }
    }

    private static Response forcedFilterUnsupported() {
        return harden(Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of(
                        "status",
                        "EMBED_RLS_UNSUPPORTED",
                        "error",
                        "This embed enforces row-level filters that cannot be applied to a saved query."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /** saiku#906/#1104 — record an embed query against the audit log, carrying
     *  the JWT {@code sub} (end-user) when present. Embed queries run via direct
     *  method calls, so they bypass the /ai/ AiAuditFilter — auditing here closes
     *  that gap for the embed surface. */
    private void audit(EmbedGuestDetails g, String endpoint, String outcome) {
        if (auditLog == null || g == null) {
            return;
        }
        AiAuditEntry e = new AiAuditEntry();
        e.endpoint = endpoint;
        e.user = g.ownerUser;
        e.sub = g.jwtSub;
        e.outcome = outcome;
        e.policyDecision =
                AiAuditEntry.OUTCOME_DENIED.equals(outcome) ? AiAuditEntry.DECISION_DENY : AiAuditEntry.DECISION_ALLOW;
        auditLog.record(e);
    }

    private static String outcomeFor(int status) {
        if (status >= 200 && status < 300) {
            return AiAuditEntry.OUTCOME_SUCCESS;
        }
        if (status == 400) {
            return AiAuditEntry.OUTCOME_VALIDATION_ERROR;
        }
        if (status == 403) {
            return AiAuditEntry.OUTCOME_DENIED;
        }
        return AiAuditEntry.OUTCOME_ERROR;
    }

    /**
     * saiku-cloud#948 header — signals the saiku-cloud gateway to apply
     * max-strength redaction on the response body, regardless of the
     * tenant's tier or per-tenant mask config. Set when the bound token has
     * {@link org.saiku.web.embed.EmbedToken.RedactionPolicy#FORCE_ON};
     * absent (NOT set to {@code TENANT_DEFAULT}) otherwise so the gateway
     * has a cheap presence check rather than a value parse. The header
     * itself is engine-emit-only — no client can synthesise it because the
     * gateway is the only consumer and it strips trust-region headers from
     * inbound requests.
     *
     * <p>Public-grant requests carry {@code TENANT_DEFAULT} (the public-
     * grant flow has no token to elevate). Tokens that pre-date #1307
     * deserialise to {@code TENANT_DEFAULT} too — neither gets the header,
     * neither triggers gateway-side max-strength.
     */
    public static final String REDACTION_POLICY_HEADER = "X-Saiku-Embed-Redaction-Policy";

    private static Response withPolicyHeader(Response r, EmbedGuestDetails g) {
        if (g == null || g.redactionPolicy == null) return r;
        if (g.redactionPolicy != org.saiku.web.embed.EmbedToken.RedactionPolicy.FORCE_ON) return r;
        return Response.fromResponse(r)
                .header(REDACTION_POLICY_HEADER, g.redactionPolicy.name())
                .build();
    }

    /**
     * Defence-in-depth response headers on EVERY embed reply. Mirrors the
     * share-view hardening (saiku#941) since the threat model is the same —
     * account-free content rendered into a third-party page:
     * <ul>
     *   <li>{@code X-Content-Type-Options: nosniff} — a browser must not
     *       MIME-sniff a JSON body that carries text-tile HTML / image URLs
     *       / member captions and execute it as HTML → blocks stored XSS
     *       at the guest level;</li>
     *   <li>{@code Referrer-Policy: no-referrer} — the embed token lives in
     *       the host page's attribute; never leak it via {@code Referer}
     *       on outbound assets;</li>
     *   <li>{@code Cache-Control: no-store} — embedded business data is
     *       never cached by proxies or browser history.</li>
     * </ul>
     *
     * <p>Deliberately NOT set: {@code X-Frame-Options: DENY} and CSP
     * {@code frame-ancestors 'none'} — the embed surface is designed to
     * render inside the host page (cross-origin XHR / fetch, not iframe),
     * and we DON'T want to block all framing because a host page that uses
     * an iframe-fallback for legacy browsers should still work. Each
     * deployment can tighten CSP at the reverse-proxy layer.
     */
    private static Response harden(Response r) {
        return Response.fromResponse(r)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store, max-age=0")
                .build();
    }
}
