/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.olap4j.CellSet;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.util.OlapResultSetUtil;
import org.saiku.service.async.AsyncQueryHandle;
import org.saiku.service.async.AsyncQueryService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiQueryMetadata;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiReturnsResolver;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.AiValidationException;
import org.saiku.service.olap.ai.OlapAiCubeMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Phase-1 AI-friendly query endpoint. Agent posts a typed
 * {@link AiQueryRequest}; the converter validates names against the live
 * schema, builds an MDX-mode ThinQuery, and ThinQueryService executes it.
 * Agents never see MDX directly (the generated MDX is echoed back in
 * {@code metadata.generatedMdx} for human debugging, but normal agent
 * flows ignore it).
 */
@Path("/saiku/api/ai")
public class AiQueryResource {

    private static final Logger log = LoggerFactory.getLogger(AiQueryResource.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ThinQueryService thinQueryService;
    private AiCubeMetadataService cubeMetadataService;
    private AsyncQueryService asyncQueryService;
    /** Wired for the saved-query resolver (POST /query/saved). The
     *  resolver loads a .saiku file from the JCR, deserialises it to a
     *  ThinQuery, executes it, and returns the same AiQueryResponse
     *  shape inline tiles already render. */
    private org.saiku.service.datasource.DatasourceService datasourceService;

    private org.saiku.web.service.SessionService sessionService;
    private final AiSchemaConverter converter = new AiSchemaConverter();
    /** Phase 2: JSON-Schema-driven shape validator. Runs first so an
     *  agent gets one structured 400 per shape failure instead of a
     *  cascading wall of converter-layer errors. Same schema doc embedded
     *  in /schema/{cubeId}.requestSchema for client-side use, so the
     *  server-side and client-side rules can't drift. */
    private final org.saiku.service.olap.ai.AiRequestSchemaValidator schemaValidator =
            new org.saiku.service.olap.ai.AiRequestSchemaValidator();

    public void setThinQueryService(ThinQueryService tqs) {
        this.thinQueryService = tqs;
    }

    public void setCubeMetadataService(AiCubeMetadataService svc) {
        this.cubeMetadataService = svc;
    }

    public void setAsyncQueryService(AsyncQueryService a) {
        this.asyncQueryService = a;
    }

    public void setDatasourceService(org.saiku.service.datasource.DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(org.saiku.web.service.SessionService s) {
        this.sessionService = s;
    }

    /**
     * Resolve a saved-query reference (a {@code .saiku} file in the JCR
     * repository) to a runnable {@link ThinQuery}, execute it, and
     * return the result in the same {@link AiQueryResponse} shape
     * inline-query tiles already render.
     *
     * <p>Body: {@code {"path": "homes/smith/foo.saiku"}}. The path
     * matches the storage key {@code BasicRepositoryResource2} uses;
     * permissions inherit from the JCR.
     *
     * <p>Used by the dashboard layer to support
     * {@code TileQuery.kind == "reference"} — see
     * {@code docs/plans/2026-05-16-dashboards-design.md}. Saved
     * queries are stored as serialised {@link ThinQuery} JSON; this
     * endpoint is the bridge to the AI response surface.
     */
    @POST
    @Path("/query/saved")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeSaved(org.saiku.service.olap.ai.AiSavedQueryRequest body) {
        if (datasourceService == null || sessionService == null) {
            return error("saved-query resolver requires repository wiring");
        }
        String path = body == null ? null : body.getPath();
        if (path == null || path.isBlank()) {
            return badRequest("path", "path required (repository location of the .saiku file)", null);
        }
        long start = System.currentTimeMillis();
        String username =
                java.util.Objects.toString(sessionService.getAllSessionObjects().get("username"), null);
        @SuppressWarnings("unchecked")
        java.util.List<String> roles =
                (java.util.List<String>) sessionService.getAllSessionObjects().get("roles");
        String raw;
        try {
            raw = datasourceService.getFileData(path, username, roles);
        } catch (RuntimeException e) {
            log.warn("saved-query load failed for {} (user={})", path, username, e);
            return badRequest("path", "Saved query not found or not readable: " + path, null);
        }
        if (raw == null || raw.isEmpty()) {
            return badRequest("path", "Saved query is empty or missing: " + path, null);
        }
        ThinQuery tq;
        try {
            tq = MAPPER.readValue(raw, ThinQuery.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("saved-query {} is not a valid ThinQuery JSON", path, e);
            return badRequest("path", "Saved query is not valid ThinQuery JSON: " + e.getMessage(), null);
        }
        // Saved queries written by older builds stored the file path in
        // tq.name, which contains slashes. Jetty 12's strict URI rules
        // reject %2F in path segments — clobber any unsafe name with a
        // fresh UUID before handing the query to the executor (mirrors
        // saiku-ui's withSafeName helper).
        if (tq.getName() == null || tq.getName().contains("/")) {
            tq.setName(java.util.UUID.randomUUID().toString());
        }

        // Merge dashboard runtime filters onto the loaded ThinQuery before
        // execution. Skipped silently when the request carries no filters
        // (the historical path), when the query is MDX-mode (can't splice
        // safely), or when the cube schema can't be loaded. See
        // ThinQueryFilterMerge for the precedence + axis-rewrite rules.
        if (body.getFilters() != null
                && !body.getFilters().isEmpty()
                && tq.getType() == ThinQuery.Type.QUERYMODEL
                && tq.getCube() != null
                && cubeMetadataService != null) {
            try {
                org.saiku.olap.dto.SaikuCube cube = tq.getCube();
                AiCubeRef ref =
                        new AiCubeRef(cube.getConnection(), cube.getCatalog(), cube.getSchema(), cube.getName());
                AiSchema schema = cubeMetadataService.getSchema(ref);
                org.saiku.service.olap.ai.ThinQueryFilterMerge.apply(tq, body.getFilters(), schema);
            } catch (RuntimeException ve) {
                // Schema lookup failures shouldn't poison the saved-query
                // execution path — fall through with the un-merged query
                // and let it run as-authored.
                log.warn("Dashboard filter merge skipped for saved query {}: {}", path, ve.getMessage());
            }
        }

        CellDataSet cds;
        try {
            cds = thinQueryService.execute(tq);
        } catch (RuntimeException e) {
            log.error("saved-query execution failed for {}", path, e);
            return error("execute failed: " + describeDeepestCause(e));
        }
        AiQueryResponse resp = buildResponse(tq, cds, start, "records");
        return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeAi(AiQueryRequest req, @QueryParam("format") @DefaultValue("records") String format) {
        long start = System.currentTimeMillis();
        AiSchema schema;
        ThinQuery tq;
        try {
            if (req == null) {
                return badRequest("body", "request body required", null);
            }
            // Phase 2: JSON Schema shape check before any other validation.
            // Catches missing required fields / shape errors with a
            // structured envelope; the converter's domain-resolution layer
            // still runs for resolved-name errors (missing measure, etc.).
            schemaValidator.assertValid(MAPPER.valueToTree(req));
            if (req.getCube() == null) {
                return badRequest("cube", "cube ref required", null);
            }
            schema = cubeMetadataService.getSchema(req.getCube());
            tq = converter.convert(req, schema);
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI query validation failed", e);
            return error("validation failed: " + e.getMessage());
        }

        CellDataSet cds;
        try {
            cds = thinQueryService.execute(tq);
        } catch (RuntimeException e) {
            // Translate well-known Mondrian "MDX object not found" errors into
            // a clean 400 VALIDATION_ERROR pointing at the offending member.
            // Without this, agents that supply a member ref the schema accepts
            // shape-wise but Mondrian can't resolve (typo, removed product,
            // wrong hierarchy depth) get an opaque 500 with no actionable field.
            Response translated = translateMondrianLookupError(e);
            if (translated != null) return translated;
            // Detect the "dimension has no resolvable physical path to the
            // cube's fact table" NPE pattern (saiku#808). Walk the cause
            // chain — the wrapper ThinQueryService throws is opaque
            // ("Can't execute query: <uuid>") and the deepest cause is the
            // useful one.
            Response pathErr = translatePhysPathNpe(e);
            if (pathErr != null) return pathErr;
            log.error("AI query execution failed", e);
            return error("execute failed: " + describeDeepestCause(e));
        }

        // saiku#915 / saiku-cloud MCP smoke-test 2026-05-23: a follow-up
        // drillthrough call lands on a different session than this sync
        // execute (very common via MCP, which gets a fresh JSESSIONID per
        // request when the client doesn't keep a cookie jar). The
        // session-scoped ThinQueryService context that holds the
        // (ThinQuery, CellSet) pair is empty on the new session and
        // drillthrough's resolver path surfaces "Unknown queryId" even
        // though the result is still hot.
        //
        // Mirror the async submission path: register a pre-completed
        // AsyncQueryHandle keyed on the ThinQuery name. The drillthrough
        // resource's existing fallback already calls
        // asyncQueryService.get(queryId) and re-attaches via
        // ThinQueryService.registerExternalContext — so sync queries get
        // the same cross-session reachability for free.
        if (asyncQueryService != null && tq.getName() != null) {
            try {
                org.olap4j.CellSet cellSet =
                        thinQueryService.getContext(tq.getName()).getOlapResult();
                org.saiku.service.async.AsyncQueryHandle handle =
                        new org.saiku.service.async.AsyncQueryHandle(tq.getName(), tq);
                handle.setFuture(java.util.concurrent.CompletableFuture.completedFuture(cellSet));
                handle.compareAndSetStatus(
                        org.saiku.service.async.AsyncQueryHandle.Status.PENDING,
                        org.saiku.service.async.AsyncQueryHandle.Status.DONE);
                asyncQueryService.register(handle);
            } catch (RuntimeException registerErr) {
                // Never fail the query response over the registration —
                // drillthrough simply won't be reachable from a different
                // session, which is the pre-fix behaviour.
                log.debug("AI sync handle registration failed for {}: {}", tq.getName(), registerErr.toString());
            }
        }

        AiQueryResponse resp = buildResponse(tq, cds, start, format);
        return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
    }

    /** Mondrian's parser raises "MDX object '<ref>' not found in cube '<name>'"
     *  whenever an axis or slicer references a member that doesn't exist. We
     *  scan the exception chain for that message and lift it to a 400 with the
     *  offending ref in the {@code error} body. Returns null if the throwable
     *  isn't a lookup failure. */
    private static final java.util.regex.Pattern MDX_NOT_FOUND_PATTERN =
            java.util.regex.Pattern.compile("MDX object '([^']+)' not found in cube '([^']+)'");

    private Response translateMondrianLookupError(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg == null) continue;
            java.util.regex.Matcher m = MDX_NOT_FOUND_PATTERN.matcher(msg);
            if (m.find()) {
                AiQueryResponse resp = new AiQueryResponse();
                resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
                resp.setError("Member '" + m.group(1) + "' not found in cube '" + m.group(2)
                        + "'. Check the member ref or call /members/search to discover valid members.");
                resp.setField("members");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(resp)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
        }
        return null;
    }

    /** saiku#808: Mondrian's RolapSchema$PhysPath.getLinks() NPEs when an
     *  axis dim/hier has no resolvable join path to the cube's fact table.
     *  The wrapper exception is opaque, so we walk the cause chain to find
     *  the underlying NPE and translate it to a structured 400 telling the
     *  agent which dimension is unwirable in this cube. */
    private Response translatePhysPathNpe(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg == null) continue;
            if (cur instanceof NullPointerException
                    && msg.contains("RolapSchema$PhysPath.getLinks()")
                    && msg.contains("path")
                    && msg.contains("is null")) {
                AiQueryResponse resp = new AiQueryResponse();
                resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
                resp.setError("This cube's schema has no resolvable join path "
                        + "between the fact table and one of the dimensions on "
                        + "the request axis. Mondrian raised "
                        + "RolapSchema$PhysPath.getLinks() NPE. Pick a different "
                        + "dimension that is wired into this cube — call "
                        + "/schema/{cubeId} to see which dimensions are wired.");
                resp.setField("rows");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(resp)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
        }
        return null;
    }

    /** Walk the cause chain to the deepest exception with a non-null
     *  message; return "ClassName: message". The Spring/JAX-RS wrappers
     *  ({@code SaikuServiceException}, {@code OlapException},
     *  {@code MondrianException}) all flatten to the same useless "Can't
     *  execute query: <uuid>" surface; the real signal is at the leaf. */
    private static String describeDeepestCause(Throwable t) {
        Throwable deepest = t;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) {
                deepest = cur;
            }
        }
        if (deepest == t) return t.getMessage();
        return deepest.getClass().getSimpleName() + ": " + deepest.getMessage();
    }

