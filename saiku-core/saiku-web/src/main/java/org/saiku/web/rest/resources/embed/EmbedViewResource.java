/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.embed;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.saiku.service.apps.TilePluginParser;
import org.saiku.service.apps.TilePluginRegistry;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSavedQueryRequest;
import org.saiku.service.olap.ai.SavedQueryFilterScope;
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
    // Lenient by design: dashboards AND .saikuapp docs carry rich UI-owned
    // per-tile config the back-end never interprets (chart options, sparkline,
    // conditional formatting, &c. — see DashboardTile's javadoc + saiku#1179).
    // FAIL_ON_UNKNOWN_PROPERTIES=false lets those fields round-trip so a real,
    // fully-authored resource parses instead of 404-ing. It only makes MORE
    // documents parse, never fewer — no security posture changes.
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DatasourceService datasourceService;
    private SessionService sessionService;
    private AiQueryResource aiQueryResource;
    private AiAuditLog auditLog;
    private TilePluginRegistry pluginRegistry;

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

    public void setPluginRegistry(TilePluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
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
        return runSavedQuery(formatParam, null);
    }

    /**
     * Saved query with embed-time slicer overrides. The guest supplies only the filter payload
     * (dimension/hierarchy/level/members) — the saved query's cube binding and axes are untouched;
     * the overrides ride the same validated slicer path ({@link AiSavedQueryRequest#setFilters})
     * that the dashboard filter tiles already use, so a guest can't pivot the cube or inject MDX.
     * A saved query that carries forced RLS filters still fails closed (see {@link #runSavedQuery}).
     *
     * <p>saiku#1946: unlike the dashboard / app tile paths there is no author-declared filter-target
     * list to gate these against, so every override is scoped to the saved query's OWN authored
     * FILTER axis and narrowed within the authored members (see
     * {@link SavedQueryFilterScope#narrowToAuthoredScope}) — a guest can no longer re-point a
     * non-forced rows / columns / pages axis, nor add a deeper level beside an authored one.
     */
    @POST
    @Path("/query/{path:.+}")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response queryFiltered(
            @PathParam("path") String pathParam,
            @jakarta.ws.rs.QueryParam("format") String formatParam,
            TileQueryOverrides overrides) {
        java.util.List<AiFilterSelection> filters =
                overrides == null || overrides.filters == null ? null : overrides.filters;
        return runSavedQuery(formatParam, filters);
    }

    /**
     * Shared body for {@link #query} and {@link #queryFiltered}. Runs the token-pinned saved query
     * under the owner's data scope, optionally splicing embed-time filter overrides onto it.
     */
    private Response runSavedQuery(String formatParam, java.util.List<AiFilterSelection> filters) {
        EmbedGuestDetails g = guest();
        if (g == null || !"query".equals(g.resourceKind)) {
            return invalid();
        }
        // Defence-in-depth re-assert the suffix at the trust boundary even
        // though mint validated it.
        if (g.resourcePath == null || !g.resourcePath.endsWith(".saiku")) {
            return invalid();
        }
        // saiku#1104: forced RLS filters from the token's JWT claims. They ride the saved query's
        // forcedFilters channel and executeSaved fails closed (RLS_UNAPPLIED 403) if any can't be
        // spliced — so a QUERYMODEL saved query now runs WITH the RLS restriction instead of being
        // refused outright, while an MDX-mode / unresolvable case still denies rather than leaks.
        final java.util.List<AiFilterSelection> forced;
        try {
            forced = parseForcedFilters(g);
        } catch (RuntimeException e) {
            audit(g, "/saiku/api/embed/query", AiAuditEntry.OUTCOME_DENIED);
            return forcedFilterUnsupported();
        }
        // Whitelist embed output formats — records (default) or matrix. Any other value falls
        // back to records rather than propagating an untrusted string to buildResponse.
        final String format = "matrix".equalsIgnoreCase(formatParam) ? "matrix" : "records";
        // saiku#1946: a bare saved query has NO author-declared filter-target list (no filter panel,
        // no filter tiles) — the saved query's own slicer IS the author's declaration, so guest
        // overrides are scoped to that authored FILTER axis and intersected with the authored
        // members. Everything else (a re-point of a non-forced rows/columns/pages axis, an additive
        // deeper level, an out-of-scope member) is dropped fail-closed and the query runs as
        // authored. Unreadable / unparseable / MDX-mode saved query ⇒ no overrides at all.
        final java.util.List<AiFilterSelection> overrides = filters == null || filters.isEmpty()
                ? java.util.Collections.emptyList()
                : SavedQueryFilterScope.narrowToAuthoredScope(loadPinnedSavedQuery(g), filters);
        try {
            Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                AiSavedQueryRequest sreq = new AiSavedQueryRequest();
                sreq.setPath(g.resourcePath);
                if (!overrides.isEmpty()) {
                    sreq.setFilters(overrides);
                }
                if (!forced.isEmpty()) {
                    sreq.setForcedFilters(forced);
                }
                return aiQueryResource.executeSaved(sreq, format);
            });
            audit(g, "/saiku/api/embed/query", outcomeFor(result.getStatus()));
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
        // Runtime filter overrides from filter tiles. Guest supplies only the filter payload
        // (dimension/hierarchy/level/members) — the tile's authored query is untouched; the
        // shared runner splices the overrides into the request Filters via the validated path.
        final java.util.List<AiFilterSelection> filterOverrides =
                overrides == null || overrides.filters == null ? java.util.Collections.emptyList() : overrides.filters;
        DashboardTile tile = findTile(dash.layout.tiles, tileId);
        return runTileQuery(
                g, tile, filterOverrides, declaredTargetsForDashboard(dash, tile), "/saiku/api/embed/dashboard/tile");
    }

    /* ----------------------------- app ----------------------------- */

    /**
     * The pinned {@code .saikuapp} document (App Builder — saiku#1441). Served
     * verbatim as opaque JSON exactly like {@link org.saiku.web.rest.resources.apps.AppResource#load}
     * (the embed bundle owns the app schema; a typed re-serialise would silently drop UI-owned
     * fields — saiku#1179). A token pins ONE app: the whole document (nav + every page + every
     * tile) rides that single grant, so the guest sees the app as one unit and can't reach app B
     * or any other repository path (the auth filter pinned {@link EmbedGuestDetails#resourcePath}).
     *
     * <p>Deliberately NOT a new query surface — this only ships the layout. Each page's tiles run
     * through {@link #appTileQuery}, which shares the exact same guarded per-tile execution as the
     * dashboard embed (see {@link #runTileQuery}); RLS/PII enforcement is therefore identical.
     */
    @GET
    @Path("/app/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response app(@PathParam("path") String pathParam) {
        EmbedGuestDetails g = guest();
        if (g == null || !"app".equals(g.resourceKind)) {
            return invalid();
        }
        String raw = loadAppRaw(g);
        if (raw == null) {
            return harden(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "Embedded app is no longer available"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node == null || !node.isObject()) {
                log.error("embedded app {} is not a JSON object", g.resourcePath);
                return harden(Response.serverError()
                        .entity(Map.of("status", "ERROR", "error", "Stored app is not a JSON object"))
                        .type(MediaType.APPLICATION_JSON)
                        .build());
            }
        } catch (Exception e) {
            log.error("embedded app {} is unparseable JSON", g.resourcePath, e);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Stored app is not valid JSON"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        return withPolicyHeader(
                harden(Response.ok(raw).type(MediaType.APPLICATION_JSON).build()), g);
    }

    /**
     * Run a single app-page tile's authored query. The guest supplies only the page id + tile id;
     * the query body comes from the pinned app document, never the client. This delegates to the
     * SAME {@link #runTileQuery} the dashboard embed uses — there is deliberately no app-specific
     * query path, so forced RLS filters + redaction policy fail-closed enforcement is identical.
     */
    @POST
    @Path("/app/{path:.+}/page/{pageId}/tile/{tileId}/query")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response appTileQuery(
            @PathParam("path") String pathParam,
            @PathParam("pageId") String pageId,
            @PathParam("tileId") String tileId,
            TileQueryOverrides overrides) {
        EmbedGuestDetails g = guest();
        if (g == null || !"app".equals(g.resourceKind)) {
            return invalid();
        }
        final java.util.List<AiFilterSelection> filterOverrides =
                overrides == null || overrides.filters == null ? java.util.Collections.emptyList() : overrides.filters;
        DashboardTile tile = findAppTile(g, pageId, tileId);
        return runTileQuery(g, tile, filterOverrides, declaredTargetsForApp(g, tile), "/saiku/api/embed/app/tile");
    }

    /**
     * Distinct members for an app-page filter tile — same contract + same guarded path as the
     * dashboard {@link #tileMembers}. Guest supplies only page id + tile id; the target axis + cube
     * come from the pinned app document, so a guest can't fish for arbitrary members off the cube.
     */
    @GET
    @Path("/app/{path:.+}/page/{pageId}/tile/{tileId}/members")
    @Produces(MediaType.APPLICATION_JSON)
    public Response appTileMembers(
            @PathParam("path") String pathParam,
            @PathParam("pageId") String pageId,
            @PathParam("tileId") String tileId,
            @jakarta.ws.rs.QueryParam("q") String q,
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {
        EmbedGuestDetails g = guest();
        if (g == null || !"app".equals(g.resourceKind)) {
            return invalid();
        }
        return runTileMembers(g, findAppTile(g, pageId, tileId), q, limit, "/saiku/api/embed/app/tile/members");
    }

    /**
     * Token-scoped delivery of an admin-installed tile plugin's {@code plugin.html} srcdoc for the
     * embed surface (App Builder Phase 2, saiku#1441 security fix). Embed guests cannot reach the
     * full-auth {@code /saiku/api/tile-plugins} catalogue, so the sandboxed-plugin tile fetches its
     * markup here instead.
     *
     * <p>Two guards keep this from becoming an arbitrary-plugin (or arbitrary-file) read:
     * <ol>
     *   <li>the {@code app} token pin (same {@code resourceKind == "app"} guard the app-tile query
     *       uses) — a token minted for one app can only fetch through this app;</li>
     *   <li>the {@code pluginId} MUST be referenced by a {@code type:"custom"} plugin tile inside the
     *       PINNED app document (scan of {@code pages[].grid.tiles[]}) — a guest can therefore only
     *       load the exact plugins the embedded app actually uses, never a probe for other installed
     *       plugins.</li>
     * </ol>
     * The plugin HTML itself is admin-installed (dropped into {@code saiku-home/tile-plugins/}), never
     * author- or client-supplied; {@code pluginId} is slug-validated before the registry is touched so
     * it can never traverse the filesystem. Returns 404 for an unreferenced, absent, or slug-invalid
     * id — the same opaque failure whether the plugin is missing or simply not used by this app.
     */
    @GET
    @Path("/app/{path:.+}/plugin/{pluginId}/html")
    @Produces(MediaType.TEXT_HTML)
    public Response appPluginHtml(@PathParam("path") String pathParam, @PathParam("pluginId") String pluginId) {
        EmbedGuestDetails g = guest();
        if (g == null || !"app".equals(g.resourceKind)) {
            return invalid();
        }
        // Reject a slug-invalid id at the door (the registry re-checks, but fail fast).
        if (pluginRegistry == null || !TilePluginParser.isValidId(pluginId)) {
            return pluginNotFound(pluginId);
        }
        // A guest may only fetch a plugin the PINNED app actually references — never an arbitrary
        // installed plugin. Scan the pinned app doc for a type:"custom" plugin tile using this id.
        if (!appReferencesPlugin(g, pluginId)) {
            return pluginNotFound(pluginId);
        }
        String html = pluginRegistry.html(pluginId);
        if (html == null) {
            return pluginNotFound(pluginId);
        }
        return withPolicyHeader(harden(Response.ok(html, MediaType.TEXT_HTML).build()), g);
    }

    /**
     * True when the pinned app document contains at least one {@code type:"custom"} plugin tile
     * ({@code custom.renderer == "plugin"}) whose {@code custom.options.pluginId} equals
     * {@code pluginId}. Walks {@code pages[].grid.tiles[]} of the opaque app JSON exactly like
     * {@link #findAppTile}; returns false on any parse failure so the caller fails closed.
     */
    private boolean appReferencesPlugin(EmbedGuestDetails g, String pluginId) {
        String raw = loadAppRaw(g);
        if (raw == null || pluginId == null) {
            return false;
        }
        try {
            JsonNode pages = MAPPER.readTree(raw).get("pages");
            if (pages == null || !pages.isArray()) {
                return false;
            }
            for (JsonNode page : pages) {
                JsonNode grid = page.get("grid");
                JsonNode tiles = grid == null ? null : grid.get("tiles");
                if (tiles == null || !tiles.isArray()) {
                    continue;
                }
                for (JsonNode tileNode : tiles) {
                    if (!isPluginTileFor(tileNode, pluginId)) {
                        continue;
                    }
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("embedded app {} plugin-reference scan failed", g.resourcePath, e);
            return false;
        }
    }

    /** A single tile node is a plugin tile referencing {@code pluginId} when it is
     *  {@code type:"custom"} + {@code custom.renderer:"plugin"} + {@code custom.options.pluginId}
     *  equal to {@code pluginId}. */
    private static boolean isPluginTileFor(JsonNode tileNode, String pluginId) {
        if (tileNode == null || !tileNode.isObject()) {
            return false;
        }
        JsonNode type = tileNode.get("type");
        if (type == null || !"custom".equals(type.asText())) {
            return false;
        }
        JsonNode custom = tileNode.get("custom");
        if (custom == null || !custom.isObject()) {
            return false;
        }
        JsonNode renderer = custom.get("renderer");
        if (renderer == null || !"plugin".equals(renderer.asText())) {
            return false;
        }
        JsonNode options = custom.get("options");
        JsonNode idNode = options == null ? null : options.get("pluginId");
        return idNode != null && idNode.isTextual() && pluginId.equals(idNode.asText());
    }

    private static Response pluginNotFound(String pluginId) {
        return harden(Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("status", "NOT_FOUND", "error", "No such tile plugin"))
                .build());
    }

    /**
     * Shared per-tile query execution for BOTH dashboard tiles and app-page tiles — the ONE
     * guarded query path on the embed surface. Runs under the pinned owner's scope via
     * {@link SessionService#runAs}, applies runtime filter-tile overrides FIRST then the token's
     * forced RLS filters LAST and authoritatively (inline: {@link #applyForcedFilters}; reference:
     * the {@code executeSaved} forcedFilters channel) — a client filter can only NARROW within the
     * forced set, and a malformed / unappliable claim fails closed. Stamps the redaction-policy +
     * hardening headers. Because app-page tiles reuse this verbatim, embedding an app introduces NO
     * new query path and NO RLS/PII bypass.
     */
    private Response runTileQuery(
            EmbedGuestDetails g,
            DashboardTile tile,
            java.util.List<AiFilterSelection> overrides,
            java.util.Set<String> declaredTargets,
            String endpoint) {
        if (tile == null || tile.query == null) {
            return harden(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "No such queryable tile"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        final TileQuery q = tile.query;
        final java.util.List<AiFilterSelection> filterOverrides =
                overrides == null ? java.util.Collections.emptyList() : overrides;
        final java.util.Set<String> declared =
                declaredTargets == null ? java.util.Collections.emptySet() : declaredTargets;
        try {
            Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                if ("inline".equals(q.kind) && q.body != null) {
                    // Order is LOAD-BEARING for RLS (saiku#1104 + security review): apply the
                    // client filter-tile overrides FIRST (only on author-declared or forced axes —
                    // saiku#1911), then apply the token's forced RLS filters LAST and authoritatively.
                    // Parse forced up front (fail-closed on a malformed claim) so the merge knows
                    // which axes the forced filters own.
                    java.util.List<AiFilterSelection> forced = parseForcedFilters(g);
                    mergeFilterOverrides(q.body, filterOverrides, declared, forcedHierarchiesOf(forced));
                    // executeAi (AiSchemaConverter) has NO forced-filter channel and REJECTS two
                    // filters on one hierarchy, and members inside one filter UNION (Mondrian
                    // aggregates the set). So we can't append a second same-axis filter and can't
                    // trust the client's members. applyForcedFilters collapses each forced axis to a
                    // SINGLE server-computed filter whose members are the forced set intersected with
                    // whatever the client/author put on that axis — a client can only narrow WITHIN
                    // the forced set, never widen, strip, or change the operator. A malformed forced
                    // claim throws above (fail-closed) BEFORE executeAi ever runs.
                    applyForcedFilters(q.body, forced);
                    return aiQueryResource.executeAi(q.body, "records");
                } else if ("reference".equals(q.kind) && q.path != null) {
                    AiSavedQueryRequest sreq = new AiSavedQueryRequest();
                    sreq.setPath(q.path);
                    // Filter-tile overrides ride the AiSavedQueryRequest.filters channel — the
                    // AI query resource merges these via ThinQueryFilterMerge before execute.
                    // saiku#1911: only overrides on an author-declared target are forwarded; an
                    // override on an undeclared axis is dropped fail-closed (a guest can't re-point the
                    // saved query's axes). A client override colliding with a forced-RLS hierarchy is
                    // additionally stripped server-side by executeSaved (dropClientFiltersCollidingWithForced).
                    java.util.List<AiFilterSelection> authorised = authorisedOverrides(filterOverrides, declared);
                    if (!authorised.isEmpty()) {
                        sreq.setFilters(authorised);
                    }
                    // saiku#1104: forced RLS filters ride the forcedFilters channel — executeSaved
                    // applies them or fails closed (RLS_UNAPPLIED 403), so a QUERYMODEL reference
                    // tile now runs WITH the restriction instead of being refused outright.
                    java.util.List<AiFilterSelection> forcedTile = parseForcedFilters(g);
                    if (!forcedTile.isEmpty()) {
                        sreq.setForcedFilters(forcedTile);
                    }
                    // Tiles always render as records — tile renderers consume caption-keyed rows.
                    return aiQueryResource.executeSaved(sreq, "records");
                }
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("status", "VALIDATION_ERROR", "error", "Tile has no runnable query"))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            });
            audit(g, endpoint, outcomeFor(result.getStatus()));
            return withPolicyHeader(harden(result), g);
        } catch (RuntimeException e) {
            log.warn("embed-view tile query failed for {}", g.resourcePath, e);
            audit(g, endpoint, AiAuditEntry.OUTCOME_ERROR);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Tile query failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /** First tile in {@code tiles} whose id equals {@code tileId}, or null. */
    private static DashboardTile findTile(java.util.List<DashboardTile> tiles, String tileId) {
        if (tiles == null || tileId == null) {
            return null;
        }
        for (DashboardTile t : tiles) {
            if (tileId.equals(t.id)) {
                return t;
            }
        }
        return null;
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
        return runTileMembers(
                g, findTile(dash.layout.tiles, tileId), q, limit, "/saiku/api/embed/dashboard/tile/members");
    }

    /**
     * Shared filter-tile member search for BOTH dashboard tiles and app-page tiles. The target
     * dimension/hierarchy/level + cube come from the pinned resource's tile — never the client —
     * and the lookup runs under the pinned owner's scope, so a guest can't fish for arbitrary
     * members off the cube.
     */
    private Response runTileMembers(EmbedGuestDetails g, DashboardTile tile, String q, int limit, String endpoint) {
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
            audit(g, endpoint, outcomeFor(result.getStatus()));
            return withPolicyHeader(harden(result), g);
        } catch (RuntimeException e) {
            log.warn("embed-view tile members failed for {}", g.resourcePath, e);
            audit(g, endpoint, AiAuditEntry.OUTCOME_ERROR);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Members lookup failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /* --------------------------- ai ask ----------------------------- */

    /**
     * DimSum-in-a-widget. A kind="ai" embed token pins a cube (resourcePath =
     * connection/catalog/schema/cubeName); this endpoint accepts a plain-English
     * question and runs it through the same {@link org.saiku.service.olap.ai.ask.AiAskService}
     * the /ai/ask REST endpoint uses, under the pinned owner's data scope.
     *
     * <p>Guest supplies only the question — the cube ref comes from the token so the
     * embedder can't fish across cubes. Response envelope is the same
     * {@code AiAskApi.AskResponse} shape agent clients already know.
     */
    @POST
    @Path("/ai/{cubeId:.+}/ask")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response aiAsk(@PathParam("cubeId") String cubeIdParam, AiAskBody body) {
        EmbedGuestDetails g = guest();
        if (g == null || !"ai".equals(g.resourceKind)) {
            return invalid();
        }
        // Defence-in-depth: cube ref comes from the pinned resourcePath, never from the URI.
        // The auth filter already pinned this comparison at the trust boundary; re-assert here
        // so a future filter regression can't leak into arbitrary-cube ask calls.
        String pinnedCubeId = g.resourcePath == null ? null : g.resourcePath.replaceFirst("^/", "");
        if (pinnedCubeId == null || pinnedCubeId.isBlank()) {
            return invalid();
        }
        if (body == null || body.question == null || body.question.isBlank()) {
            return harden(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("status", "VALIDATION_ERROR", "field", "question", "error", "question required"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        try {
            Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                org.saiku.service.olap.ai.AiCubeRef ref =
                        org.saiku.web.rest.resources.AiQueryResource.parseCubeId(pinnedCubeId);
                if (ref == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "status", "VALIDATION_ERROR",
                                    "error", "pinned cube id is malformed"))
                            .type(MediaType.APPLICATION_JSON)
                            .build();
                }
                org.saiku.service.olap.ai.ask.AiAskApi.AskRequest req =
                        new org.saiku.service.olap.ai.ask.AiAskApi.AskRequest();
                req.setQuestion(body.question);
                req.setCube(ref);
                if (body.history != null) req.setHistory(body.history);
                // saiku#1440: when the guest names an Agent Space persona, route through
                // askInSpace so the space's system prompt, skill filter, and cube allowlist
                // apply. The cube stays pinned (set above), so a space can only NARROW what a
                // guest reaches — if the space's allowlist excludes the pinned cube, askInSpace
                // fails closed with 403 rather than answering.
                String space = body.space == null ? null : body.space.trim();
                if (space != null && !space.isEmpty()) {
                    return aiQueryResource.askInSpace(space, req);
                }
                return aiQueryResource.ask(req);
            });
            audit(g, "/saiku/api/embed/ai/ask", outcomeFor(result.getStatus()));
            return withPolicyHeader(harden(result), g);
        } catch (RuntimeException e) {
            log.warn("embed-view ai ask failed for {}", g.resourcePath, e);
            audit(g, "/saiku/api/embed/ai/ask", AiAuditEntry.OUTCOME_ERROR);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "AI ask failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /**
     * Body accepted by {@link #aiAsk}. Guest supplies only the question, optional history, and an
     * optional Agent Space persona id ({@code space}). The cube ref is never accepted from the
     * client — it stays pinned by the token.
     */
    public static class AiAskBody {
        public String question;
        public java.util.List<org.saiku.service.olap.ai.ask.AiAskApi.NlAskMessageDto> history;
        public String space;
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
     * Splice runtime filter-tile overrides onto an AiQueryRequest, subject to two authorisation
     * gates (saiku#1911):
     * <ol>
     *   <li><b>Declared-target gate (exploit (b)).</b> A client override is honoured ONLY when its
     *       {@code (dimension, hierarchy, level)} matches a filter target the author declared on the
     *       pinned dashboard / app ({@code declaredTargets}) OR it targets a forced-RLS hierarchy
     *       ({@code forcedHierarchies}), which {@link #applyForcedFilters} then clamps. An override on
     *       any other axis is dropped — a guest can't re-point the author's tile onto an axis the
     *       author never exposed.</li>
     *   <li><b>Empty-members is a NO-OP (exploit (b)).</b> An override with no members means "no
     *       selection"; it is skipped WITHOUT removing the author's same-axis filter. It must NEVER
     *       delete an authored filter (the old code removed-then-skipped-append, silently stripping
     *       the author's slice).</li>
     * </ol>
     * A non-empty, authorised override REPLACES the author's same-axis filter with the client's
     * (narrowing) selection.
     *
     * <p>SECURITY: this runs BEFORE {@link #applyForcedFilters}, so it never sees a forced RLS
     * filter (they aren't in the list yet) and is therefore structurally incapable of removing or
     * widening one. Any client selection it leaves on a forced axis is subsequently clamped by
     * {@link #applyForcedFilters}. It must never be called after the forced filters are applied.
     */
    private static void mergeFilterOverrides(
            AiQueryRequest req,
            java.util.List<AiFilterSelection> overrides,
            java.util.Set<String> declaredTargets,
            java.util.Set<String> forcedHierarchies) {
        if (req == null || overrides == null || overrides.isEmpty()) return;
        java.util.List<AiFilterSelection> current = req.getFilters();
        if (current == null) current = new java.util.ArrayList<>();
        for (AiFilterSelection o : overrides) {
            if (o == null || o.getDimension() == null) continue;
            // Gate 1: reject overrides on an undeclared, non-forced axis (fail-closed).
            if (!isAuthorisedOverride(o, declaredTargets, forcedHierarchies)) continue;
            // Gate 2: empty members = no selection = NO-OP. Never delete the author's filter.
            if (o.getMembers() == null || o.getMembers().isEmpty()) continue;
            // Authorised, non-empty: replace the author's same-axis filter with the client's.
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
            current.add(o);
        }
        req.setFilters(current);
    }

    /** True when a client override may affect the query: its (dim,hier,level) is an author-declared
     *  filter target, OR it targets a forced-RLS hierarchy (so {@link #applyForcedFilters} clamps a
     *  legitimate narrowing within the forced set). Everything else is dropped (saiku#1911). */
    private static boolean isAuthorisedOverride(
            AiFilterSelection o, java.util.Set<String> declaredTargets, java.util.Set<String> forcedHierarchies) {
        if (declaredTargets != null
                && declaredTargets.contains(targetKey(o.getDimension(), o.getHierarchy(), o.getLevel()))) {
            return true;
        }
        return forcedHierarchies != null && forcedHierarchies.contains(hierKey(o.getDimension(), o.getHierarchy()));
    }

    /** Normalised, case-insensitive (dim|hier|level) key for declared-target matching. */
    private static String targetKey(String dim, String hier, String level) {
        return norm(dim) + "|" + norm(hier) + "|" + norm(level);
    }

    /** Normalised, case-insensitive (dim|hier) key for forced-hierarchy matching. */
    private static String hierKey(String dim, String hier) {
        return norm(dim) + "|" + norm(hier);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The (dim|hier) keys of the forced RLS filters — the axes {@link #applyForcedFilters} owns. */
    private static java.util.Set<String> forcedHierarchiesOf(java.util.List<AiFilterSelection> forced) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (forced == null) return out;
        for (AiFilterSelection f : forced) {
            if (f != null && f.getDimension() != null) out.add(hierKey(f.getDimension(), f.getHierarchy()));
        }
        return out;
    }

    /** Keep only the client overrides whose (dim,hier,level) matches an author-declared target — the
     *  saved-query (reference-tile) equivalent of {@link #mergeFilterOverrides}'s declared gate. */
    private static java.util.List<AiFilterSelection> authorisedOverrides(
            java.util.List<AiFilterSelection> overrides, java.util.Set<String> declaredTargets) {
        java.util.List<AiFilterSelection> out = new java.util.ArrayList<>();
        if (overrides == null) return out;
        for (AiFilterSelection o : overrides) {
            if (o == null || o.getDimension() == null) continue;
            if (declaredTargets != null
                    && declaredTargets.contains(targetKey(o.getDimension(), o.getHierarchy(), o.getLevel()))) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * The set of author-declared filter targets on a dashboard, as normalised (dim|hier|level) keys.
     * Sources (saiku#1911): the unified filter panel ({@link DashboardFilterPanel#filters}), any
     * {@code type:"filter"} tile's {@link DashboardTile#target}, and the dashboard-level default
     * {@link Dashboard#filters}. A target is included only when its cube is compatible with the
     * queried tile's cube (null on either side = compatible) so a filter declared for cube A can't
     * authorise an override on a cube-B tile.
     */
    private static java.util.Set<String> declaredTargetsForDashboard(Dashboard dash, DashboardTile queried) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (dash == null) return out;
        org.saiku.service.olap.ai.AiCubeRef tileCube = queried == null ? null : queried.cube;
        if (dash.filterPanel != null && dash.filterPanel.filters != null) {
            for (org.saiku.web.rest.resources.dashboards.DashboardFilterPanel.PanelFilter pf :
                    dash.filterPanel.filters) {
                if (pf != null && cubeCompatible(pf.cube, tileCube)) {
                    out.add(targetKey(pf.dimension, pf.hierarchy, pf.level));
                }
            }
        }
        if (dash.layout != null && dash.layout.tiles != null) {
            for (DashboardTile t : dash.layout.tiles) {
                if (t != null && "filter".equals(t.type) && t.target != null && cubeCompatible(t.cube, tileCube)) {
                    out.add(targetKey(t.target.dimension, t.target.hierarchy, t.target.level));
                }
            }
        }
        if (dash.filters != null) {
            for (org.saiku.web.rest.resources.dashboards.DashboardFilter df : dash.filters) {
                if (df != null) out.add(targetKey(df.dimension, df.hierarchy, df.level));
            }
        }
        return out;
    }

    /**
     * The set of author-declared filter targets on a pinned {@code .saikuapp} document, as normalised
     * (dim|hier|level) keys. The app JSON is opaque, so we walk {@code pages[].grid.tiles[]} for
     * {@code type:"filter"} tiles' {@code target} — mirroring {@link #findAppTile}. A target is
     * included only when its cube is compatible with the queried tile's cube. Returns an empty set on
     * any parse failure (fail-closed — no undeclared override is authorised).
     */
    private java.util.Set<String> declaredTargetsForApp(EmbedGuestDetails g, DashboardTile queried) {
        java.util.Set<String> out = new java.util.HashSet<>();
        String raw = loadAppRaw(g);
        if (raw == null) return out;
        org.saiku.service.olap.ai.AiCubeRef tileCube = queried == null ? null : queried.cube;
        try {
            JsonNode pages = MAPPER.readTree(raw).get("pages");
            if (pages == null || !pages.isArray()) return out;
            for (JsonNode page : pages) {
                JsonNode grid = page.get("grid");
                JsonNode tiles = grid == null ? null : grid.get("tiles");
                if (tiles == null || !tiles.isArray()) continue;
                for (JsonNode tileNode : tiles) {
                    JsonNode type = tileNode.get("type");
                    if (type == null || !"filter".equals(type.asText())) continue;
                    DashboardTile t = MAPPER.treeToValue(tileNode, DashboardTile.class);
                    if (t.target != null && cubeCompatible(t.cube, tileCube)) {
                        out.add(targetKey(t.target.dimension, t.target.hierarchy, t.target.level));
                    }
                }
            }
        } catch (Exception e) {
            log.error("embedded app {} declared-target scan failed", g.resourcePath, e);
            return new java.util.HashSet<>();
        }
        return out;
    }

    /** Two cubes are compatible for target scoping when either is null (unspecified) or they name the
     *  same connection/catalog/schema/cube. */
    private static boolean cubeCompatible(
            org.saiku.service.olap.ai.AiCubeRef a, org.saiku.service.olap.ai.AiCubeRef b) {
        if (a == null || b == null) return true;
        return eqIgnoreCase(a.getConnectionName(), b.getConnectionName())
                && eqIgnoreCase(a.getCatalog(), b.getCatalog())
                && eqIgnoreCase(a.getSchema(), b.getSchema())
                && eqIgnoreCase(a.getCubeName(), b.getCubeName());
    }

    /**
     * Apply the token's forced RLS filters to an inline query body AUTHORITATIVELY and LAST — the
     * fix for the inline RLS-bypass (security review). {@code executeAi} has no separate forced
     * channel (unlike {@code executeSaved}), rejects two filters on one hierarchy, and unions the
     * members inside a single filter — so we can't trust the client's list and can't append a
     * second same-axis filter. For each forced filter we therefore:
     * <ol>
     *   <li>remove every existing (authored OR client) filter on the same axis, remembering the
     *       first one;</li>
     *   <li>emit a SINGLE authoritative filter for that axis. When both the forced filter and the
     *       removed client/authored filter are plain {@code op:"in"} member sets, the emitted
     *       members are {@code clientMembers ∩ forcedMembers} (client can only narrow WITHIN the
     *       forced set; any out-of-scope member is dropped). An empty intersection falls back to
     *       the forced ceiling — NEVER empty (empty members would read as "unrestricted"). Any
     *       other operator on either side discards the client contribution and applies the forced
     *       filter verbatim.</li>
     * </ol>
     * Net invariant: on every forced axis the effective member set is always ⊆ the forced set, so
     * a client can never widen, strip, or change the operator of an RLS restriction.
     */
    private static void applyForcedFilters(AiQueryRequest req, java.util.List<AiFilterSelection> forced) {
        if (req == null || forced == null || forced.isEmpty()) return;
        java.util.List<AiFilterSelection> current = req.getFilters();
        if (current == null) current = new java.util.ArrayList<>();
        for (AiFilterSelection f : forced) {
            if (f == null || f.getDimension() == null) {
                // A forced RLS filter with no dimension can't be applied — silently skipping it would
                // run the query UNFILTERED (fail-OPEN). Throw so the tile fails closed, matching
                // parseForcedFilters' treatment of a malformed claim (saiku#1911 SEC nit).
                throw new IllegalStateException("forced RLS filter is missing its dimension");
            }
            // Remove every filter on the same HIERARCHY (authored or client) — NOT just the same
            // (dim,hier,level) axis. saiku#1911 exploit (a): a client filter on a DIFFERENT level of
            // the forced hierarchy must not survive (executeAi would UNION it, widening the RLS
            // slice). Remember only a same-LEVEL, op:"in" client filter as an eligible narrowing.
            AiFilterSelection existing = null;
            java.util.Iterator<AiFilterSelection> it = current.iterator();
            while (it.hasNext()) {
                AiFilterSelection e = it.next();
                if (e == null) {
                    it.remove();
                    continue;
                }
                if (sameHierarchy(e, f)) {
                    // Only a client filter at the SAME level can narrow within the forced set;
                    // a different-level client filter is dropped entirely (forced applies verbatim).
                    if (existing == null && sameAxis(e, f)) existing = e;
                    it.remove();
                }
            }
            // Default: the forced filter is authoritative verbatim. Only when BOTH sides are plain
            // op:"in" member sets do we honour a client NARROWING (intersection within the forced set).
            AiFilterSelection effective = f;
            if (isInOp(f) && existing != null && isInOp(existing)) {
                java.util.List<String> narrowed = intersectMembers(existing.getMembers(), f.getMembers());
                java.util.List<String> members =
                        narrowed.isEmpty() ? new java.util.ArrayList<>(f.getMembers()) : narrowed;
                effective = new AiFilterSelection(f.getDimension(), f.getHierarchy(), f.getLevel(), members);
                effective.setOp("in");
            }
            current.add(effective);
        }
        req.setFilters(current);
    }

    /** True when the filter is a plain member-set inclusion (op "in" / null / empty). Only these
     *  can be safely member-intersected; any other op (not_in / between / relative / …) on a forced
     *  axis makes the client contribution non-narrowing, so the forced filter is applied verbatim. */
    private static boolean isInOp(AiFilterSelection f) {
        String op = f.getOp();
        return op == null || op.isBlank() || "in".equalsIgnoreCase(op);
    }

    /** Members of {@code client} that are ALSO in {@code forced} (exact-match on MDX unique names),
     *  preserving the client order. This is the RLS clamp: anything the client named outside the
     *  forced set is dropped. */
    private static java.util.List<String> intersectMembers(
            java.util.List<String> client, java.util.List<String> forced) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (client == null || forced == null) return out;
        java.util.Set<String> allowed = new java.util.HashSet<>(forced);
        for (String m : client) {
            if (m != null && allowed.contains(m)) out.add(m);
        }
        return out;
    }

    /** Compare through the SAME normalised (trim + lowercase) keys the declared-target /
     *  forced-hierarchy gate uses ({@link #targetKey}), so a filter that passes the gate (e.g. a
     *  leading-space " Customer") is also caught by the clamp here — not left to the converter's
     *  dedupe backstop 400 (saiku#1911 SEC nit). */
    private static boolean sameAxis(AiFilterSelection a, AiFilterSelection b) {
        return targetKey(a.getDimension(), a.getHierarchy(), a.getLevel())
                .equals(targetKey(b.getDimension(), b.getHierarchy(), b.getLevel()));
    }

    /** Same dimension + hierarchy, ANY level. A forced RLS filter owns the whole hierarchy, so a
     *  client filter on any level of it is cleared before the forced filter is applied (saiku#1911).
     *  Uses the same normalised key as the forced-hierarchy gate ({@link #hierKey}). */
    private static boolean sameHierarchy(AiFilterSelection a, AiFilterSelection b) {
        return hierKey(a.getDimension(), a.getHierarchy()).equals(hierKey(b.getDimension(), b.getHierarchy()));
    }

    private static boolean eqIgnoreCase(String x, String y) {
        if (x == null) return y == null;
        return x.equalsIgnoreCase(y);
    }

    /**
     * Read + parse the PINNED saved query (the {@code .saiku} the token names) under the owner's
     * data scope, so {@link SavedQueryFilterScope} can read the axes the author authored. The path
     * comes from the pinned details, never the client, and the read carries the owner's identity
     * explicitly — the same delegation pattern as {@link #loadDashboard}. Returns null on a wrong
     * suffix, an unreadable / empty file, or unparseable JSON, so the caller fails closed (no
     * overrides survive).
     */
    private org.saiku.olap.query2.ThinQuery loadPinnedSavedQuery(EmbedGuestDetails g) {
        if (g == null || g.resourcePath == null || !g.resourcePath.endsWith(".saiku")) {
            return null;
        }
        String raw;
        try {
            raw = datasourceService.getFileData(g.resourcePath, g.ownerUser, g.ownerRoles);
        } catch (RuntimeException e) {
            log.warn("embed-view saved-query read failed for {}", g.resourcePath, e);
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, org.saiku.olap.query2.ThinQuery.class);
        } catch (Exception e) {
            log.warn("embedded saved query {} is unparseable — guest overrides dropped", g.resourcePath, e);
            return null;
        }
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

    /**
     * Read the pinned {@code .saikuapp} document's raw JSON under the owner's data scope. The
     * token / public-grant authorises viewing exactly this one app under exactly the owner's
     * scope — the resource path comes from the pinned details, never the client. Returns null on
     * a wrong suffix, an unreadable / empty file, so callers fail closed.
     */
    private String loadAppRaw(EmbedGuestDetails g) {
        if (g.resourcePath == null || !g.resourcePath.endsWith(".saikuapp")) {
            return null;
        }
        String raw;
        try {
            raw = datasourceService.getFileData(g.resourcePath, g.ownerUser, g.ownerRoles);
        } catch (RuntimeException e) {
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return raw;
    }

    /**
     * Locate one tile inside the pinned app document by (pageId, tileId). The app doc is opaque
     * (the UI owns its schema) so we walk the JSON tree — {@code pages[].grid.tiles[]} — and bind
     * only the matching tile node into the typed {@link DashboardTile} the shared query runner
     * needs. Scoping by page id keeps tiles unambiguous even if two pages reuse a tile id.
     * Returns null (→ 404) when the app, page, or tile can't be resolved.
     */
    private DashboardTile findAppTile(EmbedGuestDetails g, String pageId, String tileId) {
        String raw = loadAppRaw(g);
        if (raw == null || pageId == null || tileId == null) {
            return null;
        }
        try {
            JsonNode pages = MAPPER.readTree(raw).get("pages");
            if (pages == null || !pages.isArray()) {
                return null;
            }
            for (JsonNode page : pages) {
                JsonNode idNode = page.get("id");
                if (idNode == null || !pageId.equals(idNode.asText())) {
                    continue;
                }
                JsonNode grid = page.get("grid");
                JsonNode tiles = grid == null ? null : grid.get("tiles");
                if (tiles == null || !tiles.isArray()) {
                    return null;
                }
                for (JsonNode tileNode : tiles) {
                    JsonNode tId = tileNode.get("id");
                    if (tId != null && tileId.equals(tId.asText())) {
                        return MAPPER.treeToValue(tileNode, DashboardTile.class);
                    }
                }
                return null; // page matched, tile not in it
            }
            return null;
        } catch (Exception e) {
            log.error("embedded app {} tile lookup failed", g.resourcePath, e);
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
     * Parse the token's forced RLS filter claim (saiku#1104) into typed filters. A malformed claim
     * throws so every caller fails closed — a broken RLS claim must never degrade to an unfiltered
     * query. Returns an empty list when the token carries no forced filters.
     */
    private java.util.List<AiFilterSelection> parseForcedFilters(EmbedGuestDetails g) {
        if (g == null || g.forcedFiltersJson == null) {
            return java.util.Collections.emptyList();
        }
        try {
            AiFilterSelection[] forced = MAPPER.readValue(g.forcedFiltersJson, AiFilterSelection[].class);
            return Arrays.asList(forced);
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
     * render inside the host page (cross-origin XHR / fetch, not iframe).
     * The {@link org.saiku.web.servlet.SecurityHeadersFilter} on {@code /*}
     * therefore supplies the saiku#1917 default of {@code frame-ancestors
     * 'self'} / {@code X-Frame-Options: SAMEORIGIN} here. That is harmless for
     * the normal XHR embed (a framed {@code application/json} body can't be
     * executed thanks to {@code nosniff}), but a deployment that still uses
     * the iframe fallback for legacy browsers must widen it with
     * {@code -Dsaiku.security.frameAncestors="'self' https://wiki.example.com"}.
     * Each deployment can tighten CSP further at the reverse-proxy layer.
     */
    private static Response harden(Response r) {
        return Response.fromResponse(r)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store, max-age=0")
                .build();
    }
}
