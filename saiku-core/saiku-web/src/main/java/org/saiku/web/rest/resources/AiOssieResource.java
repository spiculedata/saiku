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
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;
import org.saiku.service.ossie.ai.OssieAiQueryResponse;
import org.saiku.service.ossie.ai.OssieAiSchema;
import org.saiku.service.ossie.ai.OssieAiSchemaProjector;
import org.saiku.service.ossie.ai.OssieAiValidationException;
import org.saiku.service.ossie.ai.OssieAiValidator;
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
    public Response getSchema(@PathParam("connection") String connectionName, @PathParam("model") String modelName) {
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
    public Response executeAi(OssieAiQueryRequest req) {
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
        // truncated + suppressed are R2 concerns; leave them at defaults.
        return out;
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