    /**
     * Preview-only: run the converter to produce MDX without executing.
     * Useful for cost estimation, audit logs, and "show the user what's
     * about to run" UX.
     */
    @POST
    @Path("/query/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response previewAi(AiQueryRequest req) {
        try {
            if (req == null) {
                return badRequest("body", "request body required", null);
            }
            // Phase 2: shape validation first, same as executeAi.
            schemaValidator.assertValid(MAPPER.valueToTree(req));
            if (req.getCube() == null) {
                return badRequest("cube", "cube ref required", null);
            }
            AiSchema schema = cubeMetadataService.getSchema(req.getCube());
            ThinQuery tq = converter.convert(req, schema);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("queryId", tq.getName());
            body.put("status", "PREVIEW");
            body.put("generatedMdx", tq.getMdx());
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI query preview failed", e);
            return error("preview failed: " + e.getMessage());
        }
    }

    /**
     * Phase 2: list available cubes the agent may query. Requires
     * {@link OlapAiCubeMetadataService} on the injected metadata service
     * (the interface alone is not enough). Returns {@code 503} otherwise.
     */
    @GET
    @Path("/cubes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCubes() {
        if (!(cubeMetadataService instanceof OlapAiCubeMetadataService)) {
            return error("Cube listing requires an olap4j-backed metadata service");
        }
        try {
            List<AiCubeSummary> cubes = ((OlapAiCubeMetadataService) cubeMetadataService).listCubes();
            return Response.ok(cubes).type(MediaType.APPLICATION_JSON).build();
        } catch (RuntimeException e) {
            log.error("AI cube listing failed", e);
            return error("listing failed: " + e.getMessage());
        }
    }

