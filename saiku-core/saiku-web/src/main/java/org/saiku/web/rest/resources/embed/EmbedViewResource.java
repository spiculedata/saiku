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
        return runSavedQuery(formatParam, null);
    }

    /**
     * Saved query with embed-time slicer overrides. The guest supplies only the filter payload
     * (dimension/hierarchy/level/members) — the saved query's cube binding and axes are untouched;
     * the overrides ride the same validated slicer path ({@link AiSavedQueryRequest#setFilters})
     * that the dashboard filter tiles already use, so a guest can't pivot the cube or inject MDX.
     * A saved query that carries forced RLS filters still fails closed (see {@link #runSavedQuery}).
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
        final java.util.List<AiFilterSelection> overrides =
                filters == null ? java.util.Collections.emptyList() : filters;
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
                    // Order is LOAD-BEARING for RLS (saiku#1104 + security review): apply the
                    // client filter-tile overrides FIRST (they may narrow/add on any axis), then
                    // apply the token's forced RLS filters LAST and authoritatively.
                    mergeFilterOverrides(q.body, filterOverrides);
                    // executeAi (AiSchemaConverter) has NO forced-filter channel and REJECTS two
                    // filters on one hierarchy, and members inside one filter UNION (Mondrian
                    // aggregates the set). So we can't append a second same-axis filter and can't
                    // trust the client's members. applyForcedFilters collapses each forced axis to a
                    // SINGLE server-computed filter whose members are the forced set intersected with
                    // whatever the client/author put on that axis — a client can only narrow WITHIN
                    // the forced set, never widen, strip, or change the operator. A malformed forced
                    // claim throws here (fail-closed) BEFORE executeAi ever runs.
                    applyForcedFilters(q.body, parseForcedFilters(g));
                    return aiQueryResource.executeAi(q.body, "records");
                } else if ("reference".equals(q.kind) && q.path != null) {
                    AiSavedQueryRequest sreq = new AiSavedQueryRequest();
                    sreq.setPath(q.path);
                    // Filter-tile overrides ride the AiSavedQueryRequest.filters channel — the
                    // AI query resource merges these via ThinQueryFilterMerge before execute.
                    if (!filterOverrides.isEmpty()) {
                        sreq.setFilters(filterOverrides);
                    }
                    // saiku#1104: forced RLS filters ride the forcedFilters channel — executeSaved
                    // applies them or fails closed (RLS_UNAPPLIED 403), so a QUERYMODEL reference
                    // tile now runs WITH the restriction instead of being refused outright.
                    java.util.List<AiFilterSelection> forcedTile = parseForcedFilters(g);
                    if (!forcedTile.isEmpty()) {
                        sreq.setForcedFilters(forcedTile);
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
     * Splice runtime filter-tile overrides onto an AiQueryRequest. For each override, replace an
     * existing filter that targets the same dimension/hierarchy/level (case-insensitive); append if
     * no match. Empty-members overrides are pruned so a "clear filter" from a filter tile removes
     * the corresponding slicer entirely.
     *
     * <p>SECURITY: this runs BEFORE {@link #applyForcedFilters}, so it never sees a forced RLS
     * filter (they aren't in the list yet) and is therefore structurally incapable of removing or
     * widening one. Any client selection it leaves on a forced axis is subsequently clamped by
     * {@link #applyForcedFilters}. It must never be called after the forced filters are applied.
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
            if (f == null || f.getDimension() == null) continue;
            // Remove every same-axis filter (authored or client); remember the first for narrowing.
            AiFilterSelection existing = null;
            java.util.Iterator<AiFilterSelection> it = current.iterator();
            while (it.hasNext()) {
                AiFilterSelection e = it.next();
                if (e == null) {
                    it.remove();
                    continue;
                }
                if (sameAxis(e, f)) {
                    if (existing == null) existing = e;
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
