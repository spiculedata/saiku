/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.embed;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFileObject;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.audit.AiAuditEntry;
import org.saiku.service.olap.ai.audit.AiAuditLog;
import org.saiku.web.embed.AuthoringCubeCatalogue;
import org.saiku.web.embed.AuthoringQueryValidator;
import org.saiku.web.embed.EmbedAuthoringScope;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.security.embed.EmbedAuthFilter.EmbedGuestDetails;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1435 — <b>Creator Mode</b>: the one embed surface that writes.
 *
 * <p>A {@code resourceKind="authoring"} token (or an embed JWT with
 * {@code saiku.resourceKind=authoring} + a {@code saiku.tenantId} claim) pins
 * exactly one cube and one tenant. The visitor of a host product's page can
 * then build their own query against that cube and save it — as a saved query or
 * a dashboard — inside their tenant's folder and nowhere else. That is the
 * capability OEM/ISV deals ask for: "our customers get self-serve analytics"
 * without leaving the product.
 *
 * <p><b>Why the writes live here and not on {@code /homes/**}.</b> The issue
 * sketched "widen the filter to allow POST/PUT on
 * {@code /homes/embed-guest-<tenantId>/**}". The homes tree is served by
 * {@code BasicRepositoryResource2}, whose ACL decisions are made for real Saiku
 * users; handing the embed guest a blanket write verb there would mean
 * re-deriving the whole tenant-scope argument inside a resource that has no
 * notion of an embed identity, and any future path there would inherit the
 * grant. So the embed identity is admitted — by the auth filter and the Spring
 * rule — ONLY under this prefix, and the target path is <em>always</em>
 * re-derived server-side from the token's tenant ({@link EmbedAuthoringScope});
 * no endpoint here accepts a caller-supplied path. The effective write scope is
 * identical to the proposed one, with the widening confined to a class that was
 * written to have no other scope.
 *
 * <p>Every endpoint re-asserts the pin from the {@link EmbedGuestDetails} on the
 * SecurityContext (kind, cube ref, tenant) rather than trusting the URL — the
 * filter already proved it, and this is defence in depth for a future filter
 * regression. Every response is hardened exactly like the read surface
 * ({@code nosniff}, {@code no-store}, {@code no-referrer}).
 *
 * <p>Queries arrive as JSON from a third-party page, so nothing in them is taken
 * on faith: {@link AuthoringQueryValidator} checks every hierarchy, level,
 * member and measure against a frozen catalogue of the pinned cube, and refuses
 * raw MDX, filters, sort expressions, calculated members, named sets and query
 * parameters outright. The MDX is generated server-side by the normal
 * {@link ThinQueryService} path, so a creator can only ever name what the
 * catalogue listed.
 *
 * <p>Queries and saves run under the token owner's identity and roles via
 * {@link SessionService#runAs} — the same delegation {@code EmbedViewResource}
 * uses — so a creator's objects live in the owner's home and inherit the owner's
 * data scope, exactly as if the owner had built them.
 */
@Path("/saiku/api/embed/authoring")
public class EmbedAuthoringResource {

    private static final Logger log = LoggerFactory.getLogger(EmbedAuthoringResource.class);

    /** Lenient like the rest of the embed surface: a creator-authored document
     *  carries UI-owned fields the back-end never interprets, and dropping them
     *  on save would silently lose the author's work. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String SAVE_OK = "Save Okay";

    private DatasourceService datasourceService;
    private SessionService sessionService;
    private OlapDiscoverService olapDiscoverService;
    private ThinQueryService thinQueryService;
    private AiQueryResource aiQueryResource;
    private AiAuditLog auditLog;

    /** Freezing a cube walks every visible level's members, which is far too
     *  much work to repeat on each keystroke of the creator's preview. Cached
     *  per cube for a short TTL so a schema edit shows up without a restart. */
    private Cache<String, AuthoringCubeCatalogue> catalogueCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setOlapDiscoverService(OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    public void setThinQueryService(ThinQueryService s) {
        this.thinQueryService = s;
    }

    public void setAiQueryResource(AiQueryResource r) {
        this.aiQueryResource = r;
    }

    public void setAuditLog(AiAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public void setCatalogueCache(Cache<String, AuthoringCubeCatalogue> cache) {
        this.catalogueCache = cache;
    }

    /* ---------------------------- context ---------------------------- */

    /**
     * The creator's whole world: the pinned tenant + scope folder, the pinned
     * cube, and the curated catalogue (visible dimensions → levels → members,
     * plus visible measures) the client is allowed to build from. Deliberately
     * one cube — there is no "switch cube" affordance anywhere in the surface,
     * so the "guest can't pivot to another cube" property is structural.
     */
    @GET
    @Path("/{connection}/{catalog}/{schema}/{cube}/context")
    @Produces(MediaType.APPLICATION_JSON)
    public Response context(
            @PathParam("connection") String connection,
            @PathParam("catalog") String catalog,
            @PathParam("schema") String schema,
            @PathParam("cube") String cube) {
        EmbedGuestDetails g = guard(connection, catalog, schema, cube);
        if (g instanceof Response) {
            return (Response) g;
        }
        AuthoringCubeCatalogue catalogue = catalogue(g, connection, catalog, schema, cube);
        if (catalogue == null) {
            return error("pinned cube is not available", Response.Status.NOT_FOUND);
        }
        return harden(Response.ok(catalogue.toContextJson(g.tenantId, scope(g)))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /* ---------------------------- preview ---------------------------- */

    /** Body for {@link #preview} and {@link #query}. */
    public static class SaveQueryBody {
        public String name;
        public JsonNode query;
    }

    /**
     * Run a creator-built query against the pinned cube and return the standard
     * {@link AiQueryResponse} envelope (records or matrix), so the stripped
     * workbench renders results with the same code path the full workspace uses.
     * Nothing is persisted — this is the "Run" affordance.
     */
    @POST
    @Path("/{connection}/{catalog}/{schema}/{cube}/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response preview(
            @PathParam("connection") String connection,
            @PathParam("catalog") String catalog,
            @PathParam("schema") String schema,
            @PathParam("cube") String cube,
            @QueryParam("format") String format,
            SaveQueryBody body) {
        EmbedGuestDetails g = guard(connection, catalog, schema, cube);
        if (g instanceof Response) {
            return (Response) g;
        }
        if (body == null || body.query == null || body.query.isNull()) {
            return badRequest("query", "query is required");
        }
        AuthoringCubeCatalogue catalogue = catalogue(g, connection, catalog, schema, cube);
        if (catalogue == null) {
            return error("pinned cube is not available", Response.Status.NOT_FOUND);
        }
        ThinQuery tq;
        try {
            tq = acceptQuery(g, catalogue, body.query);
        } catch (QueryRefused refused) {
            return badRequest("query", refused.getMessage());
        }
        long start = System.currentTimeMillis();
        final Response result;
        try {
            result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                thinQueryService.createQuery(tq);
                CellDataSet cds = thinQueryService.execute(tq);
                // Whitelist the format: only the two the rest of the surface emits.
                String fmt = "matrix".equalsIgnoreCase(format) ? "matrix" : "records";
                return Response.ok(aiQueryResource.buildResponse(tq, cds, start, fmt))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            });
        } catch (RuntimeException e) {
            // A pick the cube can't satisfy (a level with no members in a filtered
            // slice, a measure dropped for lack of a join path) is a 400 about the
            // request, not a 500: the host page shows the message, the internals
            // stay in the log.
            log.info("embed authoring preview refused for tenant {}: {}", g.tenantId, e.getMessage());
            audit(g, "/saiku/api/embed/authoring/preview", AiAuditEntry.OUTCOME_ERROR);
            return badRequest("query", "the query could not be run against the pinned cube");
        }
        audit(g, "/saiku/api/embed/authoring/preview", outcomeFor(result.getStatus()));
        return harden(result);
    }

    /* ------------------------- saved query ------------------------- */

    /**
     * Save the creator's query as a {@code .saiku} object inside the tenant's
     * folder. The file is the serialised {@link ThinQuery} exactly as the
     * workspace writes it, so the object opens in the full Saiku workbench for
     * the owner afterwards.
     */
    @POST
    @Path("/{connection}/{catalog}/{schema}/{cube}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveQuery(
            @PathParam("connection") String connection,
            @PathParam("catalog") String catalog,
            @PathParam("schema") String schema,
            @PathParam("cube") String cube,
            SaveQueryBody body) {
        EmbedGuestDetails g = guard(connection, catalog, schema, cube);
        if (g instanceof Response) {
            return (Response) g;
        }
        if (body == null || body.name == null || body.name.isBlank()) {
            return badRequest("name", "name is required");
        }
        if (body.query == null || body.query.isNull()) {
            return badRequest("query", "query is required");
        }
        AuthoringCubeCatalogue catalogue = catalogue(g, connection, catalog, schema, cube);
        if (catalogue == null) {
            return error("pinned cube is not available", Response.Status.NOT_FOUND);
        }
        ThinQuery tq;
        try {
            tq = acceptQuery(g, catalogue, body.query);
        } catch (QueryRefused refused) {
            return badRequest("query", refused.getMessage());
        }
        final String path;
        try {
            path = EmbedAuthoringScope.resolve(scope(g), body.name, EmbedAuthoringScope.QUERY_EXT);
        } catch (IllegalArgumentException e) {
            return badRequest("name", "name is not a usable object name");
        }
        // Execute once before persisting: a query that doesn't run has no business
        // in the repository, and the round-trip fills the model with the MDX +
        // measure metadata the workspace expects to find on load.
        final Response result;
        try {
            result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                thinQueryService.createQuery(tq);
                thinQueryService.execute(tq);
                String content;
                try {
                    content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tq);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    return error("failed to serialise the query", Response.Status.INTERNAL_SERVER_ERROR);
                }
                return save(path, content, g, "query");
            });
        } catch (RuntimeException e) {
            log.info("embed authoring save refused for tenant {}: {}", g.tenantId, e.getMessage());
            audit(g, "/saiku/api/embed/authoring/query", AiAuditEntry.OUTCOME_ERROR);
            return badRequest("query", "the query could not be run against the pinned cube");
        }
        audit(g, "/saiku/api/embed/authoring/query", outcomeFor(result.getStatus()));
        return harden(result);
    }

    /* -------------------------- dashboard -------------------------- */

    /** Body for {@link #saveDashboard}: the object name plus the dashboard
     *  document verbatim (its own {@code title} lives inside the document). */
    public static class SaveDashboardBody {
        public String name;
        public JsonNode dashboard;
    }

    /**
     * Save the creator's dashboard as a {@code .saikudash} object inside the
     * tenant's folder. The document is stored as sent (re-pretty-printed) rather
     * than re-serialised through a typed POJO, mirroring
     * {@code DashboardResource}'s saiku#1179 stance: a creator-authored dashboard
     * carries tile fields the back-end never interprets, and dropping them would
     * lose the author's work. Shape validation is deliberately minimal — a
     * {@code layout} object — because the layout grammar belongs to the embed
     * bundle, not to this resource.
     */
    @POST
    @Path("/{connection}/{catalog}/{schema}/{cube}/dashboard")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveDashboard(
            @PathParam("connection") String connection,
            @PathParam("catalog") String catalog,
            @PathParam("schema") String schema,
            @PathParam("cube") String cube,
            SaveDashboardBody body) {
        EmbedGuestDetails g = guard(connection, catalog, schema, cube);
        if (g instanceof Response) {
            return (Response) g;
        }
        if (body == null || body.name == null || body.name.isBlank()) {
            return badRequest("name", "name is required");
        }
        JsonNode dash = body.dashboard;
        if (dash == null || !dash.isObject()) {
            return badRequest("dashboard", "dashboard document is required");
        }
        if (!dash.hasNonNull("layout") || !dash.get("layout").isObject()) {
            return badRequest("dashboard", "dashboard layout is required");
        }
        final String path;
        try {
            path = EmbedAuthoringScope.resolve(scope(g), body.name, EmbedAuthoringScope.DASHBOARD_EXT);
        } catch (IllegalArgumentException e) {
            return badRequest("name", "name is not a usable object name");
        }
        // Pin the cube the dashboard was authored against into the document so a
        // later read can tell which pinned cube it belongs to. Never overwrite an
        // author-supplied value with a different cube than the token's.
        ObjectNodeWithCube stamped = new ObjectNodeWithCube(dash, g);
        Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
            String content;
            try {
                content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(stamped.node);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return error("failed to serialise the dashboard", Response.Status.INTERNAL_SERVER_ERROR);
            }
            return save(path, content, g, "dashboard");
        });
        audit(g, "/saiku/api/embed/authoring/dashboard", outcomeFor(result.getStatus()));
        return harden(result);
    }

    /* ---------------------------- objects ---------------------------- */

    /**
     * The tenant's own saved objects — and only those. The listing is issued
     * against the derived scope folder, so tenant B's JWT enumerates a different
     * folder and never sees tenant A's work, even inside the same owner's home.
     */
    @GET
    @Path("/{connection}/{catalog}/{schema}/{cube}/objects")
    @Produces(MediaType.APPLICATION_JSON)
    public Response objects(
            @PathParam("connection") String connection,
            @PathParam("catalog") String catalog,
            @PathParam("schema") String schema,
            @PathParam("cube") String cube) {
        EmbedGuestDetails g = guard(connection, catalog, schema, cube);
        if (g instanceof Response) {
            return (Response) g;
        }
        String folder = scope(g);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            // No type filter: the listing is already confined to the tenant's
            // own folder, and the extension check below is the authoritative
            // "is this a creator object" test (a filetype taxonomy here would
            // be a second thing to keep in sync).
            List<IRepositoryObject> files = sessionService.runAs(
                    g.ownerUser,
                    g.ownerRoles,
                    () -> datasourceService.getFiles(null, g.ownerUser, g.ownerRoles, folder));

            if (files != null) {
                for (IRepositoryObject f : files) {
                    if (!(f instanceof RepositoryFileObject file) || f.getType() != IRepositoryObject.Type.FILE) {
                        continue;
                    }
                    String name = file.getName();
                    if (!name.endsWith(EmbedAuthoringScope.QUERY_EXT)
                            && !name.endsWith(EmbedAuthoringScope.DASHBOARD_EXT)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("path", folder + "/" + name);
                    row.put(
                            "type",
                            name.endsWith(EmbedAuthoringScope.DASHBOARD_EXT)
                                    ? "dashboard"
                                    : "query");
                    row.put("date", file.getModified());
                    row.put("owner", file.getOwner() == null ? "" : file.getOwner());
                    out.add(row);
                }
            }
        } catch (RuntimeException e) {
            log.warn("embed authoring listing failed for {}: {}", folder, e.getMessage());
            return error("could not list the tenant folder", Response.Status.INTERNAL_SERVER_ERROR);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", g.tenantId);
        body.put("scopePath", folder);
        body.put("objects", out);
        return harden(Response.ok(body).type(MediaType.APPLICATION_JSON).build());
    }

    /** Load one of the tenant's own objects back into the creator. */
    @GET
    @Path("/{connection}/{catalog}/{schema}/{cube}/object")
    @Produces(MediaType.APPLICATION_JSON)
    public Response loadObject(
            @PathParam("connection") String connection,
            @PathParam("catalog") String catalog,
            @PathParam("schema") String schema,
            @PathParam("cube") String cube,
            @QueryParam("name") String name) {
        EmbedGuestDetails g = guard(connection, catalog, schema, cube);
        if (g instanceof Response) {
            return (Response) g;
        }
        String path = resolveExisting(g, name);
        if (path == null) {
            return badRequest("name", "name is not a readable object in your folder");
        }
        String raw = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> datasourceService.getFileData(
                path, g.ownerUser, g.ownerRoles));
        if (raw == null || raw.isBlank()) {
            return error("object is not readable", Response.Status.NOT_FOUND);
        }
        return harden(Response.ok(Map.of("name", name, "path", path, "content", raw))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /** Delete one of the tenant's own objects. Scoped exactly like every other
     *  write here — the name is resolved inside the scope, never taken as a path. */
    @DELETE
    @Path("/{connection}/{catalog}/{schema}/{cube}/object")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteObject(
            @PathParam("connection") String connection,
            @PathParam("catalog") String catalog,
            @PathParam("schema") String schema,
            @PathParam("cube") String cube,
            @QueryParam("name") String name) {
        EmbedGuestDetails g = guard(connection, catalog, schema, cube);
        if (g instanceof Response) {
            return (Response) g;
        }
        String path = resolveExisting(g, name);
        if (path == null) {
            return badRequest("name", "name is not a deletable object in your folder");
        }
        Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
            String resp = datasourceService.removeFile(path, g.ownerUser, g.ownerRoles);
            if (!"Remove Okay".equals(resp)) {
                return error("delete was rejected for this object", Response.Status.INTERNAL_SERVER_ERROR);
            }
            return Response.ok(Map.of("status", "OK", "path", path))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        });
        audit(g, "/saiku/api/embed/authoring/object", outcomeFor(result.getStatus()));
        return harden(result);
    }

    /* ---------------------------- helpers ---------------------------- */

    /**
     * Re-establish the pin at the resource boundary, or return the opaque 401.
     * Returns {@link EmbedGuestDetails} on success and a {@link Response} on
     * refusal — the awkward-but-explicit shape this codebase already uses in
     * {@code EmbedViewResource}'s {@code guest()} checks.
     */
    private Object guard(String connection, String catalog, String schema, String cube) {
        EmbedGuestDetails g = guest();
        if (g == null || !g.isAuthoring() || !EmbedAuthoringScope.isValidTenantId(g.tenantId)) {
            return invalid();
        }
        String pinned = g.resourcePath == null ? null : g.resourcePath.replaceFirst("^/", "");
        String requested = connection + "/" + catalog + "/" + schema + "/" + cube;
        if (pinned == null || !pinned.equals(requested)) {
            // The filter pinned this already; a mismatch here means the filter
            // regressed, and the safe answer to "which cube?" is no cube.
            log.warn("embed authoring cube pin mismatch: pinned={} requested={}", pinned, requested);
            return invalid();
        }
        if (g.ownerUser == null || g.ownerUser.isBlank()) {
            // Every write runs as the owner; with no owner there is no identity
            // to run as and no ACL to satisfy. Fail closed.
            return invalid();
        }
        return g;
    }

    private EmbedGuestDetails guest() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof EmbedGuestDetails d)) {
            return null;
        }
        return d;
    }

    /** The tenant's folder, derived server-side from the token. */
    private String scope(EmbedGuestDetails g) {
        return EmbedAuthoringScope.homeFor(g.ownerUser, g.tenantId);
    }

    /** Resolve a client-supplied object name to a full path inside the scope. */
    private String resolveExisting(EmbedGuestDetails g, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String ext = name.endsWith(EmbedAuthoringScope.DASHBOARD_EXT)
                ? EmbedAuthoringScope.DASHBOARD_EXT
                : EmbedAuthoringScope.QUERY_EXT;
        String bare = name.endsWith(ext) ? name.substring(0, name.length() - ext.length()) : name;
        try {
            String path = EmbedAuthoringScope.resolve(scope(g), bare, ext);
            // resolve() already asserts containment; this is the explicit re-check
            // at the point of use.
            return EmbedAuthoringScope.isWithin(scope(g), path) ? path : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Validate + adopt a client-supplied query: deserialise, pin the cube to the
     * token's, reject anything the catalogue doesn't list, and stamp a server
     * name.
     *
     * @throws QueryRefused when the document is malformed or names anything
     *     outside the pinned cube; the message is safe to echo (it describes the
     *     request, never the cube's contents)
     */
    private ThinQuery acceptQuery(EmbedGuestDetails g, AuthoringCubeCatalogue catalogue, JsonNode node) {
        ThinQuery tq;
        try {
            tq = MAPPER.treeToValue(node, ThinQuery.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new QueryRefused("query is not a well-formed query document");
        }
        AuthoringQueryValidator.Result verdict = AuthoringQueryValidator.validate(catalogue, tq);
        if (!verdict.isValid()) {
            log.info(
                    "embed authoring refused a query from tenant {} sub={}: {}",
                    g.tenantId,
                    String.valueOf(g.jwtSub),
                    verdict.violations());
            throw new QueryRefused(verdict.firstViolation());
        }
        // Server-owned from here: the cube comes from the token, the name is
        // generated, and nothing the client sent survives validation.
        tq.setCube(cubeOf(g));
        tq.setName("embed-creator-" + UUID.randomUUID());
        tq.setType(ThinQuery.Type.QUERYMODEL);
        tq.setMdx(null);
        tq.setParameters(new java.util.HashMap<>());
        tq.setTypedParameters(new ArrayList<>());
        tq.setQueryType("OLAP");
        return tq;
    }

    /** Signals a refused query; the caller turns it into a 400. */
    private static final class QueryRefused extends RuntimeException {
        QueryRefused(String message) {
            super(message);
        }
    }

    private SaikuCube cubeOf(EmbedGuestDetails g) {
        String ref = g.resourcePath == null ? "" : g.resourcePath.replaceFirst("^/", "");
        String[] parts = ref.split("/");
        return new SaikuCube(
                parts.length > 0 ? parts[0] : null,
                parts.length > 3 ? parts[3] : null,
                parts.length > 3 ? parts[3] : null,
                null,
                parts.length > 1 ? parts[1] : null,
                parts.length > 2 ? parts[2] : null);
    }

    /** The pinned cube's frozen catalogue, or null when the cube won't resolve. */
    private AuthoringCubeCatalogue catalogue(
            EmbedGuestDetails g, String connection, String catalog, String schema, String cube) {
        if (olapDiscoverService == null) {
            log.warn("embed authoring has no OlapDiscoverService wired");
            return null;
        }
        String key = connection + "/" + catalog + "/" + schema + "/" + cube;
        try {
            return catalogueCache.get(key, k -> {
                SaikuCube c =
                        new SaikuCube(connection, cube, cube, null, catalog.isEmpty() ? null : catalog, schema);
                return AuthoringCubeCatalogue.fromSaikuCube(c, olapDiscoverService);
            });
        } catch (RuntimeException e) {
            log.warn("embed authoring could not resolve pinned cube {}: {}", key, e.getMessage());
            return null;
        }
    }

    /** Persist {@code content} at {@code path} under the owner's identity. */
    private Response save(String path, String content, EmbedGuestDetails g, String what) {
        String resp = datasourceService.saveFile(content, path, g.ownerUser, g.ownerRoles);
        if (!SAVE_OK.equals(resp)) {
            log.warn("embed authoring {} save rejected at {} (tenant={})", what, path, g.tenantId);
            return error("save was rejected for this object", Response.Status.INTERNAL_SERVER_ERROR);
        }
        log.info(
                "embed authoring saved {} at {} (tenant={}, sub={})",
                what,
                path,
                g.tenantId,
                String.valueOf(g.jwtSub));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("path", path);
        body.put("type", what);
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

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

    private static Response invalid() {
        return harden(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("status", "EMBED_INVALID", "error", "Embed link is invalid or expired."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private static Response badRequest(String field, String message) {
        return harden(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "field", field, "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private static Response error(String message, Response.Status status) {
        return harden(Response.status(status)
                .entity(Map.of("status", "ERROR", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /**
     * Response headers on every authoring reply. Same posture as the read
     * surface (saiku#906/#1104): the body is rendered into a third-party page,
     * so {@code nosniff} blocks a stored-XSS via a JSON field carrying markup,
     * {@code no-store} keeps business data out of shared caches, and
     * {@code no-referrer} keeps the tenant's token out of outbound referers.
     */
    private static Response harden(Response r) {
        return Response.fromResponse(r)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store, max-age=0")
                .build();
    }

    /**
     * Stamps the token's cube onto a creator dashboard document so a later read
     * can attribute it, without ever letting an author-supplied
     * {@code embedCreator.cube} disagree with the token (a mismatch would be a
     * lie in the audit trail). Kept as a tiny holder so the mutation is visible
     * at the call site.
     */
    private static final class ObjectNodeWithCube {
        private final JsonNode node;

        ObjectNodeWithCube(JsonNode source, EmbedGuestDetails g) {
            JsonNode copy = source.deepCopy();
            if (copy instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                com.fasterxml.jackson.databind.node.ObjectNode stamp = obj.with("embedCreator");
                stamp.put("tenant", g.tenantId);
                stamp.put("cube", g.resourcePath == null ? "" : g.resourcePath.replaceFirst("^/", ""));
                stamp.put("sub", g.jwtSub == null ? "" : g.jwtSub);
            }
            this.node = copy;
        }
    }
}