    /**
     * Phase 2: typed schema for a single cube. {@code cubeId} format:
     * {@code connection/catalog/schema/cube}. URL-encoded path segments
     * are decoded by JAX-RS before reaching this method.
     */
    @GET
    @Path("/schema/{cubeId:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSchema(@PathParam("cubeId") String cubeId) {
        AiCubeRef ref = parseCubeId(cubeId);
        if (ref == null) {
            return badRequest("cubeId", "cubeId must be connection/catalog/schema/cube", null);
        }
        try {
            AiSchema schema = cubeMetadataService.getSchema(ref);
            return Response.ok(schema).type(MediaType.APPLICATION_JSON).build();
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI schema fetch failed for {}", cubeId, e);
            return error("schema fetch failed: " + e.getMessage());
        }
    }

    /**
     * Member search for a level — case-insensitive substring match by
     * default (delegates to the discover service's olap4j search). The
     * {@code cubeId} format matches {@link #getSchema} —
     * {@code connection/catalog/schema/cube}. Returns up to {@code limit}
     * hits (default 20).
     */
    @GET
    @Path("/members/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchMembers(
            @QueryParam("cubeId") String cubeId,
            @QueryParam("dimension") String dimension,
            @QueryParam("hierarchy") String hierarchy,
            @QueryParam("level") String level,
            @QueryParam("q") String q,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        AiCubeRef ref = parseCubeId(cubeId);
        if (ref == null) {
            return badRequest("cubeId", "cubeId must be connection/catalog/schema/cube", null);
        }
        if (dimension == null || dimension.isEmpty() || level == null || level.isEmpty()) {
            return badRequest("dimension/level", "dimension and level query params required", null);
        }
        if (!(cubeMetadataService instanceof OlapAiCubeMetadataService)) {
            return error("Member search requires an olap4j-backed metadata service");
        }
        try {
            List<?> hits = ((OlapAiCubeMetadataService) cubeMetadataService)
                    .searchMembers(ref, dimension, hierarchy, level, q, limit);
            return Response.ok(hits).type(MediaType.APPLICATION_JSON).build();
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI member search failed for {}/{}/{} q={}", dimension, hierarchy, level, q, e);
            return error("member search failed: " + e.getMessage());
        }
    }

    /** Parse a "connection/catalog/schema/cube" cubeId. Returns null on a malformed input. */
    static AiCubeRef parseCubeId(String cubeId) {
        if (cubeId == null || cubeId.isEmpty()) return null;
        String[] parts = cubeId.split("/", -1);
        if (parts.length != 4) return null;
        for (String p : parts) if (p.isEmpty()) return null;
        return new AiCubeRef(parts[0], parts[1], parts[2], parts[3]);
    }

