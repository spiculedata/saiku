/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuOssieConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.SaikuConnection;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ai.KAnonymityFilter;
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;
import org.saiku.service.ossie.ai.OssieAiQueryResponse;
import org.saiku.service.ossie.ai.OssieAiSchema;
import org.saiku.service.ossie.ai.OssieAiSchemaProjector;
import org.saiku.service.ossie.ai.OssieAiValidationException;
import org.saiku.service.ossie.ai.OssieAiValidator;
import org.saiku.service.ossie.ai.OssieAsyncQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ossie-side AI Query API — parallel to {@link AiQueryResource} but for the semantic-YAML
 * models rather than MDX cubes. Three-endpoint R1 core (see #1394):
 *
 * <pre>
 *   GET  /saiku/api/ai/ossie/models                          - list Ossie connections
 *   GET  /saiku/api/ai/ossie/schema/{connection}/{model}     - self-describing schema
 *   POST /saiku/api/ai/ossie/query                           - execute a typed shelf state
 * </pre>
 *
 * <p>Every endpoint validates names against the live semantic model and returns a typed
 * {@code VALIDATION_ERROR} on any mismatch — same self-correcting shape MDX's AI API uses.
 * Records-format response body by default; matrix format ({@code ?format=matrix}) comes in
 * R2.
 *
 * <p>Wiring lives in {@code saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml} next to
 * the existing AI resource. All three collaborators ({@link OssieDiscoverService},
 * {@link OssieQueryService}, and the connection manager) are the same beans the workbench
 * already uses; nothing new to provision.
 */
@Path("/saiku/api/ai/ossie")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiOssieResource {

    private static final Logger log = LoggerFactory.getLogger(AiOssieResource.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private OssieDiscoverService ossieDiscoverService;
    private OssieQueryService ossieQueryService;
    private OlapDiscoverService olapDiscoverService;
    private IDatasourceManager datasourceManager;
    private OssieAsyncQueryService asyncQueryService;
    /**
     * Optional k-anonymity filter. When set + enabled, records-format responses suppress metric
     * values on rows whose count-shaped metric falls below the configured k. Kept null in unit
     * tests so behaviour is opt-in.
     */
    private KAnonymityFilter kAnonymityFilter;
    /** R5: natural-language ask layer. Null when the deployment hasn't wired one — /ask returns 503. */
    private org.saiku.service.ossie.ai.OssieAiAskService askService;

    private final OssieAiSchemaProjector projector = new OssieAiSchemaProjector();
    private final OssieAiValidator validator = new OssieAiValidator();

    public void setOssieDiscoverService(OssieDiscoverService s) {
        this.ossieDiscoverService = s;
    }

    public void setOssieQueryService(OssieQueryService s) {
        this.ossieQueryService = s;
    }

    public void setOlapDiscoverService(OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    public void setDatasourceManager(IDatasourceManager m) {
        this.datasourceManager = m;
    }

    public void setAsyncQueryService(OssieAsyncQueryService s) {
        this.asyncQueryService = s;
    }

    public void setKAnonymityFilter(KAnonymityFilter f) {
        this.kAnonymityFilter = f;
    }

    public void setAskService(org.saiku.service.ossie.ai.OssieAiAskService s) {
        this.askService = s;
    }

    // -------------------------------------------------------------------
    // GET /models
    // -------------------------------------------------------------------

    /**
     * List Ossie models the caller can access. Iterates the connection registry, keeps the
     * OSSIE-typed connections, resolves each to its {@link OssieModelDto} so callers see the
     * inner {@code semantic_model} name (which is what {@code POST /query} needs) not just the
     * connection name.
     */
    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listModels() {
        if (olapDiscoverService == null) {
            return error("olap discover not wired");
        }
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (SaikuConnection c : olapDiscoverService.getAllConnections()) {
                if (!SaikuConnection.TYPE_OSSIE.equals(c.getType())) continue;
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("connectionName", c.getName());
                try {
                    OssieModelDto model = ossieDiscoverService.getModel(c.getName());
                    summary.put("modelName", model.getName());
                    summary.put("description", model.getDescription());
                    // Fact dataset — first dataset whose name starts with fact_, else first
                    // dataset. Kept in sync with the projector's heuristic.
                    String fact = null;
                    for (OssieModelDto.Dataset ds : model.getDatasets()) {
                        if (ds.getName().toLowerCase().startsWith("fact")) {
                            fact = ds.getName();
                            break;
                        }
                    }
                    if (fact == null && !model.getDatasets().isEmpty()) {
                        fact = model.getDatasets().get(0).getName();
                    }
                    summary.put("factDataset", fact);
                    summary.put("datasetCount", model.getDatasets().size());
                    summary.put("metricCount", model.getMetrics().size());
                } catch (Exception e) {
                    log.warn("listing Ossie model failed for connection '{}': {}", c.getName(), e.getMessage());
                    summary.put("error", e.getMessage());
                }
                out.add(summary);
            }
            return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
        } catch (RuntimeException e) {
            log.error("Ossie models listing failed", e);
            return error("model listing failed");
        }
    }

    // -------------------------------------------------------------------
    // GET /schema/{connection}/{model}
    // -------------------------------------------------------------------

    /**
     * Return the agent-facing schema for one Ossie model. Uses a two-segment path (rather than a
     * slash-joined single param) because Ossie identifiers are much simpler than MDX cube IDs —
     * connection + model is enough.
     */
    @GET
    @Path("/schema/{connection}/{model}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSchema(
            @PathParam("connection") String connectionName,
            @PathParam("model") String modelName,
            @jakarta.ws.rs.QueryParam("refresh") Boolean refresh) {
        if (ossieDiscoverService == null) {
            return error("Ossie discover not wired");
        }
        try {
            OssieModelDto semantic = ossieDiscoverService.getModel(connectionName);
            if (semantic.getName() == null || !semantic.getName().equalsIgnoreCase(modelName)) {
                return badRequest(
                        "model",
                        "model '" + modelName + "' not found on connection '" + connectionName + "'",
                        List.of(semantic.getName()));
            }
            // #1404 — ?refresh=true drops the projector's cached samples for this model so the
            // next fetch re-runs the SELECT DISTINCTs against the warehouse.
            if (Boolean.TRUE.equals(refresh)) {
                projector.invalidateSampleCache(connectionName, semantic.getName());
            }
            Connection jdbc = openWarehouseConnection(connectionName);
            OssieAiSchema schema = projector.project(connectionName, semantic, jdbc);
            return Response.ok(schema).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalArgumentException e) {
            return badRequest("connection", e.getMessage(), List.of());
        } catch (Exception e) {
            log.error("Ossie schema fetch failed for {}/{}", connectionName, modelName, e);
            return error("schema fetch failed");
        }
    }

    // -------------------------------------------------------------------
    // POST /query
    // -------------------------------------------------------------------

    /**
     * Execute a typed Ossie query. Records-format response by default; matrix format is R2.
     * On validation failure returns 400 with the {@code VALIDATION_ERROR} envelope carrying
     * {@code field} + {@code available} so the agent self-corrects.
     */
    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeAi(OssieAiQueryRequest req, @jakarta.ws.rs.QueryParam("format") String format) {
        try {
            if (req == null) {
                return badRequest("body", "request body required", List.of());
            }
            if (req.getConnection() == null || req.getConnection().isBlank()) {
                return badRequest("connection", "connection is required", List.of());
            }
            OssieModelDto semantic = ossieDiscoverService.getModel(req.getConnection());
            if (req.getModel() == null || req.getModel().isBlank()) {
                req.setModel(semantic.getName());
            } else if (!req.getModel().equalsIgnoreCase(semantic.getName())) {
                return badRequest(
                        "model",
                        "model '" + req.getModel() + "' does not match connection '" + req.getConnection()
                                + "' (its model is '" + semantic.getName() + "')",
                        List.of(semantic.getName()));
            }

            // 1. Validate against the schema. Schema built without warehouse-side sample values —
            //    the validator doesn't need them and skipping the connect-and-query saves a hop.
            OssieAiSchema schema = projector.project(req.getConnection(), semantic, null);
            validator.validate(req, schema);

            // 2. Translate the AI request into the internal OssieQueryModel the query service expects.
            OssieQueryModel internal = toInternal(req, semantic);

            // 3. Execute — reuse ThinQueryService's Ossie branch via a synthetic ThinQuery envelope.
            ThinQuery tq = new ThinQuery();
            tq.setName("ossie-ai-" + UUID.randomUUID().toString().substring(0, 8));
            tq.setQueryType("OSSIE");
            internal.setConnection(req.getConnection());
            internal.setModel(req.getModel());
            tq.setOssieQueryModel(internal);

            long t0 = System.currentTimeMillis();
            CellDataSet cds = ossieQueryService.execute(tq);
            long runtime = System.currentTimeMillis() - t0;

            if ("matrix".equalsIgnoreCase(format)) {
                Map<String, Object> body = toMatrixResponse(tq.getName(), runtime, cds, schema, req);
                return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
            }
            OssieAiQueryResponse resp = toRecordsResponse(tq.getName(), runtime, cds, schema, req);
            return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
        } catch (OssieAiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (IllegalArgumentException e) {
            return badRequest("body", e.getMessage(), List.of());
        } catch (Exception e) {
            log.error("Ossie AI query failed", e);
            return error("query failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // R5: /ask + /ask/health — natural-language ask layer
    // -------------------------------------------------------------------

    /**
     * Signals whether the ask layer is configured. Enabled when {@code askService.isConfigured()}
     * is true — the ClaudeDesktop-style UIs use this to hide the ask button on servers that
     * haven't wired an LLM key.
     */
    @GET
    @Path("/ask/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response askHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean ok = askService != null && askService.isConfigured();
        body.put("configured", ok);
        body.put("provider", askService != null ? askService.describe() : "not wired");
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Natural-language endpoint. Body: {@code {connection, model, question}}. The LLM produces a
     * JSON {@link OssieAiQueryRequest}; the resource executes it and returns the records with a
     * copy of the LLM's structured output so callers can trace what happened.
     */
    @POST
    @Path("/ask")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ask(Map<String, Object> body) {
        if (askService == null || !askService.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(
                            Map.of(
                                    "error",
                                    "NOT_CONFIGURED",
                                    "message",
                                    "The Ossie ask layer is not configured. Set saiku.ai.ask.provider (anthropic|openai) and the matching API key."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        if (body == null) return badRequest("body", "request body required", List.of());
        String connection = strOr(body.get("connection"));
        String modelName = strOr(body.get("model"));
        String question = strOr(body.get("question"));
        if (connection == null) return badRequest("connection", "connection is required", List.of());
        if (question == null) return badRequest("question", "question is required", List.of());
        try {
            OssieModelDto semantic = ossieDiscoverService.getModel(connection);
            if (modelName == null || modelName.isBlank()) modelName = semantic.getName();
            OssieAiSchema schema = projector.project(connection, semantic, openWarehouseConnection(connection));

            // #1398 — accept optional history: [{role, content}] for multi-turn conversations.
            List<org.saiku.service.ossie.ai.OssieAiAskService.ChatTurn> history = new ArrayList<>();
            Object rawHistory = body.get("history");
            if (rawHistory instanceof List<?> hlist) {
                for (Object h : hlist) {
                    if (h instanceof Map<?, ?> hmap) {
                        String role = strOr(hmap.get("role"));
                        String content = strOr(hmap.get("content"));
                        if (role != null && content != null) {
                            history.add(new org.saiku.service.ossie.ai.OssieAiAskService.ChatTurn(role, content));
                        }
                    }
                }
            }
            org.saiku.service.ossie.ai.OssieAiAskService.AskResult ar =
                    askService.ask(question, schema, connection, modelName, history);
            if (ar.error() != null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "ASK_FAILED");
                err.put("message", ar.error());
                if (ar.rawResponse() != null) err.put("rawResponse", ar.rawResponse());
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(err)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            OssieAiQueryRequest req = ar.request();
            long t0 = System.currentTimeMillis();
            OssieAiQueryResponse resp = runInternalQuery(req, t0);
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("question", question);
            wrapped.put("connection", connection);
            wrapped.put("model", modelName);
            wrapped.put("queryUsed", req);
            wrapped.put("rawLlmResponse", ar.rawResponse());
            wrapped.put("response", resp);
            return Response.ok(wrapped).type(MediaType.APPLICATION_JSON).build();
        } catch (OssieAiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (Exception e) {
            log.error("Ossie AI ask failed", e);
            return error("ask failed: " + e.getMessage());
        }
    }

    private static String strOr(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    // -------------------------------------------------------------------
    // R4: /anomaly, /forecast, /row-detail
    // -------------------------------------------------------------------

    /**
     * Anomaly detection over an Ossie query. Runs the query, extracts each metric series along
     * the requested time axis, applies the selected detector, and attaches
     * {@code anomaly:{score, expected, direction}} to each flagged cell.
     */
    @POST
    @Path("/anomaly")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response detectAnomalies(org.saiku.service.ossie.ai.OssieAiAnomalyRequest body) {
        long start = System.currentTimeMillis();
        try {
            if (body == null) return badRequest("body", "request body required", List.of());
            if (body.getQuery() == null) {
                return badRequest("query", "query (OssieAiQueryRequest) is required", List.of());
            }
            if (body.getTimeAxis() == null || body.getTimeAxis().isBlank()) {
                return badRequest("timeAxis", "timeAxis (dataset.field key) is required", List.of());
            }
            org.saiku.service.olap.ai.anomaly.AnomalyDetector detector;
            try {
                detector = org.saiku.service.olap.ai.anomaly.AnomalyDetectors.forMethod(body.getMethod());
            } catch (org.saiku.service.olap.ai.AiValidationException e) {
                return badRequest(e.getField(), e.getMessage(), e.getAvailable());
            }
            double threshold = body.getThreshold() != null ? body.getThreshold() : detector.defaultThreshold();
            if (threshold <= 0 || Double.isNaN(threshold) || Double.isInfinite(threshold)) {
                return badRequest("threshold", "threshold must be a positive number", List.of());
            }
            OssieAiQueryRequest qreq = body.getQuery();
            // Validate timeAxis against schema + query shape (#1399) before we execute — the
            // augmenter would silently sort by a phantom key otherwise.
            OssieModelDto anomSemantic = ossieDiscoverService.getModel(qreq.getConnection());
            if (qreq.getModel() == null || qreq.getModel().isBlank()) qreq.setModel(anomSemantic.getName());
            OssieAiSchema anomSchema = projector.project(qreq.getConnection(), anomSemantic, null);
            validator.validateTimeAxis(body.getTimeAxis(), qreq, anomSchema);
            OssieAiQueryResponse resp = runInternalQuery(qreq, start);
            org.saiku.service.ossie.ai.OssieAiAnomalyAugmenter.augment(resp, detector, threshold, body.getTimeAxis());
            return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
        } catch (OssieAiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (Exception e) {
            log.error("Ossie AI anomaly failed", e);
            return error("anomaly detection failed: " + e.getMessage());
        }
    }

    /**
     * Forecast projections over an Ossie query. Runs the query, extracts each metric series
     * along the requested time axis, and returns projected points + confidence intervals in the
     * top-level {@code forecast} block.
     */
    @POST
    @Path("/forecast")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response forecast(org.saiku.service.ossie.ai.OssieAiForecastRequest body) {
        long start = System.currentTimeMillis();
        try {
            if (body == null) return badRequest("body", "request body required", List.of());
            if (body.getQuery() == null) {
                return badRequest("query", "query (OssieAiQueryRequest) is required", List.of());
            }
            if (body.getTimeAxis() == null || body.getTimeAxis().isBlank()) {
                return badRequest("timeAxis", "timeAxis (dataset.field key) is required", List.of());
            }
            org.saiku.service.olap.ai.forecast.Forecaster forecaster;
            try {
                forecaster = org.saiku.service.olap.ai.forecast.Forecasters.forMethod(body.getMethod());
            } catch (org.saiku.service.olap.ai.AiValidationException e) {
                return badRequest(e.getField(), e.getMessage(), e.getAvailable());
            }
            int horizon = body.getHorizon() != null ? body.getHorizon() : 4;
            if (horizon <= 0 || horizon > 200) {
                return badRequest("horizon", "horizon must be 1..200", List.of());
            }
            double confidence = body.getInterval() != null ? body.getInterval() : 0.95;
            if (confidence <= 0 || confidence >= 1) {
                return badRequest("interval", "interval must be in (0,1)", List.of());
            }
            OssieAiQueryRequest fqreq = body.getQuery();
            // Validate timeAxis (#1399) before we execute.
            OssieModelDto fSemantic = ossieDiscoverService.getModel(fqreq.getConnection());
            if (fqreq.getModel() == null || fqreq.getModel().isBlank()) fqreq.setModel(fSemantic.getName());
            OssieAiSchema fSchema = projector.project(fqreq.getConnection(), fSemantic, null);
            validator.validateTimeAxis(body.getTimeAxis(), fqreq, fSchema);
            OssieAiQueryResponse resp = runInternalQuery(fqreq, start);
            org.saiku.service.ossie.ai.OssieAiForecastAugmenter.augment(
                    resp, forecaster, horizon, confidence, body.getTimeAxis());
            return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
        } catch (OssieAiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (Exception e) {
            log.error("Ossie AI forecast failed", e);
            return error("forecast failed: " + e.getMessage());
        }
    }

    /**
     * Row-detail (Ossie's answer to MDX drillthrough). Re-runs the shelf state with the values
     * shelf emptied so the executor emits a raw rowset instead of an aggregate. LIMIT is
     * capped at {@code maxrows} (default 100, max 10_000) to keep this endpoint bounded.
     */
    @POST
    @Path("/row-detail")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rowDetail(OssieAiQueryRequest req, @jakarta.ws.rs.QueryParam("maxrows") Integer maxrows) {
        try {
            if (req == null) return badRequest("body", "request body required", List.of());
            if (req.getConnection() == null) {
                return badRequest("connection", "connection is required", List.of());
            }
            int cap = maxrows == null ? 100 : Math.max(1, Math.min(maxrows, 10_000));
            // Force the raw-rowset shape: keep the dim fields on rows/columns, strip metrics,
            // apply the row-detail cap.
            req.setValues(new ArrayList<>());
            req.setLimit(cap);
            OssieModelDto semantic = ossieDiscoverService.getModel(req.getConnection());
            if (req.getModel() == null || req.getModel().isBlank()) req.setModel(semantic.getName());
            OssieAiSchema schema = projector.project(req.getConnection(), semantic, null);
            validator.validate(req, schema);

            OssieQueryModel internal = toInternal(req, semantic);
            ThinQuery tq = new ThinQuery();
            tq.setName("ossie-ai-rowdetail-" + UUID.randomUUID().toString().substring(0, 8));
            tq.setQueryType("OSSIE");
            internal.setConnection(req.getConnection());
            internal.setModel(req.getModel());
            tq.setOssieQueryModel(internal);
            long t0 = System.currentTimeMillis();
            CellDataSet cds = ossieQueryService.execute(tq);
            long runtime = System.currentTimeMillis() - t0;
            OssieAiQueryResponse resp = toRecordsResponse(tq.getName(), runtime, cds, schema, req);
            resp.getMeta().setTruncated(resp.getRecords().size() >= cap);
            return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
        } catch (OssieAiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (Exception e) {
            log.error("Ossie AI row-detail failed", e);
            return error("row-detail failed: " + e.getMessage());
        }
    }

    /**
     * Shared internal-query runner used by /anomaly and /forecast — validates, executes, and
     * produces a records-format response ready for augmentation.
     */
    private OssieAiQueryResponse runInternalQuery(OssieAiQueryRequest req, long start) throws Exception {
        if (req.getConnection() == null) {
            throw new OssieAiValidationException("query.connection", "connection is required", List.of());
        }
        OssieModelDto semantic = ossieDiscoverService.getModel(req.getConnection());
        if (req.getModel() == null || req.getModel().isBlank()) req.setModel(semantic.getName());
        OssieAiSchema schema = projector.project(req.getConnection(), semantic, null);
        validator.validate(req, schema);
        OssieQueryModel internal = toInternal(req, semantic);
        ThinQuery tq = new ThinQuery();
        tq.setName("ossie-ai-" + UUID.randomUUID().toString().substring(0, 8));
        tq.setQueryType("OSSIE");
        internal.setConnection(req.getConnection());
        internal.setModel(req.getModel());
        tq.setOssieQueryModel(internal);
        CellDataSet cds = ossieQueryService.execute(tq);
        long runtime = System.currentTimeMillis() - start;
        return toRecordsResponse(tq.getName(), runtime, cds, schema, req);
    }

    // -------------------------------------------------------------------
    // Async — /query/execute-async, /query/status/{id}, /query/result/{id}, DELETE /query/{id}
    // -------------------------------------------------------------------

    /**
     * Submit a shelf-state query for background execution. Validation runs synchronously so 400s
     * come back immediately; execution runs on the {@link OssieAsyncQueryService}'s pool.
     */
    @POST
    @Path("/query/execute-async")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeAsync(OssieAiQueryRequest req) {
        if (asyncQueryService == null) {
            return error("async service not configured");
        }
        try {
            if (req == null || req.getConnection() == null) {
                return badRequest("connection", "connection is required", List.of());
            }
            OssieModelDto semantic = ossieDiscoverService.getModel(req.getConnection());
            if (req.getModel() == null || req.getModel().isBlank()) req.setModel(semantic.getName());
            OssieAiSchema schema = projector.project(req.getConnection(), semantic, null);
            validator.validate(req, schema);
            OssieQueryModel internal = toInternal(req, semantic);
            ThinQuery tq = new ThinQuery();
            tq.setName("ossie-ai-" + UUID.randomUUID().toString().substring(0, 8));
            tq.setQueryType("OSSIE");
            internal.setConnection(req.getConnection());
            internal.setModel(req.getModel());
            tq.setOssieQueryModel(internal);
            OssieAsyncQueryService.Handle h = asyncQueryService.submit(tq, currentPrincipal());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("queryId", h.getId());
            body.put("status", h.getStatus().name());
            return Response.status(Response.Status.ACCEPTED)
                    .entity(body)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (OssieAiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (Exception e) {
            log.error("Ossie AI async submit failed", e);
            return error("submit failed: " + e.getMessage());
        }
    }

    @GET
    @Path("/query/status/{queryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response asyncStatus(@PathParam("queryId") String queryId) {
        if (asyncQueryService == null) return error("async service not configured");
        OssieAsyncQueryService.Handle h = asyncQueryService.getOwned(queryId, currentPrincipal(), currentUserIsAdmin());
        if (h == null) return Response.status(Response.Status.NOT_FOUND).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        body.put("status", h.getStatus().name());
        if (h.getErrorMessage() != null) body.put("error", h.getErrorMessage());
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/query/result/{queryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response asyncResult(
            @PathParam("queryId") String queryId, @jakarta.ws.rs.QueryParam("format") String format) {
        if (asyncQueryService == null) return error("async service not configured");
        OssieAsyncQueryService.Handle h = asyncQueryService.getOwned(queryId, currentPrincipal(), currentUserIsAdmin());
        if (h == null) return Response.status(Response.Status.NOT_FOUND).build();
        switch (h.getStatus()) {
            case PENDING:
            case RUNNING:
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("queryId", queryId);
                pending.put("status", h.getStatus().name());
                return Response.status(Response.Status.ACCEPTED)
                        .entity(pending)
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            case FAILED:
            case CANCELLED:
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("queryId", queryId);
                fail.put("status", h.getStatus().name());
                fail.put(
                        "error",
                        h.getErrorMessage() != null
                                ? h.getErrorMessage()
                                : h.getStatus().name());
                return Response.ok(fail).type(MediaType.APPLICATION_JSON).build();
            case DONE:
                try {
                    CellDataSet cds = h.getResult();
                    long runtime = h.getCompletedAt() != null && h.getSubmittedAt() != null
                            ? java.time.Duration.between(h.getSubmittedAt(), h.getCompletedAt())
                                    .toMillis()
                            : 0L;
                    OssieAiQueryRequest submitted =
                            translateBackToAiRequest(h.getQuery().getOssieQueryModel());
                    OssieModelDto semantic = ossieDiscoverService.getModel(submitted.getConnection());
                    OssieAiSchema schema = projector.project(submitted.getConnection(), semantic, null);
                    if ("matrix".equalsIgnoreCase(format)) {
                        Map<String, Object> matrix = toMatrixResponse(queryId, runtime, cds, schema, submitted);
                        return Response.ok(matrix)
                                .type(MediaType.APPLICATION_JSON)
                                .build();
                    }
                    OssieAiQueryResponse resp = toRecordsResponse(queryId, runtime, cds, schema, submitted);
                    return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
                } catch (Exception e) {
                    log.error("async result serialisation failed for {}", queryId, e);
                    return error("result serialisation failed");
                }
            default:
                return error("unexpected status: " + h.getStatus());
        }
    }

    @jakarta.ws.rs.DELETE
    @Path("/query/{queryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response asyncCancel(@PathParam("queryId") String queryId) {
        if (asyncQueryService == null) return error("async service not configured");
        boolean ok = asyncQueryService.cancel(queryId, currentPrincipal(), currentUserIsAdmin());
        if (!ok) return Response.status(Response.Status.NOT_FOUND).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        body.put("status", "CANCELLED");
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Rehydrate the AI-facing request from the internal ThinQuery so async result serialisation
     * can label columns the same way sync serialisation does. Same-order fields; we don't lose
     * anything here because the AI → internal translation is 1:1.
     */
    private OssieAiQueryRequest translateBackToAiRequest(OssieQueryModel internal) {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setConnection(internal.getConnection());
        req.setModel(internal.getModel());
        for (OssieQueryModel.FieldRef r : internal.getRows()) {
            OssieAiQueryRequest.FieldRef out = new OssieAiQueryRequest.FieldRef();
            out.setDataset(r.getDataset());
            out.setField(r.getField());
            req.getRows().add(out);
        }
        for (OssieQueryModel.FieldRef c : internal.getColumns()) {
            OssieAiQueryRequest.FieldRef out = new OssieAiQueryRequest.FieldRef();
            out.setDataset(c.getDataset());
            out.setField(c.getField());
            req.getColumns().add(out);
        }
        for (OssieQueryModel.MetricRef v : internal.getValues()) {
            OssieAiQueryRequest.MetricRef out = new OssieAiQueryRequest.MetricRef();
            out.setMetric(v.getMetric());
            out.setAggregation(v.getAggregation());
            req.getValues().add(out);
        }
        req.setLimit(internal.getLimit());
        return req;
    }

    /** Current Spring Security principal name, or null if unauthenticated. */
    private String currentPrincipal() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth == null) return null;
        return auth.getName();
    }

    /** Whether the current caller has ROLE_ADMIN. Used by async ownership checks. */
    private boolean currentUserIsAdmin() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth == null) return false;
        for (org.springframework.security.core.GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority())) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------
    // POST /query/preview  — compile to SQL without executing
    // -------------------------------------------------------------------

    /**
     * Validate + translate the request into SQL without executing it. Same self-correcting
     * VALIDATION_ERROR envelope as {@code POST /query} when a name is wrong. Response:
     *
     * <pre>{@code
     * { queryId: "ossie-ai-...", status: "PREVIEW", generatedSql: "SELECT ..." }
     * }</pre>
     *
     * <p>Uses {@link OssieQueryService#previewSql} which runs the same translator the executor
     * runs — so what you see here is 1:1 what {@code POST /query} would dispatch.
     */
    @POST
    @Path("/query/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response previewAi(OssieAiQueryRequest req) {
        try {
            if (req == null) {
                return badRequest("body", "request body required", List.of());
            }
            if (req.getConnection() == null || req.getConnection().isBlank()) {
                return badRequest("connection", "connection is required", List.of());
            }
            OssieModelDto semantic = ossieDiscoverService.getModel(req.getConnection());
            if (req.getModel() == null || req.getModel().isBlank()) req.setModel(semantic.getName());
            OssieAiSchema schema = projector.project(req.getConnection(), semantic, null);
            validator.validate(req, schema);

            OssieQueryModel internal = toInternal(req, semantic);
            String sql = ossieQueryService.previewSql(internal);

            String previewId =
                    "ossie-ai-preview-" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("queryId", previewId);
            body.put("status", "PREVIEW");
            body.put("generatedSql", sql);
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (OssieAiValidationException e) {
            return badRequest(e.getField(), e.getMessage(), e.getAvailable());
        } catch (IllegalArgumentException e) {
            return badRequest("body", e.getMessage(), List.of());
        } catch (Exception e) {
            log.error("Ossie AI query preview failed", e);
            return error("preview failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // GET /values/search — substring match over distinct field values
    // -------------------------------------------------------------------

    /**
     * Substring lookup of distinct values on a field. Ossie counterpart of MDX's
     * {@code /ai/members/search}. Runs a {@code SELECT DISTINCT <field> FROM <src>
     * WHERE <field> LIKE '%q%' LIMIT n} on the warehouse and returns whatever matches.
     *
     * <p>Query params:
     *
     * <ul>
     *   <li>{@code connection} (required) — Ossie connection name.
     *   <li>{@code model} (optional) — model name; defaults to the connection's model.
     *   <li>{@code dataset} (required) — dataset containing the field.
     *   <li>{@code field} (required) — field to search.
     *   <li>{@code q} (optional) — substring to filter by; omit for the first N values.
     *   <li>{@code limit} (optional, default 20, max 200) — cap on returned matches.
     * </ul>
     */
    @GET
    @Path("/values/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchValues(
            @jakarta.ws.rs.QueryParam("connection") String connectionName,
            @jakarta.ws.rs.QueryParam("model") String modelName,
            @jakarta.ws.rs.QueryParam("dataset") String datasetName,
            @jakarta.ws.rs.QueryParam("field") String fieldName,
            @jakarta.ws.rs.QueryParam("q") String q,
            @jakarta.ws.rs.QueryParam("limit") Integer limit) {
        try {
            if (connectionName == null || connectionName.isBlank()) {
                return badRequest("connection", "connection is required", List.of());
            }
            if (datasetName == null || datasetName.isBlank()) {
                return badRequest("dataset", "dataset is required", List.of());
            }
            if (fieldName == null || fieldName.isBlank()) {
                return badRequest("field", "field is required", List.of());
            }
            int cap = limit == null ? 20 : Math.max(1, Math.min(limit, 200));

            OssieModelDto semantic = ossieDiscoverService.getModel(connectionName);
            if (modelName != null && !modelName.isBlank() && !modelName.equalsIgnoreCase(semantic.getName())) {
                return badRequest("model", "unknown model '" + modelName + "'", List.of(semantic.getName()));
            }
            OssieModelDto.Dataset ds = null;
            for (OssieModelDto.Dataset d : semantic.getDatasets()) {
                if (d.getName().equalsIgnoreCase(datasetName)) {
                    ds = d;
                    break;
                }
            }
            if (ds == null) {
                List<String> avail = new ArrayList<>();
                for (OssieModelDto.Dataset d : semantic.getDatasets()) avail.add(d.getName());
                return badRequest("dataset", "unknown dataset '" + datasetName + "'", avail);
            }
            OssieModelDto.Field field = null;
            for (OssieModelDto.Field f : ds.getFields()) {
                if (f.isPii()) continue; // PII: same policy as schema view — cannot search across masked fields.
                if (f.getName().equalsIgnoreCase(fieldName)) {
                    field = f;
                    break;
                }
            }
            if (field == null) {
                List<String> avail = new ArrayList<>();
                for (OssieModelDto.Field f : ds.getFields()) if (!f.isPii()) avail.add(f.getName());
                return badRequest(
                        "field", "unknown field '" + fieldName + "' on dataset '" + ds.getName() + "'", avail);
            }

            Connection jdbc = openWarehouseConnection(connectionName);
            if (jdbc == null) {
                return error("could not open warehouse connection for '" + connectionName + "'");
            }
            try {
                List<String> matches = runValuesSearch(jdbc, ds, field, q, cap);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("connection", connectionName);
                body.put("model", semantic.getName());
                body.put("dataset", ds.getName());
                body.put("field", field.getName());
                body.put("q", q);
                body.put("limit", cap);
                body.put("matches", matches);
                return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
            } finally {
                try {
                    jdbc.close();
                } catch (Exception ignored) {
                }
            }
        } catch (IllegalArgumentException e) {
            return badRequest("body", e.getMessage(), List.of());
        } catch (Exception e) {
            log.error("Ossie AI values-search failed", e);
            return error("values-search failed: " + e.getMessage());
        }
    }

    private List<String> runValuesSearch(
            Connection jdbc, OssieModelDto.Dataset ds, OssieModelDto.Field field, String q, int cap) throws Exception {
        // Ossie's Calcite schema exposes tables under their DATASET name (e.g. "payer"), not
        // their raw source name (e.g. "DIM_PAYER"). Quote it so caseSensitive=false doesn't
        // matter; column stays unquoted so we get the same case-insensitive resolution the
        // projector's sample-value query relies on.
        String src = "\"" + ds.getName() + "\"";
        String col = field.getName();
        // Inlined literal — Calcite's parameter binding on the JDBC adapter drops the
        // binding at plan time for LIKE with UPPER/CAST wrapping, so we hand-escape the
        // filter value here. Safe: q is user-supplied but we single-quote-escape per SQL
        // standard and the col + src are validated against the model before we get here.
        String sql;
        if (q == null || q.isBlank()) {
            sql = "SELECT DISTINCT " + col + " FROM " + src + " LIMIT " + cap;
        } else {
            String escaped = q.toUpperCase(java.util.Locale.ROOT).replace("'", "''");
            sql = "SELECT DISTINCT " + col + " FROM " + src + " WHERE UPPER(CAST(" + col + " AS VARCHAR)) LIKE '%"
                    + escaped + "%' LIMIT " + cap;
        }
        List<String> out = new ArrayList<>();
        try (java.sql.Statement st = jdbc.createStatement();
                java.sql.ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Object v = rs.getObject(1);
                out.add(v == null ? null : String.valueOf(v));
            }
        }
        return out;
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Translate the AI-shaped request into the internal {@link OssieQueryModel} the query
     * service and translator work with. One-to-one field mapping — no rewrites; the AI request
     * shape is a strict subset of the internal shape.
     */
    private OssieQueryModel toInternal(OssieAiQueryRequest req, OssieModelDto semantic) {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection(req.getConnection());
        m.setModel(req.getModel());
        // Fact dataset lookup — first fact_ dataset else first dataset.
        String fact = null;
        for (OssieModelDto.Dataset ds : semantic.getDatasets()) {
            if (ds.getName().toLowerCase().startsWith("fact")) {
                fact = ds.getName();
                break;
            }
        }
        if (fact == null && !semantic.getDatasets().isEmpty()) {
            fact = semantic.getDatasets().get(0).getName();
        }
        m.setFactDataset(fact);
        for (OssieAiQueryRequest.FieldRef r : req.getRows()) {
            m.getRows().add(field(r));
        }
        for (OssieAiQueryRequest.FieldRef c : req.getColumns()) {
            m.getColumns().add(field(c));
        }
        for (OssieAiQueryRequest.MetricRef v : req.getValues()) {
            OssieQueryModel.MetricRef mr = new OssieQueryModel.MetricRef();
            mr.setMetric(v.getMetric());
            mr.setAggregation(v.getAggregation());
            m.getValues().add(mr);
        }
        for (OssieAiQueryRequest.FilterExpr f : req.getFilters()) {
            OssieQueryModel.FilterExpr fe = new OssieQueryModel.FilterExpr();
            fe.setDataset(f.getDataset());
            fe.setField(f.getField());
            fe.setOp(f.getOp());
            fe.setValue(f.getValue());
            fe.setValues(f.getValues());
            m.getFilters().add(fe);
        }
        for (OssieAiQueryRequest.SortRef s : req.getSorts()) {
            OssieQueryModel.SortRef sr = new OssieQueryModel.SortRef();
            sr.setMetric(s.getMetric());
            sr.setDataset(s.getDataset());
            sr.setField(s.getField());
            sr.setDirection(s.getDirection());
            m.getSorts().add(sr);
        }
        m.setLimit(req.getLimit());
        return m;
    }

    private OssieQueryModel.FieldRef field(OssieAiQueryRequest.FieldRef ref) {
        OssieQueryModel.FieldRef f = new OssieQueryModel.FieldRef();
        f.setDataset(ref.getDataset());
        f.setField(ref.getField());
        return f;
    }

    /**
     * Project the executor's {@link CellDataSet} into records-format response. Column order:
     * row-shelf fields, then column-shelf fields, then metrics — matches the header order the
     * translator emits.
     */
    @SuppressWarnings("StringConcatenationInLoop")
    private OssieAiQueryResponse toRecordsResponse(
            String queryId, long runtime, CellDataSet cds, OssieAiSchema schema, OssieAiQueryRequest req) {
        OssieAiQueryResponse out = new OssieAiQueryResponse();
        out.setQueryId(queryId);
        out.setRuntime(runtime);

        if (cds == null || cds.getCellSetHeaders() == null || cds.getCellSetHeaders().length == 0) {
            out.getMeta().setRowCount(0);
            return out;
        }
        AbstractBaseCell[] header = cds.getCellSetHeaders()[0];
        int nRows = req.getRows().size();
        int nCols = req.getColumns().size();
        int nDims = nRows + nCols;
        // Build column descriptors from the request shape (not the header) so we get metric
        // metadata (aggregation kind, unit) alongside the dimension keys.
        int dimIdx = 0;
        for (OssieAiQueryRequest.FieldRef r : req.getRows()) {
            OssieAiQueryResponse.Column col = new OssieAiQueryResponse.Column(
                    r.getDataset() + "." + r.getField(), headerLabel(header, dimIdx, r.getField()), "dimension");
            out.getColumns().add(col);
            dimIdx++;
        }
        for (OssieAiQueryRequest.FieldRef c : req.getColumns()) {
            OssieAiQueryResponse.Column col = new OssieAiQueryResponse.Column(
                    c.getDataset() + "." + c.getField(), headerLabel(header, dimIdx, c.getField()), "dimension");
            out.getColumns().add(col);
            dimIdx++;
        }
        for (int i = 0; i < req.getValues().size(); i++) {
            OssieAiQueryRequest.MetricRef v = req.getValues().get(i);
            OssieAiSchema.Metric mSchema = schema.getMetrics().get(v.getMetric());
            OssieAiQueryResponse.Column col = new OssieAiQueryResponse.Column(
                    v.getMetric(),
                    mSchema != null && mSchema.getDisplayName() != null ? mSchema.getDisplayName() : v.getMetric(),
                    "metric");
            String appliedAgg = v.getAggregation() != null
                    ? v.getAggregation().toLowerCase()
                    : mSchema != null ? mSchema.getAggregationKind() : null;
            col.setAggregationKind(appliedAgg);
            if (mSchema != null) col.setUnit(mSchema.getUnit());
            out.getColumns().add(col);
        }

        // Build records.
        if (cds.getCellSetBody() != null) {
            for (AbstractBaseCell[] row : cds.getCellSetBody()) {
                Map<String, Object> rec = OssieAiQueryResponse.newRecord();
                for (int i = 0; i < row.length && i < out.getColumns().size(); i++) {
                    OssieAiQueryResponse.Column col = out.getColumns().get(i);
                    AbstractBaseCell cell = row[i];
                    if ("metric".equals(col.getType())) {
                        rec.put(col.getKey(), toCellValue(cell, col.getUnit()));
                    } else {
                        rec.put(col.getKey(), cell.getFormattedValue());
                    }
                }
                out.getRecords().add(rec);
            }
        }
        out.getMeta().setRowCount(out.getRecords().size());
        applyKAnonymity(out);
        return out;
    }

    /**
     * Suppress metric values on rows whose count-shaped metric falls below k. Uses the same
     * {@link KAnonymityFilter} the MDX AI path uses so the threshold + mask are configured in
     * one place (env {@code SAIKU_AI_KANONYMITY_K}, or property
     * {@code saiku.ai.kanonymity.k}).
     *
     * <p>Detection heuristic: a column is a "count-shaped metric" when its
     * {@code aggregationKind} is {@code count}. That covers both {@code COUNT(*)} metrics
     * declared in the YAML and metrics whose expression parses to a count. For non-count
     * shelves the filter is a no-op — semantically we can't decide what "backing count" means
     * for e.g. AVG(price), so we leave those responses unchanged rather than silently
     * suppress everything.
     */
    private void applyKAnonymity(OssieAiQueryResponse resp) {
        if (kAnonymityFilter == null || !kAnonymityFilter.enabled()) return;
        List<String> countKeys = new ArrayList<>();
        for (OssieAiQueryResponse.Column c : resp.getColumns()) {
            if ("count".equalsIgnoreCase(c.getAggregationKind())) countKeys.add(c.getKey());
        }
        if (countKeys.isEmpty()) return;
        int suppressed = 0;
        for (Map<String, Object> rec : resp.getRecords()) {
            int max = 0;
            for (String key : countKeys) {
                Object v = rec.get(key);
                if (v instanceof OssieAiQueryResponse.CellValue cv && cv.getValue() instanceof Number n) {
                    max = Math.max(max, n.intValue());
                }
            }
            if (kAnonymityFilter.shouldSuppress(max)) {
                for (OssieAiQueryResponse.Column c : resp.getColumns()) {
                    if (!"metric".equals(c.getType())) continue;
                    OssieAiQueryResponse.CellValue masked =
                            new OssieAiQueryResponse.CellValue(null, kAnonymityFilter.maskValue(), c.getUnit());
                    rec.put(c.getKey(), masked);
                }
                suppressed++;
            }
        }
        if (suppressed > 0) {
            OssieAiQueryResponse.Suppressed s = new OssieAiQueryResponse.Suppressed();
            s.setCount(suppressed);
            s.setReason("k-anonymity threshold k=" + kAnonymityFilter.threshold());
            resp.getMeta().setSuppressed(s);
        }
    }

    /**
     * Matrix-format response — 1:1 the CellDataSet envelope the MDX matrix path uses so
     * downstream consumers that already render OLAP results work unchanged on Ossie. Header
     * cells serialise as {@code {value, type}}; body cells add {@code raw} when a numeric parse
     * survives.
     */
    private Map<String, Object> toMatrixResponse(
            String queryId, long runtime, CellDataSet cds, OssieAiSchema schema, OssieAiQueryRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        body.put("runtime", runtime);
        body.put("format", "matrix");
        List<List<Map<String, Object>>> headers = new ArrayList<>();
        if (cds != null && cds.getCellSetHeaders() != null) {
            for (AbstractBaseCell[] row : cds.getCellSetHeaders()) {
                List<Map<String, Object>> outRow = new ArrayList<>();
                for (AbstractBaseCell c : row) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("value", c.getFormattedValue());
                    cell.put("type", "COLUMN_HEADER");
                    outRow.add(cell);
                }
                headers.add(outRow);
            }
        }
        List<List<Map<String, Object>>> bodyRows = new ArrayList<>();
        if (cds != null && cds.getCellSetBody() != null) {
            for (AbstractBaseCell[] row : cds.getCellSetBody()) {
                List<Map<String, Object>> outRow = new ArrayList<>();
                for (AbstractBaseCell c : row) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("value", c.getFormattedValue());
                    if (c instanceof DataCell dc) {
                        cell.put("type", "DATA_CELL");
                        Object raw = dc.getRawNumber();
                        if (raw != null) cell.put("raw", raw);
                    } else {
                        cell.put("type", "ROW_HEADER");
                    }
                    outRow.add(cell);
                }
                bodyRows.add(outRow);
            }
        }
        body.put("cellSetHeaders", headers);
        body.put("cellSetBody", bodyRows);
        applyKAnonymityMatrix(body, schema, req);
        return body;
    }

    /**
     * K-anonymity suppression for the matrix-format response (#1402). Same detection
     * heuristic as the records-format path: any column whose metric declares an aggregation
     * kind of {@code count} is treated as the row's backing count. When the count falls
     * below k, every DATA_CELL on that row is replaced with the mask, and a top-level
     * {@code suppressed} block records the count + reason.
     *
     * <p>Column index alignment: the header row emits row-shelf then column-shelf then
     * metrics — same order the records-format path uses to build column descriptors —
     * so we can identify metric column positions purely from the request shape.
     */
    @SuppressWarnings("unchecked")
    private void applyKAnonymityMatrix(Map<String, Object> body, OssieAiSchema schema, OssieAiQueryRequest req) {
        if (kAnonymityFilter == null || !kAnonymityFilter.enabled()) return;
        if (schema == null || req == null) return;
        int nRows = req.getRows().size();
        int nCols = req.getColumns().size();
        int nDims = nRows + nCols;
        // Discover which metric columns are count-shaped so we know which cell indexes to
        // treat as backing counts.
        List<Integer> countCols = new ArrayList<>();
        List<Integer> metricCols = new ArrayList<>();
        for (int i = 0; i < req.getValues().size(); i++) {
            OssieAiSchema.Metric m =
                    schema.getMetrics().get(req.getValues().get(i).getMetric());
            int colIdx = nDims + i;
            metricCols.add(colIdx);
            String agg = req.getValues().get(i).getAggregation();
            String effectiveAgg = agg != null ? agg.toLowerCase() : (m != null ? m.getAggregationKind() : null);
            if ("count".equalsIgnoreCase(effectiveAgg)) countCols.add(colIdx);
        }
        if (countCols.isEmpty()) return;

        List<List<Map<String, Object>>> rows = (List<List<Map<String, Object>>>) body.get("cellSetBody");
        if (rows == null) return;
        int suppressed = 0;
        for (List<Map<String, Object>> row : rows) {
            int rowMax = 0;
            for (int ci : countCols) {
                if (ci >= row.size()) continue;
                Map<String, Object> cell = row.get(ci);
                Object raw = cell.get("raw");
                if (raw instanceof Number n) {
                    rowMax = Math.max(rowMax, n.intValue());
                }
            }
            if (kAnonymityFilter.shouldSuppress(rowMax)) {
                for (int mi : metricCols) {
                    if (mi >= row.size()) continue;
                    Map<String, Object> cell = row.get(mi);
                    cell.put("value", kAnonymityFilter.maskValue());
                    cell.remove("raw");
                }
                suppressed++;
            }
        }
        if (suppressed > 0) {
            Map<String, Object> sup = new LinkedHashMap<>();
            sup.put("count", suppressed);
            sup.put("reason", "k-anonymity threshold k=" + kAnonymityFilter.threshold());
            body.put("suppressed", sup);
        }
    }

    private String headerLabel(AbstractBaseCell[] header, int idx, String fallback) {
        if (idx < header.length && header[idx] != null) return header[idx].getFormattedValue();
        return fallback;
    }

    private OssieAiQueryResponse.CellValue toCellValue(AbstractBaseCell cell, String unit) {
        if (cell instanceof DataCell dc) {
            Object rawNum = dc.getRawNumber();
            return new OssieAiQueryResponse.CellValue(
                    rawNum != null ? rawNum : cell.getFormattedValue(), cell.getFormattedValue(), unit);
        }
        if (cell instanceof MemberCell mc) {
            return new OssieAiQueryResponse.CellValue(cell.getFormattedValue(), cell.getFormattedValue(), unit);
        }
        return new OssieAiQueryResponse.CellValue(
                cell != null ? cell.getFormattedValue() : null, cell != null ? cell.getFormattedValue() : null, unit);
    }

    /**
     * Best-effort JDBC unwrap for the connection whose sample values we need. Returns null on
     * anything unexpected — the projector treats a null connection as "skip samples" so the
     * schema endpoint still works.
     */
    private Connection openWarehouseConnection(String connectionName) {
        if (datasourceManager == null) return null;
        SaikuDatasource ds = datasourceManager.getDatasource(connectionName);
        if (ds == null || ds.getType() != SaikuDatasource.Type.OSSIE) return null;
        try {
            SaikuOssieConnection conn = new SaikuOssieConnection(connectionName, ds.getProperties());
            if (!conn.connect()) return null;
            Object raw = conn.getConnection();
            if (raw instanceof Connection c) return c;
        } catch (Exception e) {
            log.debug("openWarehouseConnection failed for {}: {}", connectionName, e.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------
    // Response builders
    // -------------------------------------------------------------------

    private Response badRequest(String field, String message, List<String> available) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VALIDATION_ERROR");
        body.put("field", field);
        body.put("message", message);
        body.put("available", available == null ? List.of() : available);
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(body)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private Response error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "SERVER_ERROR");
        body.put("message", message);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(body)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** ISaikuConnection needed by the SaikuOssieConnection cast path. Kept for javadoc reference. */
    @SuppressWarnings("unused")
    private void referenceConnectionType(ISaikuConnection unused) {}
}
