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
import org.saiku.olap.util.SaikuProperties;
import org.saiku.service.async.AsyncQueryHandle;
import org.saiku.service.async.AsyncQueryService;
import org.saiku.service.mail.MailSender;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiPiiException;
import org.saiku.service.olap.ai.AiQueryMetadata;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiReturnsResolver;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.AiValidationException;
import org.saiku.service.olap.ai.OlapAiCubeMetadataService;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.DashboardSpec;
import org.saiku.web.util.JdbcCleanup;
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
    /**
     * Phase 2: optional natural-language ask layer. When set, {@code POST /ai/ask} is enabled
     * (otherwise it returns 503 "not configured"). Held as {@code null} when no Spring wiring
     * supplies one — the rest of the resource is unaffected.
     */
    private AiAskService askService;

    public void setAskService(AiAskService s) {
        this.askService = s;
    }

    /**
     * Task 3 (NL email-draft slice): mail-configured gate for the {@code EMAIL_DRAFT} ask outcome.
     * Wired to the same {@code mailSender} bean {@link org.saiku.web.email.EmailResource} uses for
     * its own health check — held as {@code null} when no Spring wiring supplies one, in which
     * case {@link #mailConfigured()} fails closed (never hands the user an un-sendable draft).
     */
    private MailSender mailSender;

    public void setMailSender(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** True only when a real mail transport is wired and configured — mirrors {@code
     *  EmailResource#health()}'s check exactly, so the two surfaces can never disagree. */
    private boolean mailConfigured() {
        return mailSender != null && mailSender.isConfigured();
    }

    /**
     * saiku#1151: per-caller call-rate cap on the cost-bearing ask endpoint.
     * Default budget; replace via {@link #setAskRateLimiter} (Spring wiring or
     * tests). Size caps live in {@code AiAskGuard}.
     */
    private org.saiku.web.security.ratelimit.AiRateLimiter askRateLimiter =
            new org.saiku.web.security.ratelimit.AiRateLimiter();

    public void setAskRateLimiter(org.saiku.web.security.ratelimit.AiRateLimiter l) {
        this.askRateLimiter = l;
    }

    private final AiSchemaConverter converter = new AiSchemaConverter();
    /** Phase 2: JSON-Schema-driven shape validator. Runs first so an
     *  agent gets one structured 400 per shape failure instead of a
     *  cascading wall of converter-layer errors. Same schema doc embedded
     *  in /schema/{cubeId}.requestSchema for client-side use, so the
     *  server-side and client-side rules can't drift. */
    private final org.saiku.service.olap.ai.AiRequestSchemaValidator schemaValidator =
            new org.saiku.service.olap.ai.AiRequestSchemaValidator();

    /** saiku#903 — gates which data tier may leave the box. Defaults to a
     *  permissive (FULL) no-op so existing tests / un-wired contexts behave as
     *  before; the Spring bean (saiku-beans.xml) injects the real config-driven
     *  guard, whose default is schema-only. */
    private org.saiku.service.olap.ai.AiPolicyGuard aiPolicyGuard =
            new org.saiku.service.olap.ai.AiPolicyGuard(org.saiku.service.olap.ai.AiPolicy.FULL);

    public void setAiPolicyGuard(org.saiku.service.olap.ai.AiPolicyGuard g) {
        this.aiPolicyGuard = g;
    }

    /** saiku#905 — k-anonymity small-cell suppression. Defaults to disabled
     *  (k=0) so existing tests / un-wired contexts behave as before; the Spring
     *  bean injects the real config-driven filter (default k=5). */
    private org.saiku.service.olap.ai.KAnonymityFilter kAnonymityFilter =
            new org.saiku.service.olap.ai.KAnonymityFilter(0, null);

    public void setKAnonymityFilter(org.saiku.service.olap.ai.KAnonymityFilter f) {
        this.kAnonymityFilter = f;
    }

    /**
     * saiku#905 / #1324 — apply k-anonymity small-cell suppression to a
     * records-format payload using an in-result count measure (a measure column
     * whose caption names a count on a word boundary, e.g. Mondrian's "Fact
     * Count"): any row whose count is below k has all its measure cells masked.
     * No-op when suppression is disabled, there's no count column, or the payload
     * is empty. The shadow-count query for cubes that don't surface a count
     * measure is the saiku#905 follow-up. Package-private so the wiring is
     * unit-testable without standing up a CellDataSet.
     */
    void applyKAnonymity(java.util.List<java.util.Map<String, Object>> records) {
        if (!kAnonymityFilter.enabled() || records == null || records.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<String> measureKeys = new java.util.LinkedHashSet<>();
        for (java.util.Map<String, Object> r : records) {
            for (java.util.Map.Entry<String, Object> e : r.entrySet()) {
                if (e.getValue() instanceof org.saiku.service.olap.ai.AiCell) {
                    measureKeys.add(e.getKey());
                }
            }
        }
        String countKey = null;
        for (String k : measureKeys) {
            if (org.saiku.service.olap.ai.KAnonymityFilter.COUNT_MEASURE
                    .matcher(k)
                    .find()) {
                countKey = k;
                break;
            }
        }
        if (countKey != null) {
            kAnonymityFilter.applyToRecords(records, countKey, measureKeys);
        }
    }

    /**
     * saiku#1324 — apply k-anonymity to the {@code format=matrix} payload,
     * closing the egress bypass where small cells previously returned unmasked
     * just because the caller asked for matrix output. Matrix cells are
     * index-keyed ("0","1",…), so the count column is located via
     * {@code columnCaptions} (index → caption, captured as the matrix is built)
     * using the same whole-word detection as the records path; then every cell in
     * a sub-k row is masked. No-op when disabled, empty, or there's no count
     * column. Package-private for unit-testing without a CellDataSet.
     */
    void applyKAnonymityMatrix(
            java.util.List<java.util.Map<String, AiCell>> matrix, java.util.List<String> columnCaptions) {
        if (!kAnonymityFilter.enabled() || matrix == null || matrix.isEmpty() || columnCaptions == null) {
            return;
        }
        String countKey = null;
        java.util.LinkedHashSet<String> measureKeys = new java.util.LinkedHashSet<>();
        for (int i = 0; i < columnCaptions.size(); i++) {
            String key = String.valueOf(i);
            measureKeys.add(key);
            String caption = columnCaptions.get(i);
            if (countKey == null
                    && caption != null
                    && org.saiku.service.olap.ai.KAnonymityFilter.COUNT_MEASURE
                            .matcher(caption)
                            .find()) {
                countKey = key;
            }
        }
        if (countKey != null) {
            kAnonymityFilter.applyToMatrix(matrix, countKey, measureKeys);
        }
    }

    public void setThinQueryService(ThinQueryService tqs) {
        this.thinQueryService = tqs;
    }

    public void setCubeMetadataService(AiCubeMetadataService svc) {
        this.cubeMetadataService = svc;
    }

    /** saiku#scenario — raw olap4j connection for Mondrian write-back (what-if) scenarios. */
    private org.saiku.service.olap.OlapDiscoverService olapDiscoverService;

    public void setOlapDiscoverService(org.saiku.service.olap.OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    public void setAsyncQueryService(AsyncQueryService a) {
        this.asyncQueryService = a;
    }

    /**
     * Resolve the current caller's principal name (the async-handle owner) from
     * the Spring Security context. {@code null} when unauthenticated / no
     * context — kept in lockstep with {@link AsyncQueryService#currentPrincipal()}
     * so submit-time and access-time identity are derived the same way.
     */
    private static String currentPrincipal() {
        return AsyncQueryService.currentPrincipal();
    }

    /** True when the current caller holds {@code ROLE_ADMIN}. */
    private static boolean currentUserIsAdmin() {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                            .getAuthentication();
            if (auth == null) {
                return false;
            }
            for (org.springframework.security.core.GrantedAuthority ga : auth.getAuthorities()) {
                if ("ROLE_ADMIN".equals(ga.getAuthority())) {
                    return true;
                }
            }
        } catch (RuntimeException ignore) {
            // No / broken security context — treat as non-admin.
        }
        return false;
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
    public Response executeSaved(
            org.saiku.service.olap.ai.AiSavedQueryRequest body,
            @QueryParam("format") @DefaultValue("records") String format) {
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
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
        // saiku#1752: roles come from the single authoritative SecurityContextHolder reader, not the
        // lazily-seeded session "roles" map (see SessionRoles / saiku#1747).
        java.util.List<String> roles = org.saiku.web.rest.util.SessionRoles.currentRoles();
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
            return badRequest("path", "Saved query is not valid ThinQuery JSON", null);
        }
        // Saved queries written by older builds stored the file path in
        // tq.name, which contains slashes. Jetty 12's strict URI rules
        // reject %2F in path segments — clobber any unsafe name with a
        // fresh UUID before handing the query to the executor (mirrors
        // saiku-ui's withSafeName helper).
        if (tq.getName() == null || tq.getName().contains("/")) {
            tq.setName(java.util.UUID.randomUUID().toString());
        }

        // Merge best-effort dashboard runtime filters FIRST. Skipped silently when the request
        // carries no filters (the historical path), when the query is MDX-mode (can't splice
        // safely), or when the cube schema can't be loaded. See ThinQueryFilterMerge for the
        // precedence + axis-rewrite rules.
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

        // saiku#1104 — forced RLS filters apply LAST (after any client/dashboard filters) so they
        // always win: a client filter on the same dimension can't loosen the row-level restriction.
        // They MUST apply or the request fails closed — a forced filter that can't be spliced
        // (MDX-mode query, unresolvable dimension, schema lookup failure) means the query would run
        // UNFILTERED, the exact RLS bypass we refuse. Only the embed surface sets these.
        if (body.getForcedFilters() != null && !body.getForcedFilters().isEmpty()) {
            AiSchema forcedSchema = null;
            if (tq.getCube() != null && cubeMetadataService != null) {
                try {
                    org.saiku.olap.dto.SaikuCube cube = tq.getCube();
                    forcedSchema = cubeMetadataService.getSchema(
                            new AiCubeRef(cube.getConnection(), cube.getCatalog(), cube.getSchema(), cube.getName()));
                } catch (RuntimeException e) {
                    log.warn("saved-query {} forced-filter schema lookup failed — failing closed", path, e);
                }
            }
            java.util.List<org.saiku.service.olap.ai.AiFilterSelection> unapplied =
                    org.saiku.service.olap.ai.ThinQueryFilterMerge.applyReportingUnapplied(
                            tq, body.getForcedFilters(), forcedSchema);
            if (!unapplied.isEmpty()) {
                log.warn(
                        "saved-query {} refused: {} forced RLS filter(s) could not be applied (fail-closed)",
                        path,
                        unapplied.size());
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(java.util.Map.of(
                                "status",
                                "RLS_UNAPPLIED",
                                "error",
                                "Row-level security filters could not be applied to this saved query."))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
        }

        CellDataSet cds;
        try {
            cds = thinQueryService.execute(tq);
        } catch (RuntimeException e) {
            log.error("saved-query execution failed for {}", path, e);
            return error("execute failed");
        }
        AiQueryResponse resp = buildResponse(tq, cds, start, format);
        return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeAi(AiQueryRequest req, @QueryParam("format") @DefaultValue("records") String format) {
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
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
            return error("validation failed");
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
            return error("execute failed");
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
                        new org.saiku.service.async.AsyncQueryHandle(tq.getName(), tq, currentPrincipal());
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

        // saiku#1780: pass the requested measure labels + slicer dimensions so
        // buildResponse can surface any measure Mondrian dropped for lack of a
        // join path to a filtered dimension (instead of silently omitting it).
        AiQueryResponse resp = buildResponse(
                tq, cds, start, format, requestedMeasureLabelGroups(req, schema), filterDimensionLabels(req));
        return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * saiku#1780 — for EACH requested measure, collect every label an agent
     * might recognise it by: the raw name the caller sent, plus (resolved via
     * the schema) the canonical name and the display-name rename. The labels
     * are GROUPED per measure — one inner list per requested measure — so
     * {@link #detectDroppedMeasures} can treat a measure as present if ANY of
     * its labels matches a result column. A flat label list would falsely flag
     * a PRESENT measure requested via a synonym (#818) or carrying a display
     * rename (Phase-3 <datasource>.generated.json) as "unavailable" under its
     * non-caption label.
     *
     * <p>The first entry of each group is the label we surface the "unavailable"
     * cell under when the measure really was dropped: the canonical name when we
     * could resolve one, else the raw requested name.
     */
    private static List<List<String>> requestedMeasureLabelGroups(AiQueryRequest req, AiSchema schema) {
        List<List<String>> groups = new ArrayList<>();
        if (req == null || req.getMeasures() == null) return groups;
        for (org.saiku.service.olap.ai.AiMeasureSelection m : req.getMeasures()) {
            if (m == null || m.getName() == null || m.getName().trim().isEmpty()) continue;
            String requested = m.getName().trim();

            AiSchema.Measure resolved = null;
            if (schema != null) {
                resolved = schema.measures.get(AiSchema.key(requested));
                if (resolved == null) {
                    String canonKey = schema.measureAliases.get(AiSchema.key(requested));
                    if (canonKey != null) resolved = schema.measures.get(canonKey);
                }
            }

            // Primary/surfacing label first: canonical name when resolvable,
            // else the raw requested name.
            java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
            if (resolved != null
                    && resolved.name != null
                    && !resolved.name.trim().isEmpty()) {
                labels.add(resolved.name.trim());
            } else {
                labels.add(requested);
            }
            labels.add(requested);
            if (resolved != null) {
                if (resolved.name != null && !resolved.name.trim().isEmpty()) labels.add(resolved.name.trim());
                if (resolved.displayName != null && !resolved.displayName.trim().isEmpty()) {
                    labels.add(resolved.displayName.trim());
                }
            }
            groups.add(new ArrayList<>(labels));
        }
        return groups;
    }

    /** saiku#1780 — the dimension names on the slicer, for the drop reason. */
    private static List<String> filterDimensionLabels(AiQueryRequest req) {
        List<String> dims = new ArrayList<>();
        if (req == null || req.getFilters() == null) return dims;
        for (org.saiku.service.olap.ai.AiFilterSelection f : req.getFilters()) {
            if (f == null || f.getDimension() == null || f.getDimension().trim().isEmpty()) continue;
            String d = f.getDimension().trim();
            if (!dims.contains(d)) dims.add(d);
        }
        return dims;
    }

    /**
     * Server-side statistical anomaly detection over a time-series query
     * (saiku#907). Tier-3: NO LLM, NO external model — all computation runs
     * in-JVM via {@link org.saiku.service.olap.ai.anomaly.AnomalyDetector}.
     *
     * <p>Body: {@code { query: {<AiQueryRequest>}, method: "zscore"|"mad"|"stl",
     * threshold: number, timeAxis: "<axis unique name>" }}. The {@code query}
     * is executed through the exact same path {@code /ai/query} uses (validate →
     * convert → {@link ThinQueryService#execute}); the numeric series is then
     * extracted per measure column (rows = time members in order) and the chosen
     * detector flags anomalous points. The response is the standard
     * {@link AiQueryResponse} (records format) AUGMENTED with an
     * {@code anomaly:{score,expected,direction}} object on each flagged cell,
     * plus a top-level {@code anomalyCount} so "no anomalies" is an explicit
     * {@code 0}, never a missing field.
     *
     * <p>Validation errors (unknown method, bad threshold, missing query/cube,
     * STL stub, or a non-numeric time axis) reuse the same
     * {@link AiValidationException} → {@code badRequest} envelope as the rest of
     * this resource, with {@code field} + {@code available} for self-correction.
     */
    @POST
    @Path("/anomaly")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response detectAnomalies(org.saiku.service.olap.ai.anomaly.AiAnomalyRequest body) {
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
        long start = System.currentTimeMillis();
        if (body == null) {
            return badRequest("body", "request body required", null);
        }
        AiQueryRequest req = body.getQuery();
        if (req == null) {
            return badRequest("query", "query (an AiQueryRequest) is required", null);
        }
        String timeAxis = body.getTimeAxis();
        if (timeAxis == null || timeAxis.isBlank()) {
            return badRequest("timeAxis", "timeAxis (the time axis unique name) is required", null);
        }

        // Resolve the detector + threshold up-front so bad method / threshold
        // fail fast with a clean 400 before we execute the (expensive) query.
        org.saiku.service.olap.ai.anomaly.AnomalyDetector detector;
        try {
            detector = org.saiku.service.olap.ai.anomaly.AnomalyDetectors.forMethod(body.getMethod());
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        }
        double threshold = detector.defaultThreshold();
        if (body.getThreshold() != null) {
            double t = body.getThreshold();
            if (Double.isNaN(t) || Double.isInfinite(t) || t <= 0) {
                return badRequest("threshold", "threshold must be a positive number", null);
            }
            threshold = t;
        }

        // Execute the query through the SAME path /ai/query uses.
        AiSchema schema;
        ThinQuery tq;
        try {
            schemaValidator.assertValid(MAPPER.valueToTree(req));
            if (req.getCube() == null) {
                return badRequest("query.cube", "cube ref required", null);
            }
            schema = cubeMetadataService.getSchema(req.getCube());
            tq = converter.convert(req, schema);
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI anomaly query validation failed", e);
            return error("validation failed");
        }

        CellDataSet cds;
        try {
            cds = thinQueryService.execute(tq);
        } catch (RuntimeException e) {
            Response translated = translateMondrianLookupError(e);
            if (translated != null) return translated;
            Response pathErr = translatePhysPathNpe(e);
            if (pathErr != null) return pathErr;
            log.error("AI anomaly query execution failed", e);
            return error("execute failed");
        }

        // Always build in records format — that is the shape the augmenter and
        // the chart tiles consume.
        AiQueryResponse resp = buildResponse(tq, cds, start, "records");
        try {
            org.saiku.service.olap.ai.anomaly.AnomalyAugmenter.augment(resp, detector, threshold, timeAxis);
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            // STL stub throws AiValidationException (handled above); any other
            // failure here is a real bug in the detector — surface it as a 500.
            log.error("AI anomaly detection failed", e);
            return error("anomaly detection failed");
        }

        // saiku#1482 — the /anomaly surface previously bypassed k-anonymity, leaking small-cell
        // values /ai/query would have masked. Detection ran on the real values above (accurate
        // flags); we mask the small-cell values in the observed records before they leave. Reuses
        // the exact filter /ai/query uses — no-op without a count measure or when disabled.
        applyKAnonymity(resp.getData());

        int anomalyCount = org.saiku.service.olap.ai.anomaly.AnomalyAugmenter.countAnomalies(resp);
        resp.setRuntimeMs(System.currentTimeMillis() - start);

        // Echo the typed response plus a compact anomaly summary block. We wrap
        // in a LinkedHashMap rather than mutating AiQueryResponse so the /query
        // shape stays untouched for other callers; clients read resp fields as
        // usual and check the sibling anomaly summary for counts/params.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("response", resp);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("method", detector.method());
        summary.put("threshold", threshold);
        summary.put("timeAxis", timeAxis);
        summary.put("anomalyCount", anomalyCount);
        out.put("anomaly", summary);
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Server-side statistical forecast over a time-series query (saiku#908),
     * Tier-3: in-JVM via {@link org.saiku.service.olap.ai.forecast.Forecaster},
     * NO LLM / NO external model. Runs {@code body.query} through the SAME path
     * {@code /ai/query} uses, extracts each measure series in time order, and
     * projects {@code horizon} future points with prediction intervals at the
     * requested {@code confidence}.
     *
     * <p>Echoes the typed {@link AiQueryResponse} (records, observed data
     * untouched) plus a sibling {@code forecast} block keyed by measure caption
     * — {@code {method, horizon, confidence, timeAxis, series:{caption:[{value,
     * lower,upper,forecast}]}}} — which the chart tile appends as a dashed
     * continuation with a confidence band. Unknown method / bad horizon /
     * confidence return the self-correcting 400 envelope; {@code arima} and
     * {@code prophet} are registered stubs that 400 until impls land.
     */
    @POST
    @Path("/forecast")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response forecast(org.saiku.service.olap.ai.forecast.AiForecastRequest body) {
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
        long start = System.currentTimeMillis();
        if (body == null) {
            return badRequest("body", "request body required", null);
        }
        AiQueryRequest req = body.getQuery();
        if (req == null) {
            return badRequest("query", "query (an AiQueryRequest) is required", null);
        }
        String timeAxis = body.getTimeAxis();
        if (timeAxis == null || timeAxis.isBlank()) {
            return badRequest("timeAxis", "timeAxis (the time axis unique name) is required", null);
        }

        // Resolve forecaster + params up-front so bad inputs fail fast with a
        // clean 400 before we execute the (expensive) query.
        org.saiku.service.olap.ai.forecast.Forecaster forecaster;
        try {
            forecaster = org.saiku.service.olap.ai.forecast.Forecasters.forMethod(body.getMethod());
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        }
        int horizon = body.getHorizon() == null ? 6 : body.getHorizon();
        if (horizon < 1 || horizon > 365) {
            return badRequest("horizon", "horizon must be between 1 and 365", null);
        }
        double confidence = body.getConfidence() == null ? 0.95 : body.getConfidence();
        if (confidence <= 0 || confidence >= 1 || Double.isNaN(confidence)) {
            return badRequest("confidence", "confidence must be between 0 and 1 (exclusive)", null);
        }

        AiSchema schema;
        ThinQuery tq;
        try {
            schemaValidator.assertValid(MAPPER.valueToTree(req));
            if (req.getCube() == null) {
                return badRequest("query.cube", "cube ref required", null);
            }
            schema = cubeMetadataService.getSchema(req.getCube());
            tq = converter.convert(req, schema);
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI forecast query validation failed", e);
            return error("validation failed");
        }

        CellDataSet cds;
        try {
            cds = thinQueryService.execute(tq);
        } catch (RuntimeException e) {
            Response translated = translateMondrianLookupError(e);
            if (translated != null) return translated;
            Response pathErr = translatePhysPathNpe(e);
            if (pathErr != null) return pathErr;
            log.error("AI forecast query execution failed", e);
            return error("execute failed");
        }

        AiQueryResponse resp = buildResponse(tq, cds, start, "records");
        Map<String, List<org.saiku.service.olap.ai.forecast.ForecastPoint>> series;
        try {
            series = org.saiku.service.olap.ai.forecast.ForecastAssembler.assemble(
                    resp, forecaster, horizon, confidence, timeAxis);
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            // arima/prophet stubs throw AiValidationException (handled above);
            // anything else here is a real forecaster bug → 500.
            log.error("AI forecast failed", e);
            return error("forecast failed");
        }

        // saiku#1482 — mask small-cell values in the observed records before egress, same as
        // /ai/query. The forecast projections above were computed on the real series (they're
        // derived aggregates, not raw cells); this closes the raw small-cell leak on the observed
        // half of the response. No-op without a count measure or when disabled.
        applyKAnonymity(resp.getData());

        resp.setRuntimeMs(System.currentTimeMillis() - start);

        // Echo the typed response plus a sibling forecast block (observed data
        // untouched), mirroring the /anomaly envelope.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("response", resp);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("method", forecaster.method());
        summary.put("horizon", horizon);
        summary.put("confidence", confidence);
        summary.put("timeAxis", timeAxis);
        summary.put("series", series);
        out.put("forecast", summary);
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Natural-language ask endpoint.
     *
     * <p>Body: {@code {question: string, cube: AiCubeRef, history?: [{role, content}]}}.
     * Translates {@code question} to an {@link AiQueryRequest} via the configured
     * {@link AiAskService} (Anthropic / OpenAI tool-use), executes the resulting request through
     * the same path {@code /ai/query} uses, and returns an {@link AiAskApi.AskResponse} envelope
     * carrying:
     *
     * <ul>
     *   <li>{@code request} — the structured query the model emitted (handy for the UI's "edit in
     *       canvas" flow);
     *   <li>{@code response} — the full {@link AiQueryResponse} from executing it;
     *   <li>{@code generatedMdx} — convenience mirror of {@code response.metadata.generatedMdx}.
     * </ul>
     *
     * <p>Status codes:
     *
     * <ul>
     *   <li>200 — translation succeeded; {@code response} carries the executed query envelope
     *       (which itself may report VALIDATION_ERROR if the model named something invalid — the
     *       UI surfaces those candidate lists as clickable suggestions).
     *   <li>400 — malformed request body (missing question / cube).
     *   <li>503 — {@link AiAskService} not wired (provider=noop). Body is an
     *       {@link AiAskApi.AskResponse} with {@code degraded=true, reason=...} so the UI can
     *       render a clear "AI ask is not configured" message.
     * </ul>
     */
    /**
     * Cheap configuration probe used by the workspace to decide whether to render the "Ask the AI"
     * toolbar button. Returns {@code {"configured": true|false}} — never throws, never depends on a
     * cube selection. The body is intentionally tiny so the client can call it on app load.
     *
     * <p>Returns {@code configured:false} when:
     * <ul>
     *   <li>the {@link AiAskService} bean wasn't wired (no provider configured at all), or
     *   <li>the bean wraps a {@link org.saiku.service.olap.ai.ask.NoopNlAskProvider}.
     * </ul>
     */
    @GET
    @Path("/ask/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response askHealth() {
        boolean configured = askService != null && askService.isConfigured();
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("configured", configured);
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Skill catalogue (saiku#1426): admin-authored markdown workflows under {@code
     * saiku-home/skills/}. Returned as compact summaries so a site with dozens of skills stays
     * light on the wire. Pass {@code ?errors=true} to include parse failures so operators can fix
     * bad frontmatter without reading server logs.
     */
    @GET
    @Path("/skills")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listSkills(@QueryParam("errors") @DefaultValue("false") boolean includeErrors) {
        if (askService == null || askService.skills() == null) {
            return Response.ok(java.util.Map.of("skills", List.of()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        var registry = askService.skills();
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        List<Object> out = new ArrayList<>();
        for (var skill : registry.list()) {
            out.add(skill.asSummary());
        }
        body.put("skills", out);
        if (includeErrors) {
            body.put("errors", registry.errors());
        }
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * One skill's full body — used by the UI when the user picks a slash-menu entry so it can
     * preview the workflow before dispatching.
     */
    @GET
    @Path("/skills/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSkill(@PathParam("name") String name) {
        if (askService == null || askService.skills() == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(java.util.Map.of("error", "skill not found"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return askService
                .skills()
                .get(name)
                .<Response>map(skill ->
                        Response.ok(skill).type(MediaType.APPLICATION_JSON).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(java.util.Map.of("error", "skill '" + name + "' not found"))
                        .type(MediaType.APPLICATION_JSON)
                        .build());
    }

    /**
     * Force-refresh the skill catalogue. Called by admin/refresh (or manually while iterating on
     * skills). Returns the fresh catalogue counts so operators can eyeball the reload.
     */
    @POST
    @Path("/skills/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshSkills() {
        if (askService == null || askService.skills() == null) {
            return Response.ok(java.util.Map.of("skills", 0, "errors", 0))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        var registry = askService.skills();
        registry.forceRefresh();
        return Response.ok(java.util.Map.of(
                        "skills", registry.list().size(),
                        "errors", registry.errors().size()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Agent-space catalogue (saiku#1440). Returned as compact summaries — id, name, description,
     * suggested prompts. The system prompt and cube allowlist are deliberately omitted so an
     * unauthenticated embed can't scrape the persona routing.
     */
    @GET
    @Path("/spaces")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listSpaces(@QueryParam("errors") @DefaultValue("false") boolean includeErrors) {
        if (askService == null || askService.spaces() == null) {
            return Response.ok(java.util.Map.of("spaces", List.of()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        var registry = askService.spaces();
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        List<Object> out = new ArrayList<>();
        for (var space : registry.list()) {
            out.add(space.asSummary());
        }
        body.put("spaces", out);
        if (includeErrors) {
            body.put("errors", registry.errors());
        }
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * One space's full record — used by the admin UI when editing a persona. Includes the system
     * prompt and cube allowlist that {@link #listSpaces} omits.
     */
    @GET
    @Path("/spaces/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSpace(@PathParam("id") String id) {
        if (askService == null || askService.spaces() == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(java.util.Map.of("error", "space not found"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return askService
                .spaces()
                .get(id)
                .<Response>map(space ->
                        Response.ok(space).type(MediaType.APPLICATION_JSON).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(java.util.Map.of("error", "space '" + id + "' not found"))
                        .type(MediaType.APPLICATION_JSON)
                        .build());
    }

    @POST
    @Path("/spaces/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshSpaces() {
        if (askService == null || askService.spaces() == null) {
            return Response.ok(java.util.Map.of("spaces", 0, "errors", 0))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        var registry = askService.spaces();
        registry.forceRefresh();
        return Response.ok(java.util.Map.of(
                        "spaces", registry.list().size(),
                        "errors", registry.errors().size()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Space-scoped ask: same envelope as {@link #ask}, but the persona referenced by {@code
     * spaceId} pins the cube allowlist, filters the skill catalogue, and injects the system
     * prompt. Body's {@code cube} field is optional — the space's default cube is used when it's
     * omitted; when provided, it must match one of the space's allowlisted refs or the call
     * returns 403.
     */
    @POST
    @Path("/spaces/{id}/ask")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response askInSpace(@PathParam("id") String spaceId, AiAskApi.AskRequest body) {
        Response pre = validateAskPreamble(body, false);
        if (pre != null) {
            return pre;
        }
        AiAskService.AskOutcome outcome = askService.askInSpace(
                spaceId,
                body.getCube(),
                body.getQuestion(),
                body.historyAsMessages(),
                body.getCellsetDigest(),
                parseForceTool(body.getForceTool()),
                body.getCurrentQuery());
        if (outcome.degraded()) {
            // Map scope denials by the outcome's typed denial code (saiku#1465) rather than by
            // prefix-matching the prose reason — a wording change in the service can no longer
            // silently downgrade a 403 to a 200. A plain provider degrade (denial == OK) falls
            // through to a 200 with degraded:true, same as the classic /ask.
            Response denial = mapSpaceAccessDenial(outcome.denial(), spaceId);
            if (denial != null) {
                return denial;
            }
            AiAskApi.AskResponse out = new AiAskApi.AskResponse();
            out.setDegraded(true);
            out.setReason(outcome.reason());
            out.setModel(outcome.model());
            return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
        }
        // Mirror the classic /ai/ask success handling (saiku#1455): branch on the outcome kind so
        // INSIGHT / VIEW_CHANGE surface their artefact and QUERY is actually executed — previously
        // this path echoed only {model, request} and silently dropped insight/view-change answers.
        return buildAskSuccessResponse(outcome);
    }

    @POST
    @Path("/ask")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ask(AiAskApi.AskRequest body) {
        Response pre = validateAskPreamble(body, true);
        if (pre != null) {
            return pre;
        }
        AiAskService.AskOutcome outcome = askService.ask(
                body.getCube(),
                body.getQuestion(),
                body.historyAsMessages(),
                body.getCellsetDigest(),
                parseForceTool(body.getForceTool()),
                body.getCurrentQuery());
        if (outcome.degraded()) {
            AiAskApi.AskResponse out = new AiAskApi.AskResponse();
            out.setDegraded(true);
            out.setReason(outcome.reason());
            out.setModel(outcome.model());
            int status = outcome.reason() != null && outcome.reason().startsWith("AI ask is not configured")
                    ? Response.Status.SERVICE_UNAVAILABLE.getStatusCode()
                    : Response.Status.OK.getStatusCode();
            return Response.status(status)
                    .entity(out)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        return buildAskSuccessResponse(outcome);
    }

    /**
     * Streaming variant of {@link #ask(AiAskApi.AskRequest)} (saiku#1433). Same request body,
     * same response envelope, but the endpoint speaks Server-Sent Events so embedded chat surfaces
     * can render progress as it arrives — the "assistant is typing…" affordance without waiting on
     * the full round-trip.
     *
     * <p>Wire format (WHATWG SSE per <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html">
     * the HTML spec</a>):
     *
     * <pre>{@code
     * event: model
     * data: {"model":"claude-sonnet-4-6"}
     *
     * event: intent
     * data: {"kind":"INSIGHT"}
     *
     * event: chunk
     * data: {"delta":"Store "}
     *
     * event: chunk
     * data: {"delta":"Sales trended up 12% week-on-week..."}
     *
     * event: final
     * data: {"degraded":false,"model":"...","insight":{...}}
     * }</pre>
     *
     * <p><strong>Streaming semantics (v1).</strong> The underlying provider call is still
     * synchronous — the LLM's tool-use response is emitted whole. The endpoint then chunks any
     * prose fields (insight markdown, view-change reason) into word-sized deltas so the client
     * gets a progressive render experience. True per-token streaming from the LLM provider is a
     * follow-up (both Anthropic and OpenAI expose streaming APIs, but their tool-use streaming
     * payloads are non-trivial to accumulate at the AbstractNlAskProvider seam). The wire shape
     * is stable; a future PR that plugs in real LLM streaming won't require client changes.
     *
     * <p>Rate limiter + size cap + policy gate + auth are identical to {@link
     * #ask(AiAskApi.AskRequest)} — the streaming variant isn't a bypass surface.
     */
    @POST
    @Path("/ask/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("text/event-stream")
    public Response askStream(AiAskApi.AskRequest body) {
        Response pre = validateAskPreamble(body, true);
        if (pre != null) {
            return pre;
        }
        org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool force = parseForceTool(body.getForceTool());
        return streamAsk(
                () -> askService.ask(
                        body.getCube(),
                        body.getQuestion(),
                        body.historyAsMessages(),
                        body.getCellsetDigest(),
                        force,
                        body.getCurrentQuery()),
                "AI ask (streaming)");
    }

    /**
     * Space-scoped streaming ask (saiku#1440 + #1433). Same wire format as {@link
     * #askStream(AiAskApi.AskRequest)} — the events are indistinguishable to the client — but
     * routes through {@link AiAskService#askInSpace} so the persona's system prompt, cube
     * allowlist, and skill filter apply. A caller who wants both space scoping AND progressive
     * chat UX uses this endpoint; the two features were always meant to compose.
     *
     * <p>Same rate limit + size cap + policy gate + auth as the classic {@code
     * /spaces/{id}/ask}. Space-not-found + FORBIDDEN outcomes surface as {@code error} events
     * followed by a degraded {@code final} — the client sees the same SSE shape regardless of
     * whether the failure was provider-side or scope-side.
     */
    @POST
    @Path("/spaces/{id}/ask/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("text/event-stream")
    public Response askInSpaceStream(@PathParam("id") String spaceId, AiAskApi.AskRequest body) {
        Response pre = validateAskPreamble(body, false);
        if (pre != null) {
            return pre;
        }
        // Pre-flight the persona scope BEFORE committing to a 200 event-stream, so a scope denial
        // maps to a real 403/404/503 rather than a 200 carrying an in-band SSE error (saiku#1454).
        // The post-LLM re-check of the model's emitted cube (saiku#1453) still runs in askInSpace.
        Response scopeDenial = mapSpaceAccessDenial(askService.checkSpaceAccess(spaceId, body.getCube()), spaceId);
        if (scopeDenial != null) {
            return scopeDenial;
        }
        org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool force = parseForceTool(body.getForceTool());
        return streamAsk(
                () -> askService.askInSpace(
                        spaceId,
                        body.getCube(),
                        body.getQuestion(),
                        body.historyAsMessages(),
                        body.getCellsetDigest(),
                        force,
                        body.getCurrentQuery()),
                "AI ask-in-space (streaming)");
    }

    /**
     * Streaming variant of the chained ask loop (Task 7, feature-email). Runs {@link
     * AiAskService#askChained} — the bounded server-side agentic loop that builds a query, executes
     * it, feeds the result back, and lets the model report on it, all in one request — and streams
     * every step over SSE so the UI can hydrate the workspace as soon as the query step arrives and
     * render the report the moment the terminal step lands, without waiting on the whole chain.
     *
     * <p>Same preamble (auth/size/rate/policy/configured) as every other ask endpoint — one chain
     * counts as one rate-limited unit, exactly like a single-turn ask. See {@link
     * #streamChainAsSse(AiAskService.AskChain, SseWriter)} for the wire contract.
     */
    @POST
    @Path("/ask/chain/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("text/event-stream")
    public Response askChainStream(AiAskApi.AskRequest body) {
        Response pre = validateAskPreamble(body, true);
        if (pre != null) {
            return pre;
        }
        org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool force = parseForceTool(body.getForceTool());
        return streamChain(
                () -> askService.askChained(
                        body.getCube(),
                        body.getQuestion(),
                        body.historyAsMessages(),
                        body.getCellsetDigest(),
                        force,
                        body.getCurrentQuery()),
                "AI ask (chained, streaming)");
    }

    /**
     * AI dashboard-builder (D1, feature-email): from one natural-language request the model authors
     * a MULTI-TILE dashboard spec (N query-tiles) the frontend drops into Saiku's real dashboard
     * model. NON-streaming — a dashboard spec is one provider turn, so there's nothing to progress
     * over SSE.
     *
     * <p>Same preamble as every other ask endpoint (auth/size/rate/policy/configured) — a dashboard
     * build is one LLM call, so it counts as one unit against the shared AI rate budget via {@link
     * #askRateLimiter}. Unlike the report path, this path sends NO cell data to the LLM (only the
     * PII-filtered schema + the model's own query specs), so it is not gated on {@code
     * ai.llm.egress=aggregated}. On success returns the {@link DashboardSpec} (200). On a degrade
     * the same 200-with-degraded-field envelope every other ask surface uses.
     */
    @POST
    @Path("/ask/dashboard")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response askDashboard(AiAskApi.AskRequest body) {
        Response pre = validateAskPreamble(body, true);
        if (pre != null) {
            return pre;
        }
        // v1 runs the classic (non-space-scoped) path — space is null, matching the chained-ask
        // endpoint's v1 posture. The AskRequest wire shape carries no space id.
        DashboardSpec spec =
                askService.buildDashboard(body.getCube(), body.getQuestion(), body.historyAsMessages(), null);
        return Response.ok(spec).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Translate one ask outcome into the SSE event sequence documented on {@link
     * #askStream(AiAskApi.AskRequest)}: {@code model} → {@code intent} → 0+ {@code chunk} → {@code
     * final}, or {@code model} → {@code error} → degraded-{@code final} when the provider failed.
     *
     * <p>Package-visible for unit testing. Called by both {@link #askStream} and {@link
     * #askInSpaceStream}; the two endpoints differ only in which {@code AiAskService} entry
     * point they invoke.
     */
    void streamOutcomeAsSse(AiAskService.AskOutcome outcome, SseWriter sse) throws java.io.IOException {
        // model event — always fired first so the client can show which backend answered.
        if (outcome.model() != null) {
            sse.event("model", MAPPER.writeValueAsString(java.util.Map.of("model", outcome.model())));
        }

        if (outcome.degraded()) {
            // Degraded path — emit an error event with the provider's reason, then a final.
            sse.event(
                    "error",
                    MAPPER.writeValueAsString(
                            java.util.Map.of("reason", outcome.reason() == null ? "" : outcome.reason())));
            AiAskApi.AskResponse out = new AiAskApi.AskResponse();
            out.setDegraded(true);
            out.setReason(outcome.reason());
            if (outcome.model() != null) out.setModel(outcome.model());
            sse.event("final", MAPPER.writeValueAsString(out));
            return;
        }

        sse.event(
                "intent",
                MAPPER.writeValueAsString(java.util.Map.of(
                        "kind", outcome.kind() == null ? "" : outcome.kind().name())));

        AiAskApi.AskResponse out = new AiAskApi.AskResponse();
        out.setDegraded(false);
        out.setModel(outcome.model());

        if (outcome.kind() == AiAskService.AskOutcome.Kind.INSIGHT) {
            out.setInsight(outcome.insight());
            String markdown = outcome.insight() == null ? "" : outcome.insight().getMarkdown();
            if (markdown != null && !markdown.isEmpty()) {
                emitChunks(sse, markdown);
            }
            sse.event("final", MAPPER.writeValueAsString(out));
            return;
        }

        if (outcome.kind() == AiAskService.AskOutcome.Kind.VIEW_CHANGE) {
            out.setViewChange(outcome.viewChange());
            String reason =
                    outcome.viewChange() == null ? null : outcome.viewChange().getReason();
            if (reason != null && !reason.isEmpty()) {
                emitChunks(sse, reason);
            }
            sse.event("final", MAPPER.writeValueAsString(out));
            return;
        }

        if (outcome.kind() == AiAskService.AskOutcome.Kind.EMAIL_DRAFT) {
            if (!mailConfigured()) {
                // Task 3: same honest refusal as the sync path (buildAskSuccessResponse), mirrored
                // into the streaming wire shape — error event carrying the reason, then a degraded
                // final so a client keying completion on `final` never hangs.
                sse.event("error", MAPPER.writeValueAsString(java.util.Map.of("reason", MAIL_NOT_CONFIGURED_REASON)));
                out.setDegraded(true);
                out.setReason(MAIL_NOT_CONFIGURED_REASON);
                sse.event("final", MAPPER.writeValueAsString(out));
                return;
            }
            out.setEmailDraft(outcome.emailDraft());
            String summary =
                    outcome.emailDraft() == null ? "" : outcome.emailDraft().getSummary();
            if (summary != null && !summary.isEmpty()) {
                emitChunks(sse, summary);
            }
            sse.event("final", MAPPER.writeValueAsString(out));
            return;
        }

        // QUERY intent — no prose to stream; the query itself IS the artefact. Execute the same way
        // the sync endpoint does (shared helper — saiku#1455/#1460) and emit the final envelope in
        // one event so the client accumulates a complete AskResponse.
        out.setRequest(outcome.request());
        executeQueryIntoResponse(out, outcome.request());
        sse.event("final", MAPPER.writeValueAsString(out));
    }

    /**
     * Stream an {@link AiAskService.AskChain}: each step reuses the single-step envelope shape
     * ({@link AiAskApi.AskResponse}), but only the LAST step emits {@code final} — the client's
     * completion signal — while every earlier step emits {@code step} instead. Per step:
     *
     * <pre>{@code
     * event: intent
     * data: {"kind":"QUERY","index":0}
     *
     * event: step            (or `final` on the last step)
     * data: {"degraded":false,"model":"...","request":{...}}
     * }</pre>
     *
     * <p><strong>QUERY steps never re-execute.</strong> The chained loop already ran the query
     * server-side (to feed the model the result); this only streams {@code request} so the client
     * hydrates the workspace and re-renders — mirroring today's non-streaming QUERY path. Calling
     * {@link #executeQueryIntoResponse} here would run the query a second time for no benefit.
     * INSIGHT / VIEW_CHANGE / EMAIL_DRAFT steps carry their artefact plus chunked prose exactly as
     * {@link #streamOutcomeAsSse} does. A degraded step emits {@code error} then its terminal
     * envelope, matching the single-turn contract.
     *
     * <p>When {@link AiAskService.AskChain#hitStepLimit()} is true, the loop stopped at the cap
     * while still building queries (no report was produced). {@code final} has already fired by
     * then, so an informational {@code note} event is emitted afterward — the client has already
     * completed on {@code final}; treat {@code note} as a toast, not part of the completion gate.
     *
     * <p>Package-visible for unit testing (mirrors {@link #streamOutcomeAsSse}'s test seam).
     */
    void streamChainAsSse(AiAskService.AskChain chain, SseWriter sse) throws java.io.IOException {
        List<AiAskService.AskOutcome> steps = chain.steps();

        // model event once — the first step that carries a model id, fired before any step content.
        String model = steps.stream()
                .map(AiAskService.AskOutcome::model)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (model != null) {
            sse.event("model", MAPPER.writeValueAsString(java.util.Map.of("model", model)));
        }

        for (int i = 0; i < steps.size(); i++) {
            AiAskService.AskOutcome step = steps.get(i);
            boolean last = i == steps.size() - 1;
            String eventName = last ? "final" : "step";

            if (step.degraded()) {
                sse.event(
                        "error",
                        MAPPER.writeValueAsString(
                                java.util.Map.of("reason", step.reason() == null ? "" : step.reason())));
                AiAskApi.AskResponse out = new AiAskApi.AskResponse();
                out.setDegraded(true);
                out.setReason(step.reason());
                if (step.model() != null) {
                    out.setModel(step.model());
                }
                sse.event(eventName, MAPPER.writeValueAsString(out));
                continue;
            }

            sse.event(
                    "intent",
                    MAPPER.writeValueAsString(java.util.Map.of(
                            "kind", step.kind() == null ? "" : step.kind().name(), "index", i)));

            AiAskApi.AskResponse out = new AiAskApi.AskResponse();
            out.setDegraded(false);
            out.setModel(step.model());

            if (step.kind() == AiAskService.AskOutcome.Kind.INSIGHT) {
                out.setInsight(step.insight());
                String markdown = step.insight() == null ? "" : step.insight().getMarkdown();
                if (markdown != null && !markdown.isEmpty()) {
                    emitChunks(sse, markdown);
                }
            } else if (step.kind() == AiAskService.AskOutcome.Kind.VIEW_CHANGE) {
                out.setViewChange(step.viewChange());
                String reason =
                        step.viewChange() == null ? null : step.viewChange().getReason();
                if (reason != null && !reason.isEmpty()) {
                    emitChunks(sse, reason);
                }
            } else if (step.kind() == AiAskService.AskOutcome.Kind.EMAIL_DRAFT) {
                if (!mailConfigured()) {
                    sse.event(
                            "error", MAPPER.writeValueAsString(java.util.Map.of("reason", MAIL_NOT_CONFIGURED_REASON)));
                    out.setDegraded(true);
                    out.setReason(MAIL_NOT_CONFIGURED_REASON);
                    sse.event(eventName, MAPPER.writeValueAsString(out));
                    continue;
                }
                out.setEmailDraft(step.emailDraft());
                String summary =
                        step.emailDraft() == null ? "" : step.emailDraft().getSummary();
                if (summary != null && !summary.isEmpty()) {
                    emitChunks(sse, summary);
                }
            } else if (step.kind() == AiAskService.AskOutcome.Kind.QUERY) {
                // NO executeQueryIntoResponse — the chained loop already executed this query
                // server-side; the client hydrates the workspace from `request` and re-renders.
                out.setRequest(step.request());
            }

            sse.event(eventName, MAPPER.writeValueAsString(out));
        }

        if (chain.hitStepLimit()) {
            sse.event(
                    "note",
                    MAPPER.writeValueAsString(java.util.Map.of(
                            "reason", "Reached the step limit — returned the built query without an AI report.")));
        }
    }

    /**
     * Split {@code prose} into word-boundary chunks (keeping the whitespace attached to each
     * chunk so the client can concatenate deltas without needing to guess spacing) and emit each
     * as an SSE {@code chunk} event.
     *
     * <p>Package-private for a unit test. Cap on chunk count is loose — long insights emit
     * hundreds of small events, which is fine on modern HTTP but not free; if that becomes an
     * issue we can group tokens by sentence.
     */
    static void emitChunks(SseWriter sse, String prose) throws java.io.IOException {
        int len = prose.length();
        int i = 0;
        while (i < len) {
            // Find the next word boundary (whitespace run) and emit up to and including it so the
            // delta reads naturally when concatenated.
            int wordEnd = i;
            while (wordEnd < len && !Character.isWhitespace(prose.charAt(wordEnd))) wordEnd++;
            while (wordEnd < len && Character.isWhitespace(prose.charAt(wordEnd))) wordEnd++;
            String chunk = prose.substring(i, wordEnd);
            if (!chunk.isEmpty()) {
                sse.event("chunk", "{\"delta\":" + jsonString(chunk) + "}");
            }
            i = wordEnd;
        }
    }

    /**
     * JSON-string escape for a raw text chunk. Kept package-visible for the emitChunks test; used
     * verbatim in {@link #emitChunks} to avoid an extra Jackson serialisation round-trip per token.
     */
    static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
        return b.toString();
    }

    /**
     * Minimal SSE frame writer. Flushes after each event so a slow client sees progress rather
     * than a batched dump at close. Package-private for testing.
     */
    static final class SseWriter {
        private final java.io.Writer writer;

        SseWriter(java.io.Writer writer) {
            this.writer = writer;
        }

        /**
         * Emit one SSE event with a name and JSON data payload. Multi-line payloads are rare on
         * this endpoint (we control the JSON serialiser) but we still handle {@code \n} correctly
         * per spec — every line after the first is prefixed with {@code data: }.
         */
        void event(String name, String jsonData) throws java.io.IOException {
            writer.write("event: ");
            writer.write(name);
            writer.write('\n');
            // Each line of the payload gets its own `data: ` prefix per the SSE spec. Our JSON is
            // single-line but this keeps the writer honest if a future serialiser emits pretty-printed.
            for (String line : jsonData.split("\n", -1)) {
                writer.write("data: ");
                writer.write(line);
                writer.write('\n');
            }
            writer.write('\n');
            writer.flush();
        }
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
            return error("preview failed");
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
            return error("listing failed");
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
            // saiku#902: project to the redacted agent view before serializing.
            // The internal in-memory schema keeps captions + samples + synonyms
            // (the validator needs them for name resolution); the JSON we hand
            // back to the agent strips them for any level / measure tagged
            // {@code saiku.semantic.pii=true}.
            return Response.ok(schema.toAgentView())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("AI schema fetch failed for {}", cubeId, e);
            return error("schema fetch failed");
        }
    }

    /**
     * saiku#902 phase 3 — admin diagnostics for the PII scanner.
     *
     * <p>Returns the cached {@link org.saiku.service.olap.ai.PiiScanner.Match}
     * list for the cube — one entry per measure / level whose name matched a
     * documented PII pattern AND that is NOT already annotated
     * {@code saiku.semantic.pii=true}. The intent is operator-facing: surface
     * "you probably want to annotate these columns" hints in admin UI without
     * the operator having to grep their launcher logs.
     *
     * <p>The endpoint sits at {@code /ai/schema/{cubeId}/pii-suggestions}
     * rather than {@code /ai/schema/{cubeId}} so the suggestions never leak
     * into the agent-facing describe response — agents shouldn't see the
     * operator's draft policy. Admin gating happens via the JAX-RS layer's
     * usual {@code @RolesAllowed("ROLE_ADMIN")} when wired upstream; the
     * resource itself doesn't enforce auth so unit tests can call it
     * directly.
     *
     * <p>The cube must have been described at least once (via
     * {@link #getSchema(String)}) for the scanner to have run; an
     * unrecognised / unwarmed cube returns an empty list, not 404.
     */
    @GET
    @Path("/schema/{cubeId:.+}/pii-suggestions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPiiSuggestions(@PathParam("cubeId") String cubeId) {
        AiCubeRef ref = parseCubeId(cubeId);
        if (ref == null) {
            return badRequest("cubeId", "cubeId must be connection/catalog/schema/cube", null);
        }
        try {
            // Warm the schema cache first so a fresh suggestions request
            // doesn't return empty just because the cube hadn't been
            // described yet.
            cubeMetadataService.getSchema(ref);
            return Response.ok(java.util.Map.of("suggestions", cubeMetadataService.getPiiSuggestions(ref)))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (AiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (RuntimeException e) {
            log.error("PII suggestions fetch failed for {}", cubeId, e);
            return error("pii suggestions fetch failed");
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
            return error("member search failed");
        }
    }

    /** Parse a "connection/catalog/schema/cube" cubeId. Returns null on a malformed input. */
    public static AiCubeRef parseCubeId(String cubeId) {
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
            return error("validation failed");
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
            return error("submit failed");
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
        AsyncQueryHandle h = asyncQueryService.getOwned(queryId, currentPrincipal(), currentUserIsAdmin());
        if (h == null) {
            // Unknown id OR not owned by this caller — 404 on both so the
            // status code can't be used as an id-existence oracle (IDOR fix).
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
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
        if (asyncQueryService == null) return error("Async service not configured");
        AsyncQueryHandle h = asyncQueryService.getOwned(queryId, currentPrincipal(), currentUserIsAdmin());
        // Unknown id OR not owned by this caller — 404 on both (IDOR fix).
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
        String principal = currentPrincipal();
        boolean admin = currentUserIsAdmin();
        AsyncQueryHandle h = asyncQueryService.getOwned(queryId, principal, admin);
        // Unknown id OR not owned by this caller — 404 on both (IDOR fix).
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
        boolean ok = asyncQueryService.cancelOwned(queryId, principal, admin);
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
     *   <li><b>Per-cell</b> ({@code position=col:row}, column-axis index then
     *       row-axis index): drills the single cell at
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
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.RAW_ROW_DATA);
        if (thinQueryService == null) return error("Query service not configured");

        // Stage 1: resolve the underlying ThinQuery name (async handle
        // resolution + cross-session re-attach of external context).
        String name = resolveDrillthroughName(queryId);

        // Stage 2: rewrite the bare-caption returns clause to fully-qualified
        // MDX (saiku#782). A resolver AiValidationException short-circuits to
        // a 400; otherwise the resolved value is parked in resolvedReturns[0].
        String[] resolvedReturns = {returns};
        Response returnsError = rewriteDrillthroughReturns(name, returns, resolvedReturns);
        if (returnsError != null) return returnsError;

        // Stage 3: parse + validate the optional per-cell position (saiku#930)
        // before touching the engine so a malformed value is a clean 400.
        List<Integer>[] cellPositionHolder = parsePositionHolder();
        Response positionError = parseCellPosition(position, cellPositionHolder);
        if (positionError != null) return positionError;
        List<Integer> cellPosition = cellPositionHolder[0];

        // Stage 4: execute the drillthrough + serialise the response body,
        // translating the known failure modes to their typed envelopes.
        try {
            java.sql.ResultSet rs = cellPosition != null
                    ? thinQueryService.drillthrough(name, cellPosition, maxrows, resolvedReturns[0])
                    : thinQueryService.drillthrough(name, maxrows, firstRowset, resolvedReturns[0]);
            return serialiseDrillthroughBody(queryId, rs);
        } catch (NullPointerException e) {
            return translateDrillthroughNpe(queryId, e);
        } catch (Exception e) {
            return translateDrillthroughFailure(queryId, e);
        }
    }

    /**
     * Resolve a drillthrough/column-discovery {@code queryId} to the
     * underlying ThinQuery name. For async queries the handle id is not the
     * ThinQuery name, so we look up through the handle when available.
     *
     * <p>saiku#862: when the resolved query has no per-session context (a
     * drillthrough request that landed on a different session than the async
     * submit — Basic-auth clients without a shared cookie jar, some
     * load-balanced setups), re-attach the async query's ThinQuery + CellSet
     * to THIS request's session-scoped ThinQueryService context. Without this
     * the empty context map drives the NPE-catch path to surface
     * "Unknown queryId" even though the handle is live.
     */
    private String resolveDrillthroughName(String queryId) {
        String name = queryId;
        if (asyncQueryService != null) {
            // Owner-scoped: a non-owner who guesses/leaks another user's handle
            // id must not be able to re-attach that user's CellSet to their own
            // session and drill the fact rows (IDOR fix, #1165 audit-3). On an
            // ownership mismatch getOwned returns null, so we fall through to
            // name = queryId — the caller's own (empty) session context then
            // drives the standard "unknown queryId" 404 path.
            AsyncQueryHandle h = asyncQueryService.getOwned(queryId, currentPrincipal(), currentUserIsAdmin());
            if (h != null) {
                name = h.getQuery().getName();
                if (thinQueryService.getContext(name) == null) {
                    org.olap4j.CellSet cs = asyncQueryService.result(queryId);
                    thinQueryService.registerExternalContext(h.getQuery(), cs);
                }
            }
        }
        return name;
    }

    /**
     * saiku#782: agents only have bare captions (the keys of each row in a
     * drillthrough response) — accept those and rewrite to the fully qualified
     * MDX form Mondrian's RETURN clause expects. Tokens that are already
     * bracketed pass through unchanged, so callers that already speak MDX
     * aren't surprised. Validation errors from the resolver carry the
     * candidate list so the agent can self-correct without scraping /schema.
     *
     * <p>Writes the resolved value into {@code out[0]} (left as the raw
     * {@code returns} when no resolution applies). Returns a 400 Response when
     * the resolver raises {@link AiValidationException}; otherwise null.
     */
    private Response rewriteDrillthroughReturns(String name, String returns, String[] out) {
        if (returns == null || returns.trim().isEmpty() || cubeMetadataService == null) {
            return null;
        }
        try {
            org.saiku.service.util.QueryContext qc = thinQueryService.getContext(name);
            if (qc != null && qc.getOlapQuery() != null && qc.getOlapQuery().getCube() != null) {
                org.saiku.olap.dto.SaikuCube cube = qc.getOlapQuery().getCube();
                AiCubeRef ref =
                        new AiCubeRef(cube.getConnection(), cube.getCatalog(), cube.getSchema(), cube.getName());
                AiSchema schema = cubeMetadataService.getSchema(ref);
                out[0] = AiReturnsResolver.resolve(returns, schema);
            }
        } catch (AiPiiException pe) {
            // saiku#902: distinguish PII refusal from a regular validation
            // error in the audit log + structured response. Status is still
            // VALIDATION_ERROR (the agent's self-correction path is uniform —
            // look at field, pick from available); the log line carries the
            // explicit reason so a CISO reading the audit can confirm the
            // gate fired correctly. The candidate list here ALREADY excludes
            // PII columns (built by AiReturnsResolver.nonPiiCandidates).
            log.info("drillthrough returns= refused (PII column): name={} message={}", name, pe.getMessage());
            AiQueryResponse resp = new AiQueryResponse();
            resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
            resp.setError(pe.getMessage());
            resp.setField(pe.getField());
            if (pe.getAvailable() != null) resp.setAvailable(pe.getAvailable());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(resp)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
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
            // Schema lookup failure shouldn't poison the drillthrough — fall
            // through with the raw returns value and let Mondrian report
            // whatever it reports. The 400-translation block in
            // translateDrillthroughFailure handles the typical
            // "in RETURN clause" message.
        }
        return null;
    }

    /** Allocate the single-element holder for the parsed cell position. */
    @SuppressWarnings("unchecked")
    private static List<Integer>[] parsePositionHolder() {
        return (List<Integer>[]) new List[1];
    }

    /**
     * saiku#930: per-cell drillthrough. {@code position} is "col:row" cellset
     * coordinates — component i indexes cellset axis i (axis 0 = columns,
     * axis 1 = rows), matching {@link ThinQueryService#drillthrough}. Parsed +
     * validated before touching the engine so a malformed value is a clean
     * 400.
     *
     * <p>Writes the parsed coordinates into {@code out[0]} (left null when no
     * position is supplied — whole-result mode). Returns a 400 Response on a
     * non-numeric coordinate; otherwise null.
     */
    private Response parseCellPosition(String position, List<Integer>[] out) {
        if (position == null || position.trim().isEmpty()) {
            return null;
        }
        List<Integer> cellPosition = new ArrayList<>();
        for (String p : position.split(":")) {
            try {
                cellPosition.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException nfe) {
                AiQueryResponse resp = new AiQueryResponse();
                resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
                resp.setError(
                        "Malformed position '" + position
                                + "'. Expected \"col:row\" cell coordinates (column-axis index then row-axis index), e.g. \"2:1\".");
                resp.setField("position");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(resp)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
        }
        out[0] = cellPosition;
        return null;
    }

    /**
     * Serialise a drillthrough {@link java.sql.ResultSet} into the typed
     * response body — keys {@code queryId}, {@code rowCount}, {@code columns},
     * {@code rows}. The ResultSet is fully drained and closed here.
     *
     * <p>saiku#800: Mondrian's drillthrough sometimes returns the same
     * {@code getColumnLabel()} for multiple columns within a hierarchy (e.g.
     * Year + Quarter + Month all labelled "Quarter"). Disambiguated keys are
     * pre-computed ONCE before the row loop so the first occurrence keeps its
     * raw label (back-compat) and subsequent duplicates fall back to
     * {@code getColumnName(c)} when it differs, else a positional suffix
     * _2, _3, … Without this every row's Map.put silently overwrote the
     * earlier column, leaving the first column labelled with the LAST column's
     * value — silent data corruption. The explicit {@code columns[]} lets
     * agents read by position when the label heuristic still leaves ambiguity.
     */
    private Response serialiseDrillthroughBody(String queryId, java.sql.ResultSet rs) throws java.sql.SQLException {
        List<Map<String, AiCell>> rows = new ArrayList<>();
        List<String> columnLabels = new ArrayList<>();
        if (rs != null) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();
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
        body.put("columns", columnLabels);
        body.put("rows", rows);
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * saiku#783: {@link ThinQueryService#drillthrough} dereferences the
     * internal QueryContext map without a present-check; an unknown queryId
     * leaks an NPE with internal class names. Translate to a clean 404 with
     * the typed AiQueryResponse envelope (field=queryId).
     */
    private Response translateDrillthroughNpe(String queryId, NullPointerException e) {
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
    }

    /**
     * Translate a non-NPE drillthrough engine failure to its typed envelope:
     *
     * <ul>
     *   <li>saiku#794: an empty source cellset (drillthrough defaults to the
     *       (0,0) cell; "Cell coordinates (0, 0) fall outside CellSet bounds
     *       (0, 0)") surfaces as an empty drillthrough — 200 + rowCount=0 —
     *       rather than a generic 500.</li>
     *   <li>saiku#795: a bad {@code returns=} param surfaces as
     *       "Can't perform drillthrough operation on unknown member '&lt;ref&gt;'
     *       in RETURN clause" — lifted to a 400 with field=returns.</li>
     *   <li>anything else: a generic 500 via {@link #error}.</li>
     * </ul>
     */
    private Response translateDrillthroughFailure(String queryId, Exception e) {
        String m = e.getMessage();
        if (m != null && m.contains("fall outside CellSet bounds")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("queryId", queryId);
            body.put("rowCount", 0);
            body.put("rows", new ArrayList<>());
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        }
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
        return error("drillthrough failed");
    }

    /**
     * CSV export of a drillthrough (saiku#1051). Streams the same underlying
     * fact rows the JSON {@link #drillthrough} endpoint returns, but rendered
     * as an RFC-4180 CSV file with a {@code Content-Disposition: attachment}
     * header so a browser triggers a download.
     *
     * <p>Honours the exact same params as the JSON endpoint:
     * <ul>
     *   <li>{@code position=col:row} — per-cell drillthrough (saiku#930);
     *       omit for whole-result.</li>
     *   <li>{@code returns} — the projection (DRILLTHROUGH ... RETURN ...).
     *       Bare captions are resolved to qualified MDX exactly as the JSON
     *       path does (saiku#782).</li>
     *   <li>{@code maxrows} — row cap (defaults 100).</li>
     * </ul>
     *
     * <p>The CSV bytes are produced by {@link ThinQueryService#exportResultSetCsv(java.sql.ResultSet)}
     * — the SAME service path the workspace export ({@code Query2Resource.getDrillthroughExport})
     * uses — so quoting/escaping behaviour stays identical across the product.
     *
     * <p>Auth: inherits the {@code /rest/**} = isFullyAuthenticated rule; no
     * permitAll, not reachable by share guests.
     */
    @GET
    @Path("/query/{queryId}/drillthrough/export/csv")
    @Produces({"text/csv"})
    public Response drillthroughExportCsv(
            @PathParam("queryId") String queryId,
            @QueryParam("maxrows") @DefaultValue("100") int maxrows,
            @QueryParam("firstRowset") Integer firstRowset,
            @QueryParam("position") String position,
            @QueryParam("returns") String returns) {
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.RAW_ROW_DATA);
        if (thinQueryService == null) return error("Query service not configured");
        // Resolve async handle id -> underlying ThinQuery name, re-attaching the
        // async cellset to this session's context (saiku#862). Routed through the
        // shared OWNER-SCOPED resolver (getOwned) so a non-owner can't export
        // another user's drillthrough via a leaked/guessed queryId — the inlined
        // copy here used the unowned get() and re-attached cross-user (IDOR,
        // saiku#1284; mirrors the JSON + columns endpoints' #1208/#1165-audit-3 fix).
        String name = resolveDrillthroughName(queryId);
        // Resolve bare-caption returns to qualified MDX (saiku#782), mirroring
        // the JSON endpoint so the two stay behaviourally consistent.
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
                // Schema lookup failure shouldn't poison the export — fall
                // through with the raw returns value (see JSON path).
            }
        }
        // saiku#930: parse + validate position before touching the engine.
        List<Integer> cellPosition = null;
        if (position != null && !position.trim().isEmpty()) {
            cellPosition = new ArrayList<>();
            for (String p : position.split(":")) {
                try {
                    cellPosition.add(Integer.parseInt(p.trim()));
                } catch (NumberFormatException nfe) {
                    AiQueryResponse resp = new AiQueryResponse();
                    resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
                    resp.setError(
                            "Malformed position '" + position
                                    + "'. Expected \"col:row\" cell coordinates (column-axis index then row-axis index), e.g. \"2:1\".");
                    resp.setField("position");
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(resp)
                            .type(MediaType.APPLICATION_JSON)
                            .build();
                }
            }
        }
        java.sql.ResultSet rs = null;
        try {
            rs = cellPosition != null
                    ? thinQueryService.drillthrough(name, cellPosition, maxrows, resolvedReturns)
                    : thinQueryService.drillthrough(name, maxrows, firstRowset, resolvedReturns);
            byte[] doc = thinQueryService.exportResultSetCsv(rs);
            String fileName = SaikuProperties.webExportCsvName + "-drillthrough.csv";
            return Response.ok(doc, "text/csv")
                    .header("content-disposition", "attachment; filename=" + fileName)
                    .header("content-length", doc.length)
                    .build();
        } catch (NullPointerException e) {
            // Unknown queryId leaks an NPE on the internal QueryContext lookup
            // — translate to a clean 404 (same as the JSON path, saiku#783).
            log.warn("AI drillthrough CSV export on unknown queryId {} — translated to 404", queryId);
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
            // Empty source cellset — drillthrough at (0,0) falls outside an
            // empty cellset. Return an empty (header-less) CSV rather than a
            // 500, matching the JSON endpoint's empty-result handling.
            if (m != null && m.contains("fall outside CellSet bounds")) {
                byte[] doc = new byte[0];
                String fileName = SaikuProperties.webExportCsvName + "-drillthrough.csv";
                return Response.ok(doc, "text/csv")
                        .header("content-disposition", "attachment; filename=" + fileName)
                        .header("content-length", doc.length)
                        .build();
            }
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
            log.error("AI drillthrough CSV export failed for {}", queryId, e);
            return error("drillthrough CSV export failed");
        } finally {
            JdbcCleanup.closeQuietly(rs);
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
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
        if (thinQueryService == null) return error("Query service not configured");
        // saiku#862: same async-handle resolution + cross-session re-attach as
        // drillthrough() so column discovery resolves on a different session.
        String name = resolveDrillthroughName(queryId);
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
            return error("drillthrough column discovery failed");
        }
    }

    /* ------------------------------------------------------------------ */

    private AiQueryResponse buildResponse(ThinQuery tq, CellDataSet cds, long startedAt) {
        return buildResponse(tq, cds, startedAt, "records");
    }

    private AiQueryResponse buildResponse(ThinQuery tq, CellDataSet cds, long startedAt, String format) {
        return buildResponse(
                tq, cds, startedAt, format, java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    /**
     * @param requestedMeasureLabelGroups one label-group per measure the caller
     *     asked for; each group holds every label that measure is known by
     *     (canonical name, display-name rename, raw requested name/synonym),
     *     with the surfacing label first. GROUPED (not flat) so a present
     *     measure requested via a synonym or display rename isn't falsely
     *     flagged unavailable. Used to detect requested-but-absent measures
     *     (saiku#1780) — a measure with no join path to a filtered dimension is
     *     dropped from the result by Mondrian, and would otherwise vanish
     *     silently.
     * @param filterDimensions dimensions on the slicer, used to phrase the
     *     "no join path" reason. May be empty.
     */
    private AiQueryResponse buildResponse(
            ThinQuery tq,
            CellDataSet cds,
            long startedAt,
            String format,
            List<List<String>> requestedMeasureLabelGroups,
            List<String> filterDimensions) {
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
            // saiku#1324: index -> caption for the matrix payload, captured as
            // the matrix is built, so k-anon can find the count column (matrix
            // cells are keyed by index, not caption).
            List<String> matrixColumnCaptions = new ArrayList<>();

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
                        if (matrixColumnCaptions.size() <= c) {
                            String cap = body[0][c] == null ? ("col" + c) : safe(body[0][c].getFormattedValue());
                            matrixColumnCaptions.add(cap.isEmpty() ? ("col" + c) : cap);
                        }
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
                        // saiku#1780: guard row[c] — a ragged/short row (fewer
                        // cells than totalWidth) must not throw AIOOBE.
                        for (int c = rowHeaderCount; c < totalWidth && c < row.length; c++) {
                            int colIdx = c - rowHeaderCount;
                            cells.put(String.valueOf(colIdx), toCell(row[c]));
                            if (matrixColumnCaptions.size() <= colIdx) {
                                matrixColumnCaptions.add(
                                        colIdx < cols.size() ? cols.get(colIdx).getCaption() : ("col" + colIdx));
                            }
                        }
                        matrix.add(cells);
                    } else {
                        Map<String, Object> record = new LinkedHashMap<>();
                        // Row-header columns first, named by their captions.
                        for (int c = 0; c < rowHeaderCount && c < row.length; c++) {
                            String key = c < rowHeaderCaptions.size() ? rowHeaderCaptions.get(c) : ("row" + c);
                            record.put(key, row[c] == null ? "" : safe(row[c].getFormattedValue()));
                        }
                        // Data cells next, named by column caption. saiku#1780:
                        // guard row[c] — a short row must not throw AIOOBE.
                        for (int c = rowHeaderCount; c < totalWidth && c < row.length; c++) {
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

            // saiku#1780: a requested measure with no join path to a filtered
            // dimension is dropped from the result by Mondrian — it never
            // becomes a column. Previously it vanished silently: the agent got
            // fewer columns than it asked for with no explanation. Detect those
            // and surface each explicitly with a null-valued, self-describing
            // "unavailable" cell (VALIDATION-style reason) so the caller can act
            // on it. Additive: normal queries (nothing dropped) are unchanged.
            //
            // Present columns come from the ACTUAL emitted payload (record keys
            // minus row-headers / matrix captions), not just header-derived
            // `cols` — the measures-only shape puts measure captions in the
            // record keys with an empty header, so `cols` would be empty there.
            List<String> presentColumns = new ArrayList<>();
            if (useMatrix) {
                presentColumns.addAll(matrixColumnCaptions);
            } else {
                java.util.Set<String> rowHeaderKeys = new java.util.HashSet<>(rowHeaderCaptions);
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                for (Map<String, Object> record : records) {
                    for (String k : record.keySet()) {
                        if (!rowHeaderKeys.contains(k)) seen.add(k);
                    }
                }
                presentColumns.addAll(seen);
            }
            Map<String, AiCell> dropped =
                    detectDroppedMeasures(requestedMeasureLabelGroups, presentColumns, filterDimensions);
            if (!dropped.isEmpty()) {
                // Record the dropped names in metadata.measures too, so the
                // measures list reflects everything requested, not just what
                // produced a column.
                for (String d : dropped.keySet()) {
                    if (!measureNames.contains(d)) measureNames.add(d);
                }
                if (useMatrix) {
                    // Matrix cells are keyed by column index; append the dropped
                    // measures as fresh trailing columns on every row.
                    int base = matrixColumnCaptions.size();
                    for (Map.Entry<String, AiCell> e : dropped.entrySet()) {
                        matrixColumnCaptions.add(e.getKey());
                    }
                    if (matrix.isEmpty()) matrix.add(new LinkedHashMap<>());
                    for (Map<String, AiCell> rowCells : matrix) {
                        int idx = base;
                        for (Map.Entry<String, AiCell> e : dropped.entrySet()) {
                            rowCells.put(String.valueOf(idx++), e.getValue());
                        }
                    }
                } else {
                    if (records.isEmpty()) records.add(new LinkedHashMap<>());
                    for (Map<String, Object> record : records) {
                        for (Map.Entry<String, AiCell> e : dropped.entrySet()) {
                            record.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
                }
            }
            meta.setMeasures(measureNames);

            // saiku#905 / #1324: k-anonymity small-cell suppression on BOTH
            // egress shapes (mutates cells in place; resp already holds the
            // list). The matrix path was the #1324 bypass — it's now suppressed
            // too, via the index->caption map captured above.
            if (useMatrix) {
                applyKAnonymityMatrix(matrix, matrixColumnCaptions);
            } else {
                applyKAnonymity(records);
            }
        }
        resp.setRuntimeMs(System.currentTimeMillis() - startedAt);
        return resp;
    }

    /**
     * saiku#1780 — compare the measures the caller requested against the
     * columns that actually came back, and build a self-describing
     * "unavailable" cell for every requested measure that produced no column.
     *
     * <p>A measure with no join path to a filtered/sliced dimension is dropped
     * from the result by Mondrian, so it never appears as a result column.
     *
     * <p>Labels are GROUPED per measure: each inner list holds every label a
     * single requested measure is known by (canonical name, display-name
     * rename, raw requested name/synonym), surfacing label first. A measure is
     * "dropped" ONLY IF NONE of its labels appears in the present-column set —
     * matched normalised (trim + case-insensitive). This is the load-bearing
     * correctness rule: a PRESENT measure requested via a synonym (#818) or
     * carrying a display rename (Phase-3 <datasource>.generated.json) must NOT
     * be flagged just because one of its non-caption labels isn't a column.
     * Each dropped measure yields exactly ONE unavailable cell, keyed by its
     * surfacing label; a present measure yields ZERO, no matter how many labels
     * it carries.
     *
     * <p>Package-private + static for unit-testability.
     *
     * @return an insertion-ordered map of dropped measure label → unavailable
     *     cell. Empty (never null) when nothing was dropped — the common case.
     */
    static Map<String, AiCell> detectDroppedMeasures(
            List<List<String>> requestedMeasureLabelGroups,
            List<String> presentColumns,
            List<String> filterDimensions) {
        Map<String, AiCell> dropped = new LinkedHashMap<>();
        if (requestedMeasureLabelGroups == null || requestedMeasureLabelGroups.isEmpty()) return dropped;

        java.util.Set<String> present = new java.util.HashSet<>();
        if (presentColumns != null) {
            for (String col : presentColumns) {
                if (col != null) present.add(col.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }

        String reason = droppedMeasureReason(filterDimensions);

        for (List<String> group : requestedMeasureLabelGroups) {
            if (group == null || group.isEmpty()) continue;

            // A measure is present if ANY of its labels matches a column.
            boolean anyPresent = false;
            String surfacingLabel = null;
            for (String label : group) {
                if (label == null || label.trim().isEmpty()) continue;
                String trimmed = label.trim();
                if (surfacingLabel == null) surfacingLabel = trimmed; // first non-blank = surfacing label
                if (present.contains(trimmed.toLowerCase(java.util.Locale.ROOT))) {
                    anyPresent = true;
                    break;
                }
            }
            if (anyPresent || surfacingLabel == null) continue;

            // Dropped: emit exactly one cell, keyed by the surfacing label.
            dropped.putIfAbsent(surfacingLabel, AiCell.unavailable(reason));
        }
        return dropped;
    }

    /** Phrase the "no join path" reason from the filtered dimensions. */
    private static String droppedMeasureReason(List<String> filterDimensions) {
        if (filterDimensions == null || filterDimensions.isEmpty()) {
            return "no join path to a filtered dimension";
        }
        java.util.LinkedHashSet<String> dims = new java.util.LinkedHashSet<>();
        for (String d : filterDimensions) {
            if (d != null && !d.trim().isEmpty()) dims.add(d.trim());
        }
        if (dims.isEmpty()) return "no join path to a filtered dimension";
        return "no join path to filtered dimension(s): " + String.join(", ", dims);
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

    /**
     * saiku#scenario — Mondrian write-back "what-if". Given a level and a target member on that
     * level, write a new value for one member's measure cell into a fresh Mondrian scenario
     * ({@code cell.setValue(v, allocationPolicy)}), then re-run the same query under the scenario so
     * the engine re-allocates the delta and every sibling/total reflects it. Returns actual vs
     * what-if per member. The cube must be declared {@code enableScenarios="true"}.
     *
     * <p>Body: {@code {connection, cube, level, measure, member, value, policy?}} where
     * {@code level}/{@code member} are MDX unique names and {@code value} is the new absolute
     * measure value for {@code member}. {@code policy} defaults to {@code EQUAL_ALLOCATION}.
     */
    @POST
    @Path("/scenario/whatif")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response scenarioWhatIf(java.util.Map<String, Object> body) {
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
        if (body == null) return badRequest("body", "request body required", null);
        String connection = str(body.get("connection"));
        String cube = str(body.get("cube"));
        String level = str(body.get("level"));
        String measure = str(body.get("measure"));
        String member = str(body.get("member"));
        Object rawValue = body.get("value");
        if (connection == null) return badRequest("connection", "connection is required", null);
        if (cube == null || level == null || measure == null || member == null || rawValue == null) {
            return badRequest("body", "cube, level, measure, member and value are required", null);
        }
        double newValue;
        try {
            newValue = Double.parseDouble(String.valueOf(rawValue));
        } catch (NumberFormatException e) {
            return badRequest("value", "value must be numeric", null);
        }
        org.olap4j.AllocationPolicy policy;
        try {
            policy = org.olap4j.AllocationPolicy.valueOf(
                    str(body.get("policy")) == null
                            ? "EQUAL_ALLOCATION"
                            : str(body.get("policy")).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            policy = org.olap4j.AllocationPolicy.EQUAL_ALLOCATION;
        }

        org.olap4j.OlapConnection con = null;
        org.olap4j.Scenario prior = null;
        try {
            con = olapDiscoverService.getNativeConnection(connection);
            prior = con.getScenario();
            org.olap4j.Scenario scenario = con.createScenario();
            con.setScenario(scenario);
            // The query MUST be scoped to the scenario member in the WHERE clause, or it just
            // reads actuals — the write-back lives on [Scenario].[<id>].
            String mdx = "SELECT {[Measures].[" + measure + "]} ON COLUMNS, " + level
                    + ".Members ON ROWS FROM [" + cube + "] WHERE [Scenario].[Scenario].[" + scenario.getId()
                    + "]";
            log.info("Scenario what-if MDX: {}", mdx);
            try (org.olap4j.OlapStatement st = con.createStatement()) {
                // 1) actuals under the (empty) scenario
                org.olap4j.CellSet actual = st.executeOlapQuery(mdx);
                java.util.List<org.olap4j.Position> rows =
                        actual.getAxes().get(1).getPositions();
                java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
                int targetRow = -1;
                for (int i = 0; i < rows.size(); i++) {
                    org.olap4j.metadata.Member m = rows.get(i).getMembers().get(0);
                    double a = cellDouble(actual.getCell(
                            actual.getAxes().get(0).getPositions().get(0), rows.get(i)));
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("member", m.getUniqueName());
                    row.put("caption", m.getCaption());
                    row.put("actual", a);
                    out.add(row);
                    if (m.getUniqueName().equals(member)) targetRow = i;
                }
                if (targetRow < 0) {
                    return badRequest(
                            "member",
                            "member not found on the level",
                            out.stream().map(r -> (String) r.get("member")).toList());
                }
                // Secondary breakdowns (payer type + month). Capture the portfolio actuals AND
                // the changed member's OWN mix now (scenario still empty). Mondrian's scenario
                // re-allocation reflects in the write-back's own dimension but not in
                // cross-dimension aggregates, so we distribute the portfolio delta across payer /
                // month in proportion to the changed member's mix — which is exactly where the
                // re-allocated value lands.
                String payerLevel = str(body.get("payerLevel"));
                String trendLevel = str(body.get("trendLevel"));
                String memberSlicer = "(" + member + ")";
                java.util.LinkedHashMap<String, Double> payerAct =
                        payerLevel == null ? null : queryLevelAgg(st, measure, payerLevel, cube, null);
                java.util.LinkedHashMap<String, Double> payerMix =
                        payerLevel == null ? null : queryLevelAgg(st, measure, payerLevel, cube, memberSlicer);
                java.util.LinkedHashMap<String, Double> trendAct =
                        trendLevel == null ? null : queryLevelAgg(st, measure, trendLevel, cube, null);
                java.util.LinkedHashMap<String, Double> trendMix =
                        trendLevel == null ? null : queryLevelAgg(st, measure, trendLevel, cube, memberSlicer);

                // 2) write the new value into the scenario (engine re-allocates the delta)
                actual.getCell(actual.getAxes().get(0).getPositions().get(0), rows.get(targetRow))
                        .setValue(newValue, policy);

                // 3) re-run the main breakdown under the scenario — reflects the write-back
                org.olap4j.CellSet whatif = st.executeOlapQuery(mdx);
                java.util.List<org.olap4j.Position> wrows =
                        whatif.getAxes().get(1).getPositions();
                double actualTotal = 0, whatifTotal = 0;
                for (int i = 0; i < wrows.size(); i++) {
                    double w = cellDouble(whatif.getCell(
                            whatif.getAxes().get(0).getPositions().get(0), wrows.get(i)));
                    out.get(i).put("whatif", w);
                    actualTotal += (double) out.get(i).get("actual");
                    whatifTotal += w;
                }
                double delta = whatifTotal - actualTotal;
                java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
                resp.put("measure", measure);
                resp.put("policy", policy.name());
                resp.put("member", member);
                resp.put("rows", out);
                resp.put("actualTotal", actualTotal);
                resp.put("whatifTotal", whatifTotal);
                if (payerAct != null) resp.put("payer", distributeDelta(payerAct, payerMix, delta));
                if (trendAct != null) resp.put("trend", distributeDelta(trendAct, trendMix, delta));
                return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
            }
        } catch (RuntimeException | java.sql.SQLException e) {
            log.warn("Scenario what-if failed", e);
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return error("what-if failed: " + e.getMessage() + " | root: "
                    + root.getClass().getSimpleName() + ": " + root.getMessage());
        } finally {
            if (con != null) {
                try {
                    con.setScenario(prior);
                } catch (Exception ignore) {
                    // best-effort reset
                }
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    /**
     * Run a single-measure level query, summing by member caption. {@code whereClause} (the
     * content after {@code WHERE}, e.g. {@code ([Product].[Product].[Oncology])}) is appended when
     * non-null — used to slice a breakdown to the changed member. Null = portfolio actuals.
     */
    private java.util.LinkedHashMap<String, Double> queryLevelAgg(
            org.olap4j.OlapStatement st, String measure, String levelUnique, String cube, String whereClause)
            throws java.sql.SQLException {
        String mdx = "SELECT {[Measures].[" + measure + "]} ON COLUMNS, " + levelUnique + ".Members ON ROWS FROM ["
                + cube + "]" + (whereClause == null ? "" : " WHERE " + whereClause);
        org.olap4j.CellSet cs = st.executeOlapQuery(mdx);
        java.util.List<org.olap4j.Position> rows = cs.getAxes().get(1).getPositions();
        org.olap4j.Position col0 = cs.getAxes().get(0).getPositions().get(0);
        java.util.LinkedHashMap<String, Double> m = new java.util.LinkedHashMap<>();
        for (org.olap4j.Position rp : rows) {
            m.merge(rp.getMembers().get(0).getCaption(), cellDouble(cs.getCell(col0, rp)), Double::sum);
        }
        return m;
    }

    /**
     * Build {caption, actual, whatif} rows by distributing {@code delta} across the actuals in
     * proportion to {@code mix} (the changed member's own breakdown at this level). Where the
     * changed member concentrates, the what-if moves most — matching how the write-back re-allocates.
     */
    private static java.util.List<java.util.Map<String, Object>> distributeDelta(
            java.util.LinkedHashMap<String, Double> act, java.util.LinkedHashMap<String, Double> mix, double delta) {
        double mixTotal = 0;
        if (mix != null) {
            for (double v : mix.values()) mixTotal += v;
        }
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Double> e : act.entrySet()) {
            double share = (mix != null && mixTotal != 0) ? mix.getOrDefault(e.getKey(), 0.0) / mixTotal : 0;
            java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("caption", e.getKey());
            r.put("actual", e.getValue());
            r.put("whatif", e.getValue() + delta * share);
            out.add(r);
        }
        return out;
    }

    private static double cellDouble(org.olap4j.Cell c) {
        if (c == null || c.isNull()) return 0d;
        Object v = c.getValue();
        return v instanceof Number ? ((Number) v).doubleValue() : 0d;
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

    /**
     * saiku#1151: a degraded {@link AiAskApi.AskResponse} for a size/rate
     * rejection at the given HTTP status (413 oversize, 400 bad shape, 429 rate).
     * Reuses the same envelope the configured/degraded paths use so the UI
     * renders a clear message rather than choking on an unexpected shape.
     */
    private Response askLimitResponse(int status, String reason) {
        AiAskApi.AskResponse out = new AiAskApi.AskResponse();
        out.setDegraded(true);
        out.setReason(reason);
        return Response.status(status)
                .entity(out)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** Standard client-facing reason for a 503 when no LLM provider is wired. */
    private static final String ASK_NOT_CONFIGURED_REASON =
            "AI ask is not configured. Set saiku.ai.ask.provider to 'anthropic' or 'openai' and "
                    + "supply the matching API key (env: ANTHROPIC_API_KEY or OPENAI_API_KEY) to "
                    + "enable the feature.";

    /**
     * Task 3 (NL email-draft slice): client-facing reason when the model picked {@code
     * EMAIL_DRAFT} but this server has no mail transport wired ({@link #mailConfigured()} false).
     * An honest refusal, not a 503 — the ask itself succeeded, only delivery isn't available.
     */
    private static final String MAIL_NOT_CONFIGURED_REASON = "Email isn't set up on this server.";

    /**
     * Shared validation preamble for every ask endpoint (sync + streaming, classic + space).
     * Runs the policy gate, shape checks, size cap, rate limit and provider-configured check in
     * one place so a guard change can't drift across the four copies (saiku#1460). Returns a
     * ready-to-send error {@link Response} to short-circuit, or {@code null} when the request may
     * proceed.
     *
     * @param requireCube true for the classic endpoints (cube ref mandatory); false for space
     *     endpoints, where the cube is optional and defaults to the persona's default cube.
     */
    private Response validateAskPreamble(AiAskApi.AskRequest body, boolean requireCube) {
        aiPolicyGuard.assertCanSend(org.saiku.service.olap.ai.AiDataKind.AGGREGATED_RESULT_VALUES);
        if (body == null) {
            return badRequest("body", "request body required", null);
        }
        if (body.getQuestion() == null || body.getQuestion().isBlank()) {
            return badRequest("question", "question must be non-blank", null);
        }
        if (requireCube && (body.getCube() == null || body.getCube().getCubeName() == null)) {
            return badRequest("cube", "cube ref required", null);
        }
        // saiku#1151: cap request size + call rate before reaching the paid LLM provider. Oversize
        // questions/histories inflate per-call token spend; unbounded call frequency is a cost-DoS.
        org.saiku.web.security.ratelimit.AiAskGuard.Violation sizeViolation =
                org.saiku.web.security.ratelimit.AiAskGuard.checkSize(body);
        if (sizeViolation != null) {
            return askLimitResponse(sizeViolation.isPayloadTooLarge() ? 413 : 400, sizeViolation.getMessage());
        }
        if (!askRateLimiter.tryAcquire(askRateKey())) {
            return askLimitResponse(
                    429,
                    "Too many AI ask requests — limit is " + askRateLimiter.getMaxCalls() + " per "
                            + (askRateLimiter.getWindowMs() / 1000) + "s. Please retry shortly.");
        }
        if (askService == null) {
            AiAskApi.AskResponse notConfigured = new AiAskApi.AskResponse();
            notConfigured.setDegraded(true);
            notConfigured.setReason(ASK_NOT_CONFIGURED_REASON);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(notConfigured)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return null;
    }

    /**
     * Build the success response envelope for a non-degraded ask outcome, shared by the sync
     * classic ({@link #ask}) and space ({@link #askInSpace}) endpoints so every ask surface returns
     * the same shape (saiku#1455). INSIGHT and VIEW_CHANGE short-circuit with the model's artefact;
     * QUERY is converted + executed the same way {@code /ai/query} does.
     */
    private Response buildAskSuccessResponse(AiAskService.AskOutcome outcome) {
        AiAskApi.AskResponse out = new AiAskApi.AskResponse();
        out.setDegraded(false);
        out.setModel(outcome.model());

        // Insight and view-change intents skip the converter / execution entirely — the model
        // already produced the final artefact (markdown analysis / view target).
        if (outcome.kind() == AiAskService.AskOutcome.Kind.INSIGHT) {
            out.setInsight(outcome.insight());
            return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
        }
        if (outcome.kind() == AiAskService.AskOutcome.Kind.VIEW_CHANGE) {
            out.setViewChange(outcome.viewChange());
            return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
        }
        if (outcome.kind() == AiAskService.AskOutcome.Kind.EMAIL_DRAFT) {
            if (!mailConfigured()) {
                // Task 3: honest refusal — mail isn't wired on this deployment, so don't hand the
                // user a draft they have no way to send. Mirrors the classic degraded AskResponse
                // shape (degraded=true + reason, model already set above) rather than inventing a
                // new envelope.
                out.setDegraded(true);
                out.setReason(MAIL_NOT_CONFIGURED_REASON);
                return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
            }
            out.setEmailDraft(outcome.emailDraft());
            return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
        }

        // QUERY intent — convert + execute.
        out.setRequest(outcome.request());
        executeQueryIntoResponse(out, outcome.request());
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Convert + execute the model's {@link AiQueryRequest} through the same path {@code /ai/query}
     * uses, folding the result (or a structured validation / execution error) into {@code out}.
     * Shared by the sync success path ({@link #buildAskSuccessResponse}) and the streaming QUERY
     * branch ({@link #streamOutcomeAsSse}) so the two can't diverge. Errors are captured into the
     * response envelope rather than thrown, so the UI can show "the model proposed this, but it
     * failed at &lt;stage&gt;" instead of a 500.
     */
    private void executeQueryIntoResponse(AiAskApi.AskResponse out, AiQueryRequest req) {
        long start = System.currentTimeMillis();
        try {
            // Phase 2 shape-validate before the converter sees the request — same as /ai/query.
            schemaValidator.assertValid(MAPPER.valueToTree(req));
            AiSchema schema = cubeMetadataService.getSchema(req.getCube());
            ThinQuery tq = converter.convert(req, schema);
            // Surface the converted ThinQueryModel so the UI's "edit in canvas" can hydrate the
            // workbench's chip builder directly instead of pasting opaque MDX.
            out.setQueryModel(tq.getQueryModel());
            CellDataSet cds = thinQueryService.execute(tq);
            AiQueryResponse aiResp = buildResponse(tq, cds, start, "records");
            out.setResponse(aiResp);
            if (aiResp != null && aiResp.getMetadata() != null) {
                out.setGeneratedMdx(aiResp.getMetadata().getGeneratedMdx());
            }
        } catch (AiValidationException e) {
            // Surface the converter's structured validation as the response envelope so the UI can
            // render candidate-list suggestions just like a direct /ai/query call would.
            AiQueryResponse aiResp = new AiQueryResponse();
            aiResp.setStatus(org.saiku.service.olap.ai.AiQueryResponse.Status.VALIDATION_ERROR);
            aiResp.setError(e.getMessage());
            aiResp.setField(e.getField());
            aiResp.setAvailable(e.getAvailable() == null ? null : new java.util.ArrayList<>(e.getAvailable()));
            aiResp.setRuntimeMs(System.currentTimeMillis() - start);
            out.setResponse(aiResp);
        } catch (RuntimeException e) {
            log.warn("AI ask execution failed after successful translation", e);
            AiQueryResponse aiResp = new AiQueryResponse();
            aiResp.setStatus(org.saiku.service.olap.ai.AiQueryResponse.Status.EXECUTION_ERROR);
            aiResp.setError("execute failed");
            aiResp.setRuntimeMs(System.currentTimeMillis() - start);
            out.setResponse(aiResp);
        }
    }

    /**
     * Translate the wire-shape {@code forceTool} string into the service enum. Unknown / null →
     * {@link org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool#AUTO} so a bad client value
     * silently degrades to auto-routing rather than 400ing the whole turn. Uses {@link Locale#ROOT}
     * so the uppercase is locale-independent — a tr-TR JVM must not turn {@code "insight"} into
     * {@code "İNSİGHT"} and drop the user's explicit pick (saiku#1458).
     */
    static org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool parseForceTool(String raw) {
        if (raw == null) {
            return org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool.AUTO;
        }
        try {
            return org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return org.saiku.service.olap.ai.ask.NlAskRequest.ForceTool.AUTO;
        }
    }

    /**
     * Map a space-access pre-flight (saiku#1454) to an HTTP error {@link Response}, or {@code null}
     * when access is granted. Lets the streaming space endpoint return a real 403/404/503
     * <em>before</em> it commits to a 200 event-stream, honouring the documented "cube refs outside
     * the allowlist return 403 FORBIDDEN" contract instead of burying the denial in an SSE event.
     */
    private Response mapSpaceAccessDenial(AiAskService.SpaceAccess access, String spaceId) {
        switch (access) {
            case OK:
                return null;
            case SPACES_NOT_CONFIGURED:
                return askLimitResponse(503, "agent spaces are not configured on this instance");
            case SPACE_NOT_FOUND:
                return askLimitResponse(404, "space not found: " + spaceId);
            case FORBIDDEN:
                return askLimitResponse(403, "FORBIDDEN: requested cube is not in space '" + spaceId + "' allowlist");
            default:
                return null;
        }
    }

    /**
     * Shared SSE runner for the streaming ask endpoints (saiku#1460). Builds the WHATWG SSE stream,
     * invokes {@code outcomeSupplier} to produce the outcome (the only thing that differs between
     * {@link #askStream} and {@link #askInSpaceStream}), and pipes it through {@link
     * #streamOutcomeAsSse}. Centralises the failure handling so both endpoints get identical,
     * correct behaviour:
     *
     * <ul>
     *   <li>{@link com.fasterxml.jackson.core.JsonProcessingException} (a serialisation failure of
     *       the outcome payload) is caught <em>before</em> the {@link java.io.IOException} branch —
     *       it is NOT a client disconnect, so it must surface as an error to the still-connected
     *       client, not vanish at DEBUG (saiku#1457).
     *   <li>Every error path emits an {@code error} event <em>followed by</em> a terminal degraded
     *       {@code final} event, matching the documented wire contract (saiku#1456).
     * </ul>
     */
    private Response streamAsk(java.util.function.Supplier<AiAskService.AskOutcome> outcomeSupplier, String logLabel) {
        jakarta.ws.rs.core.StreamingOutput stream = outputStream -> {
            java.io.Writer writer =
                    new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8);
            SseWriter sse = new SseWriter(writer);
            try {
                streamOutcomeAsSse(outcomeSupplier.get(), sse);
            } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                // Serialising the outcome failed — the client is still connected. Surface an error
                // (NOT the disconnect branch below, which JsonProcessingException would fall into
                // as an IOException subclass) so the turn terminates visibly. (saiku#1457)
                log.warn("{}: failed to serialise SSE payload", logLabel, jpe);
                emitStreamError(sse);
            } catch (java.io.IOException ioe) {
                // Genuine client disconnect — the connection is already dead, nothing to emit.
                log.debug("{}: client disconnected: {}", logLabel, ioe.getMessage());
            } catch (RuntimeException e) {
                log.warn("{}: unexpected failure", logLabel, e);
                emitStreamError(sse);
            }
        };
        return Response.ok(stream)
                .type("text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no") // Nginx: disable buffering so events flush.
                .build();
    }

    /**
     * Shared SSE runner for the chained streaming endpoint ({@link #askChainStream}) — twin of
     * {@link #streamAsk} but wraps {@link AiAskService.AskChain} through {@link
     * #streamChainAsSse(AiAskService.AskChain, SseWriter)} instead of a single {@code AskOutcome}.
     * Same failure handling: a serialisation failure surfaces as an in-band error (not silently
     * swallowed as a disconnect), a genuine client disconnect logs at DEBUG and stops, and any other
     * runtime failure emits the terminal error/final pair so a client keying completion on {@code
     * final} never hangs.
     */
    private Response streamChain(java.util.function.Supplier<AiAskService.AskChain> chainSupplier, String logLabel) {
        jakarta.ws.rs.core.StreamingOutput stream = outputStream -> {
            java.io.Writer writer =
                    new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8);
            SseWriter sse = new SseWriter(writer);
            try {
                streamChainAsSse(chainSupplier.get(), sse);
            } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                log.warn("{}: failed to serialise SSE payload", logLabel, jpe);
                emitStreamError(sse);
            } catch (java.io.IOException ioe) {
                log.debug("{}: client disconnected: {}", logLabel, ioe.getMessage());
            } catch (RuntimeException e) {
                log.warn("{}: unexpected failure", logLabel, e);
                emitStreamError(sse);
            }
        };
        return Response.ok(stream)
                .type("text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .build();
    }

    /**
     * Emit the terminal error pair — an {@code error} event followed by a degraded {@code final} —
     * so a client keying completion on {@code final} (per the documented contract) never hangs
     * (saiku#1456). Best-effort: if even this write fails the client is already gone.
     */
    private static void emitStreamError(SseWriter sse) {
        try {
            sse.event("error", MAPPER.writeValueAsString(java.util.Map.of("reason", "internal error")));
            AiAskApi.AskResponse out = new AiAskApi.AskResponse();
            out.setDegraded(true);
            out.setReason("internal error");
            sse.event("final", MAPPER.writeValueAsString(out));
        } catch (java.io.IOException swallow) {
            // best-effort — the client is already gone.
        }
    }

    /**
     * saiku#1151: rate-limit identity = caller principal + client IP. Falls back
     * to a constant when no request is bound to the thread (unit tests), so
     * those callers share a single bucket.
     */
    private String askRateKey() {
        jakarta.servlet.http.HttpServletRequest req = currentRequest();
        String user = null;
        String ip = null;
        if (req != null) {
            user = req.getRemoteUser();
            if (user == null && req.getUserPrincipal() != null) {
                user = req.getUserPrincipal().getName();
            }
            ip = req.getRemoteAddr();
        }
        return (user == null ? "anon" : user) + ":" + (ip == null ? "unknown" : ip);
    }

    /**
     * The {@link jakarta.servlet.http.HttpServletRequest} bound to the current
     * thread via Spring, or {@code null} when none is bound. Mirrors how this
     * resource already reaches request state ({@code RequestContextHolder}),
     * which works for the Spring-singleton wiring (a {@code @Context} field
     * would not).
     */
    private static jakarta.servlet.http.HttpServletRequest currentRequest() {
        org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes) {
            return ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
        }
        return null;
    }
}