    /* ------------------------- Phase 4: async --------------------------- */

    /**
     * Submit an AI query for async execution. Validation still happens
     * synchronously so 400s come back immediately — execution alone is
     * handed off to {@link AsyncQueryService}.
     */
    @POST
    @Path("/query/execute-async")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeAiAsync(AiQueryRequest req) {
        if (asyncQueryService == null) {
            return error("Async service not configured");
        }
        AiSchema schema;
        ThinQuery tq;
        try {
            if (req == null || req.getCube() == null) {
                return badRequest("cube", "cube ref required", null);
            }
            schema = cubeMetadataService.getSchema(req.getCube());
            tq = converter.convert(req, schema);
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI async submit validation failed", e);
            return error("validation failed: " + e.getMessage());
        }
        AsyncQueryHandle handle;
        try {
            // Propagate the caller's RequestAttributes so the worker thread
            // can resolve the session-scoped ThinQueryService proxy. Without
            // this, "Scope 'session' is not active for the current thread"
            // is thrown the moment the worker touches thinQueryService.
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            handle = asyncQueryService.submit(tq, attrs);
        } catch (RuntimeException e) {
            log.error("AI async submit failed", e);
            return error("submit failed: " + e.getMessage());
        }
        AiQueryResponse resp = new AiQueryResponse();
        resp.setQueryId(handle.getId());
        resp.setStatus(AiQueryResponse.Status.SUCCESS);
        AiQueryMetadata meta = new AiQueryMetadata();
        meta.setGeneratedMdx(tq.getMdx());
        resp.setMetadata(meta);
        return Response.status(Response.Status.ACCEPTED).entity(resp).build();
    }

    /**
     * Poll the status of a submitted query. Returns
     * {@code {queryId, status}} where status maps to AsyncQueryHandle's
     * states (PENDING / RUNNING / DONE / FAILED / CANCELLED).
     */
    @GET
    @Path("/query/status/{queryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response asyncStatus(@PathParam("queryId") String queryId) {
        if (asyncQueryService == null) return error("Async service not configured");
        AsyncQueryHandle h = asyncQueryService.get(queryId);
        if (h == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        body.put("status", h.getStatus().name());
        if (h.getErrorMessage() != null) body.put("error", h.getErrorMessage());
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Fetch the materialised result for a completed async query. Returns
     * 404 if unknown, 202 if still running, 200 with the full
     * AiQueryResponse if done, 400/500 on error.
     */
    @GET
    @Path("/query/result/{queryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response asyncResult(@PathParam("queryId") String queryId) {
        if (asyncQueryService == null) return error("Async service not configured");
        AsyncQueryHandle h = asyncQueryService.get(queryId);
        if (h == null) return Response.status(Response.Status.NOT_FOUND).build();
        switch (h.getStatus()) {
            case PENDING:
            case RUNNING:
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("queryId", queryId);
                pending.put("status", h.getStatus().name());
                return Response.status(Response.Status.ACCEPTED).entity(pending).build();
            case FAILED:
                AiQueryResponse fail = new AiQueryResponse();
                fail.setQueryId(queryId);
                fail.setStatus(AiQueryResponse.Status.EXECUTION_ERROR);
                fail.setError(h.getErrorMessage());
                return Response.ok(fail).build();
            case CANCELLED:
                AiQueryResponse cancelled = new AiQueryResponse();
                cancelled.setQueryId(queryId);
                cancelled.setStatus(AiQueryResponse.Status.EXECUTION_ERROR);
                cancelled.setError("cancelled");
                return Response.ok(cancelled).build();
            case DONE:
                CellSet cs = asyncQueryService.result(queryId);
                CellDataSet cds = cs == null ? null : OlapResultSetUtil.cellSet2Matrix(cs);
                AiQueryResponse done = buildResponse(h.getQuery(), cds, h.getSubmittedAt());
                done.setQueryId(queryId);
                return Response.ok(done).build();
            default:
                return error("unknown async status: " + h.getStatus());
        }
    }

    /**
     * Cancel a running async query. Best-effort — cooperates with
     * {@link ThinQueryService#cancel(String)} which calls
     * {@code OlapStatement.cancel()} on the live Mondrian statement.
     */
    @DELETE
    @Path("/query/{queryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response asyncCancel(@PathParam("queryId") String queryId) {
        if (asyncQueryService == null) return error("Async service not configured");
        // saiku#792: probe the current state BEFORE attempting cancel so an
        // already-finished query reports ALREADY_COMPLETED / ALREADY_FAILED
        // instead of a misleading CANCELLED. Idempotent retries on an
        // already-cancelled handle still return CANCELLED (so the response
        // shape stays stable for "I asked twice").
        AsyncQueryHandle h = asyncQueryService.get(queryId);
        if (h == null) return Response.status(Response.Status.NOT_FOUND).build();
        AsyncQueryHandle.Status before = h.getStatus();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        if (before == AsyncQueryHandle.Status.DONE) {
            body.put("status", "ALREADY_COMPLETED");
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        }
        if (before == AsyncQueryHandle.Status.FAILED) {
            body.put("status", "ALREADY_FAILED");
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        }
        if (before == AsyncQueryHandle.Status.CANCELLED) {
            body.put("status", "CANCELLED");
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        }
        boolean ok = asyncQueryService.cancel(queryId);
        if (!ok) return Response.status(Response.Status.NOT_FOUND).build();
        body.put("status", "CANCELLED");
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /* ----------------------- Phase 4: drillthrough ---------------------- */

    /**
     * Drill into an earlier result's underlying fact rows. Two modes:
     *
     * <ul>
     *   <li><b>Whole-result</b> (no {@code position}): drills the result as a
     *       whole, via {@link ThinQueryService#drillthrough(String, int, Integer, String)}.
     *       Optional {@code firstRowset} bound applies here.</li>
     *   <li><b>Per-cell</b> ({@code position=row:col}): drills the single cell at
     *       that cellset coordinate, via
     *       {@link ThinQueryService#drillthrough(String, java.util.List, Integer, String)}
     *       — the same path the workspace ({@code Query2Resource}) uses. This is
     *       what dashboard cell-click drillthrough (saiku#930) calls.</li>
     * </ul>
     *
     * <p>The {@code queryId} for an AI query is either the sync ThinQuery name
     * (returned in {@code AiQueryResponse.queryId}) or the async handle's
     * underlying ThinQuery name.
     */
    @GET
    @Path("/query/{queryId}/drillthrough")
    @Produces(MediaType.APPLICATION_JSON)
    public Response drillthrough(
            @PathParam("queryId") String queryId,
            @QueryParam("maxrows") @DefaultValue("100") int maxrows,
            @QueryParam("firstRowset") Integer firstRowset,
            @QueryParam("position") String position,
            @QueryParam("returns") String returns) {
        if (thinQueryService == null) return error("Query service not configured");
        // For async queries the handle id != the underlying ThinQuery name.
        // Resolve through the handle when possible.
        String name = queryId;
        if (asyncQueryService != null) {
            AsyncQueryHandle h = asyncQueryService.get(queryId);
            if (h != null) {
                name = h.getQuery().getName();
                // saiku#862: re-attach the async query's ThinQuery + CellSet
                // to THIS request's session-scoped ThinQueryService context.
                // Without this, a drillthrough request that lands on a
                // different session than the async submit (e.g. Basic-auth
                // clients without a shared cookie jar; some load-balanced
                // setups) hits an empty context map and the NPE-catch path
                // surfaces "Unknown queryId" even though the handle is live.
                if (thinQueryService.getContext(name) == null) {
                    org.olap4j.CellSet cs = asyncQueryService.result(queryId);
                    thinQueryService.registerExternalContext(h.getQuery(), cs);
                }
            }
        }
        // saiku#782: agents only have bare captions (the keys of each row in
        // a drillthrough response) — accept those and rewrite to the fully
        // qualified MDX form Mondrian's RETURN clause expects. Tokens that
        // are already bracketed pass through unchanged, so callers that
        // already speak MDX aren't surprised. Validation errors from the
        // resolver carry the candidate list so the agent can self-correct
        // without scraping /schema.
        String resolvedReturns = returns;
        if (returns != null && !returns.trim().isEmpty() && cubeMetadataService != null) {
            try {
                org.saiku.service.util.QueryContext qc = thinQueryService.getContext(name);
                if (qc != null && qc.getOlapQuery() != null && qc.getOlapQuery().getCube() != null) {
                    org.saiku.olap.dto.SaikuCube cube = qc.getOlapQuery().getCube();
                    AiCubeRef ref =
                            new AiCubeRef(cube.getConnection(), cube.getCatalog(), cube.getSchema(), cube.getName());
                    AiSchema schema = cubeMetadataService.getSchema(ref);
                    resolvedReturns = AiReturnsResolver.resolve(returns, schema);
                }
            } catch (AiValidationException ve) {
                AiQueryResponse resp = new AiQueryResponse();
                resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
                resp.setError(ve.getMessage());
                resp.setField(ve.getField());
                if (ve.getAvailable() != null) resp.setAvailable(ve.getAvailable());
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(resp)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            } catch (RuntimeException ignored) {
                // Schema lookup failure shouldn't poison the drillthrough —
                // fall through with the raw returns value and let Mondrian
                // report whatever it reports. The 400-translation block
                // below handles the typical "in RETURN clause" message.
            }
        }
        // saiku#930: per-cell drillthrough. position is "row:col" cellset
        // coordinates (same form Query2Resource accepts). Parse + validate
        // before touching the engine so a malformed value is a clean 400.
        List<Integer> cellPosition = null;
        if (position != null && !position.trim().isEmpty()) {
            cellPosition = new ArrayList<>();
            for (String p : position.split(":")) {
                try {
                    cellPosition.add(Integer.parseInt(p.trim()));
                } catch (NumberFormatException nfe) {
                    AiQueryResponse resp = new AiQueryResponse();
                    resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
                    resp.setError("Malformed position '" + position
                            + "'. Expected \"row:col\" cell coordinates, e.g. \"2:1\".");
                    resp.setField("position");
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(resp)
                            .type(MediaType.APPLICATION_JSON)
                            .build();
                }
            }
        }
        try {
            java.sql.ResultSet rs = cellPosition != null
                    ? thinQueryService.drillthrough(name, cellPosition, maxrows, resolvedReturns)
                    : thinQueryService.drillthrough(name, maxrows, firstRowset, resolvedReturns);
            List<Map<String, AiCell>> rows = new ArrayList<>();
            List<String> columnLabels = new ArrayList<>();
            if (rs != null) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                // saiku#800: Mondrian's drillthrough sometimes returns the same
                // getColumnLabel() for multiple columns within a hierarchy
                // (e.g. Year + Quarter + Month all labelled "Quarter"). Pre-
                // compute disambiguated keys ONCE before the row loop so:
                //   * the first occurrence keeps its raw label (back-compat)
                //   * subsequent duplicates fall back to getColumnName(c) when
                //     that differs, else to a positional suffix _2, _3, ...
                // Without this, every row's Map.put silently overwrote the
                // earlier column, leaving the first column labelled with the
                // LAST column's value — silent data corruption.
                columnLabels = disambiguateColumnLabels(md);
                while (rs.next()) {
                    Map<String, AiCell> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) {
                        Object v = rs.getObject(c);
                        row.put(columnLabels.get(c - 1), toCellFromObject(v));
                    }
                    rows.add(row);
                }
                rs.close();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("queryId", queryId);
            body.put("rowCount", rows.size());
            // saiku#800: explicit columns[] so agents can read by position
            // when the label heuristic still leaves ambiguity.
            body.put("columns", columnLabels);
            body.put("rows", rows);
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (NullPointerException e) {
            // ThinQueryService.drillthrough dereferences the internal
            // QueryContext map without a present-check; an unknown queryId
            // leaks an NPE with internal class names. Catch and translate to
            // a clean 404 with the typed AiQueryResponse envelope (saiku#783).
            log.warn("AI drillthrough on unknown queryId {} — translated to 404", queryId);
            AiQueryResponse resp = new AiQueryResponse();
            resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
            resp.setError("Unknown queryId '" + queryId
                    + "'. The queryId must come from a previous /query or "
                    + "/query/execute-async response and must not have been evicted.");
            resp.setField("queryId");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(resp)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            // Drillthrough defaults to the (0,0) cell — when the source
            // cellset is empty (e.g. NON EMPTY filtered everything out),
            // Mondrian throws "Cell coordinates (0, 0) fall outside CellSet
            // bounds (0, 0)". Surface that as an empty drillthrough (200 +
            // rowCount=0) rather than a generic 500 (saiku#794).
            String m = e.getMessage();
            if (m != null && m.contains("fall outside CellSet bounds")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("queryId", queryId);
                body.put("rowCount", 0);
                body.put("rows", new ArrayList<>());
                return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
            }
            // A bad `returns=` query param surfaces as
            // "Can't perform drillthrough operation on unknown member
            // '<ref>' in RETURN clause" — Mondrian-friendly text. Lift to a
            // 400 with the typed envelope pointing at the offending param
            // (saiku#795).
            if (m != null && m.contains("in RETURN clause")) {
                AiQueryResponse resp = new AiQueryResponse();
                resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
                resp.setError(m.replaceFirst("^.*Mondrian Error:", "").trim());
                resp.setField("returns");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(resp)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            log.error("AI drillthrough failed for {}", queryId, e);
            return error("drillthrough failed: " + e.getMessage());
        }
    }

    /**
     * Column discovery for drillthrough (saiku#774). Returns the list of
     * drillthrough columns available for a previously-executed query so
     * the agent / UI can populate a {@code returns=} clause without
     * trial-and-error.
     *
     * <p>Response shape:
     * <pre>{@code
     *   { "queryId": "...", "columns": [ { "name": "[Time].[Time].[Year]", "type": "VARCHAR" }, ... ] }
     * }</pre>
     *
     * <p>The {@code name} values are the MDX-qualified labels the
     * downstream {@code DRILLTHROUGH ... RETURN ...} clause accepts.
     */
    @GET
    @Path("/query/{queryId}/drillthrough/columns")
    @Produces(MediaType.APPLICATION_JSON)
    public Response drillthroughColumns(@PathParam("queryId") String queryId) {
        if (thinQueryService == null) return error("Query service not configured");
        String name = queryId;
        if (asyncQueryService != null) {
            AsyncQueryHandle h = asyncQueryService.get(queryId);
            if (h != null) {
                name = h.getQuery().getName();
                // saiku#862: re-attach the async result's cellset to this
                // request's session-scoped ThinQueryService context so
                // column discovery can resolve. See drillthrough() above.
                if (thinQueryService.getContext(name) == null) {
                    org.olap4j.CellSet cs = asyncQueryService.result(queryId);
                    thinQueryService.registerExternalContext(h.getQuery(), cs);
                }
            }
        }
        try {
            List<Map<String, String>> cols = thinQueryService.drillthroughColumns(name);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("queryId", queryId);
            body.put("columns", cols);
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (NullPointerException e) {
            // Same translation path as drillthrough above — unknown queryId
            // leaks an NPE on the internal QueryContext lookup.
            log.warn("AI drillthrough column discovery on unknown queryId {} — translated to 404", queryId);
            AiQueryResponse resp = new AiQueryResponse();
            resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
            resp.setError("Unknown queryId '" + queryId
                    + "'. The queryId must come from a previous /query or "
                    + "/query/execute-async response and must not have been evicted.");
            resp.setField("queryId");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(resp)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            String m = e.getMessage();
            if (m != null && m.contains("fall outside CellSet bounds")) {
                // Empty source cellset — no columns to discover. Return an
                // empty list so the agent can decide whether to proceed.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("queryId", queryId);
                body.put("columns", new ArrayList<>());
                return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
            }
            log.error("AI drillthrough column discovery failed for {}", queryId, e);
            return error("drillthrough column discovery failed: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */

    private AiQueryResponse buildResponse(ThinQuery tq, CellDataSet cds, long startedAt) {
        return buildResponse(tq, cds, startedAt, "records");
    }

    private AiQueryResponse buildResponse(ThinQuery tq, CellDataSet cds, long startedAt, String format) {
        boolean useMatrix = "matrix".equalsIgnoreCase(format);
        AiQueryResponse resp = new AiQueryResponse();
        resp.setQueryId(tq.getName());
        resp.setStatus(AiQueryResponse.Status.SUCCESS);
        resp.setFormat(useMatrix ? "matrix" : "records");

        AiQueryMetadata meta = new AiQueryMetadata();
        meta.setGeneratedMdx(tq.getMdx());
        meta.setFreshness(new AiQueryMetadata.Freshness(System.currentTimeMillis(), false));
        resp.setMetadata(meta);

        if (cds != null) {
            AbstractBaseCell[][] headers = cds.getCellSetHeaders();
            AbstractBaseCell[][] body = cds.getCellSetBody();
            int totalWidth = body != null && body.length > 0 ? body[0].length : 0;
            int rowHeaderCount = countRowHeaderColumns(body);

            // Column captions: walk ALL header rows and join each column's
            // segment with " | ", filling down spanning captions (olap4j leaves
            // the cells beyond the first one of a span empty). The previous
            // "last row only" path collapsed multi-axis columns (e.g.
            // measures × quarters) into ambiguous "Q1 Q2 Q3 Q4 Q1 Q2 Q3 Q4" —
            // colliding keys in records format silently dropped the first
            // measure's cells (saiku#789).
            List<AiQueryMetadata.Caption> cols = new ArrayList<>();
            if (headers != null && headers.length > 0) {
                int colCount = headers[headers.length - 1].length;
                // Pre-compute fill-down per header row.
                String[][] filled = new String[headers.length][colCount];
                for (int hRow = 0; hRow < headers.length; hRow++) {
                    String last = "";
                    for (int c = 0; c < colCount; c++) {
                        if (headers[hRow] == null || c >= headers[hRow].length) {
                            filled[hRow][c] = "";
                            continue;
                        }
                        String segment = headers[hRow][c] == null ? "" : safe(headers[hRow][c].getFormattedValue());
                        if (!segment.isEmpty()) last = segment;
                        filled[hRow][c] = last;
                    }
                }
                for (int c = rowHeaderCount; c < colCount; c++) {
                    StringBuilder cap = new StringBuilder();
                    for (int hRow = 0; hRow < headers.length; hRow++) {
                        String segment = filled[hRow][c];
                        if (segment == null || segment.isEmpty()) continue;
                        if (cap.length() > 0) cap.append(" | ");
                        cap.append(segment);
                    }
                    String caption = cap.toString();
                    cols.add(new AiQueryMetadata.Caption(caption, caption));
                }
            }
            meta.setColumns(cols);

            // Walk body building both row captions + the per-row payload.
            List<AiQueryMetadata.Caption> rows = new ArrayList<>();
            List<Map<String, AiCell>> matrix = new ArrayList<>();
            List<Map<String, Object>> records = new ArrayList<>();

            // Row-header column captions (e.g. ["Product Family"] or
            // ["Product Family", "Year"] for multi-axis rows). We pull
            // them from the last header row's row-header section.
            List<String> rowHeaderCaptions = new ArrayList<>();
            if (headers != null && headers.length > 0) {
                AbstractBaseCell[] lastHeader = headers[headers.length - 1];
                for (int c = 0; c < rowHeaderCount && c < lastHeader.length; c++) {
                    String cap = lastHeader[c] == null ? "" : safe(lastHeader[c].getFormattedValue());
                    if (cap.isEmpty()) cap = "row" + c;
                    rowHeaderCaptions.add(cap);
                }
            }

            // saiku#803: measures-only queries (no rows axis on the request)
            // render with the measure captions as a header row that lands in
            // body[0] and the values in body[1]. The standard renderer below
            // treats both as row-headers, producing the awkward
            // {row0:"Unit Sales", row1:"Store Sales"} / {row0:"266,773"}
            // shape. Detect that case and flatten to a single record keyed by
            // measure caption with typed AiCell values — matching every other
            // query shape on the API.
            boolean measuresOnly = body != null
                    && body.length == 2
                    && rowHeaderCount == totalWidth
                    && totalWidth > 0
                    && body[0] != null
                    && body[1] != null
                    && allMemberCells(body[0])
                    && allDataCells(body[1]);
            if (measuresOnly) {
                if (useMatrix) {
                    Map<String, AiCell> cells = new LinkedHashMap<>();
                    for (int c = 0; c < totalWidth; c++) {
                        cells.put(String.valueOf(c), toCell(body[1][c]));
                    }
                    matrix.add(cells);
                    rows.add(new AiQueryMetadata.Caption("", ""));
                } else {
                    Map<String, Object> record = new LinkedHashMap<>();
                    for (int c = 0; c < totalWidth; c++) {
                        String key = body[0][c] == null ? ("col" + c) : safe(body[0][c].getFormattedValue());
                        if (key.isEmpty()) key = "col" + c;
                        record.put(key, toCell(body[1][c]));
                    }
                    records.add(record);
                    rows.add(new AiQueryMetadata.Caption("", ""));
                }
            } else if (body != null) {
                for (AbstractBaseCell[] row : body) {
                    rows.add(
                            new AiQueryMetadata.Caption(rowName(row, rowHeaderCount), rowCaption(row, rowHeaderCount)));

                    if (useMatrix) {
                        Map<String, AiCell> cells = new LinkedHashMap<>();
                        for (int c = rowHeaderCount; c < totalWidth; c++) {
                            cells.put(String.valueOf(c - rowHeaderCount), toCell(row[c]));
                        }
                        matrix.add(cells);
                    } else {
                        Map<String, Object> record = new LinkedHashMap<>();
                        // Row-header columns first, named by their captions.
                        for (int c = 0; c < rowHeaderCount && c < row.length; c++) {
                            String key = c < rowHeaderCaptions.size() ? rowHeaderCaptions.get(c) : ("row" + c);
                            record.put(key, row[c] == null ? "" : safe(row[c].getFormattedValue()));
                        }
                        // Data cells next, named by column caption.
                        for (int c = rowHeaderCount; c < totalWidth; c++) {
                            int colIdx = c - rowHeaderCount;
                            String colKey =
                                    colIdx < cols.size() ? cols.get(colIdx).getCaption() : ("col" + colIdx);
                            record.put(colKey, toCell(row[c]));
                        }
                        records.add(record);
                    }
                }
            }
            meta.setRows(rows);
            if (useMatrix) resp.setMatrix(matrix);
            else resp.setData(records);
            resp.setTotalRows(rows.size());

            List<String> measureNames = new ArrayList<>();
            for (AiQueryMetadata.Caption c : cols) measureNames.add(c.getCaption());
            meta.setMeasures(measureNames);
        }
        resp.setRuntimeMs(System.currentTimeMillis() - startedAt);
        return resp;
    }

    /**
     * Convert a raw JDBC column value (from a drillthrough ResultSet) into
     * an {@link AiCell}. Numeric column types come back as native Numbers;
     * everything else (strings, dates) populates {@code formatted} only.
     */
    private static AiCell toCellFromObject(Object v) {
        if (v == null) return new AiCell(null, "", null);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return new AiCell(d, v.toString(), null);
        }
        String s = v.toString();
        Double parsed = AiCell.parseValueFromFormatted(s);
        String unit = AiCell.sniffUnit(s);
        return new AiCell(parsed, s, unit);
    }

    /**
     * saiku#800: build a per-column key list that survives Mondrian's habit
     * of returning the same {@code getColumnLabel(c)} for multiple drill-
     * through columns within a hierarchy (e.g. Year/Quarter/Month all
     * labelled "Quarter"). First occurrence keeps its raw label; subsequent
     * collisions try {@code getColumnName(c)} as a tiebreaker and finally
     * fall back to a {@code _N} positional suffix. Order-preserving so the
     * returned list aligns with column indexes 1..colCount.
     */
    static List<String> disambiguateColumnLabels(java.sql.ResultSetMetaData md) throws java.sql.SQLException {
        int colCount = md.getColumnCount();
        List<String> out = new ArrayList<>(colCount);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int c = 1; c <= colCount; c++) {
            String label = md.getColumnLabel(c);
            if (label == null || label.isEmpty()) label = "col_" + c;
            String chosen = label;
            if (seen.contains(chosen)) {
                String alt = null;
                try {
                    alt = md.getColumnName(c);
                } catch (java.sql.SQLException ignore) {
                    // Some drivers don't implement getColumnName — fall through.
                }
                if (alt != null && !alt.isEmpty() && !seen.contains(alt) && !alt.equals(label)) {
                    chosen = alt;
                } else {
                    int n = 2;
                    String candidate;
                    do {
                        candidate = label + "_" + n;
                        n++;
                    } while (seen.contains(candidate));
                    chosen = candidate;
                }
            }
            seen.add(chosen);
            out.add(chosen);
        }
        return out;
    }

    /** Convert a raw {@link AbstractBaseCell} into an {@link AiCell}. */
    private static AiCell toCell(AbstractBaseCell c) {
        if (c == null) return new AiCell(null, "", null);
        String formatted = safe(c.getFormattedValue());
        // Prefer the engine's raw numeric when DataCell carries one.
        Double value = null;
        java.util.Map<String, String> props = null;
        if (c instanceof org.saiku.olap.dto.resultset.DataCell) {
            org.saiku.olap.dto.resultset.DataCell dc = (org.saiku.olap.dto.resultset.DataCell) c;
            Number raw = dc.getRawNumber();
            if (raw != null) value = raw.doubleValue();
            // saiku#773: thread the cellset's olap4j StandardCellProperty
            // values out to the agent. Empty -> stays null and Jackson
            // drops the field per AiCell's NON_EMPTY include policy.
            java.util.Map<String, String> p = dc.getProperties();
            if (p != null && !p.isEmpty()) props = p;
        }
        if (value == null) value = AiCell.parseValueFromFormatted(formatted);
        String unit = AiCell.sniffUnit(formatted);
        return new AiCell(value, formatted, unit, props);
    }

    /**
     * Number of leading columns in the body matrix that are MemberCells
     * (i.e. row-header columns). Computed from the first row; defaults to
     * 0 when the body is empty.
     */
    private static int countRowHeaderColumns(AbstractBaseCell[][] body) {
        if (body == null || body.length == 0) return 0;
        AbstractBaseCell[] first = body[0];
        int n = 0;
        while (n < first.length && first[n] instanceof MemberCell) n++;
        return n;
    }

    /** saiku#803: every cell in the row is a MemberCell (caption). Used to
     *  detect the measures-only header-leaked-into-body shape. */
    private static boolean allMemberCells(AbstractBaseCell[] row) {
        if (row == null || row.length == 0) return false;
        for (AbstractBaseCell c : row) {
            if (!(c instanceof MemberCell)) return false;
        }
        return true;
    }

    /** saiku#803: every cell in the row is a DataCell. Paired with
     *  {@link #allMemberCells} to identify the measures-only flatten case. */
    private static boolean allDataCells(AbstractBaseCell[] row) {
        if (row == null || row.length == 0) return false;
        for (AbstractBaseCell c : row) {
            if (!(c instanceof org.saiku.olap.dto.resultset.DataCell)) return false;
        }
        return true;
    }

    private static String rowName(AbstractBaseCell[] row, int rowHeaderCount) {
        StringBuilder s = new StringBuilder();
        for (int c = 0; c < rowHeaderCount && c < row.length; c++) {
            if (s.length() > 0) s.append(" | ");
            s.append(row[c] == null ? "" : safe(row[c].getFormattedValue()));
        }
        return s.toString();
    }

    private static String rowCaption(AbstractBaseCell[] row, int rowHeaderCount) {
        // Same as rowName for the DTO matrix the API exposes — caption and
        // name converge once headers are formatted.
        return rowName(row, rowHeaderCount);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private Response badRequest(String field, String message, List<String> available) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
        resp.setError(message);
        resp.setField(field);
        if (available != null) resp.setAvailable(available);
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(resp)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private Response error(String message) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setStatus(AiQueryResponse.Status.EXECUTION_ERROR);
        resp.setError(message);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(resp)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
