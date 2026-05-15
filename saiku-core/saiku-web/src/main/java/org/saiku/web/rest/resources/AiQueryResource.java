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
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.OlapAiCubeMetadataService;
import org.saiku.service.olap.ai.AiQueryMetadata;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.AiValidationException;
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
    private final AiSchemaConverter converter = new AiSchemaConverter();

    public void setThinQueryService(ThinQueryService tqs) { this.thinQueryService = tqs; }
    public void setCubeMetadataService(AiCubeMetadataService svc) { this.cubeMetadataService = svc; }
    public void setAsyncQueryService(AsyncQueryService a) { this.asyncQueryService = a; }

    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeAi(AiQueryRequest req) {
        long start = System.currentTimeMillis();
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
            log.error("AI query validation failed", e);
            return error("validation failed: " + e.getMessage());
        }

        CellDataSet cds;
        try {
            cds = thinQueryService.execute(tq);
        } catch (RuntimeException e) {
            log.error("AI query execution failed", e);
            return error("execute failed: " + e.getMessage());
        }

        AiQueryResponse resp = buildResponse(tq, cds, start);
        return Response.ok(resp).type(MediaType.APPLICATION_JSON).build();
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
            return badRequest("cubeId",
                    "cubeId must be connection/catalog/schema/cube", null);
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
        boolean ok = asyncQueryService.cancel(queryId);
        if (!ok) return Response.status(Response.Status.NOT_FOUND).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        body.put("status", "CANCELLED");
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    /* ----------------------- Phase 4: drillthrough ---------------------- */

    /**
     * Drill into a single cell of an earlier result. Uses the same
     * {@link ThinQueryService#drillthrough(String, int, String)} the
     * regular query API does — the {@code queryId} for an AI query is
     * either the sync ThinQuery name (returned in {@code AiQueryResponse.queryId})
     * or the async handle's underlying ThinQuery name.
     */
    @GET
    @Path("/query/{queryId}/drillthrough")
    @Produces(MediaType.APPLICATION_JSON)
    public Response drillthrough(
            @PathParam("queryId") String queryId,
            @QueryParam("maxrows") @DefaultValue("100") int maxrows,
            @QueryParam("returns") String returns) {
        if (thinQueryService == null) return error("Query service not configured");
        // For async queries the handle id != the underlying ThinQuery name.
        // Resolve through the handle when possible.
        String name = queryId;
        if (asyncQueryService != null) {
            AsyncQueryHandle h = asyncQueryService.get(queryId);
            if (h != null) name = h.getQuery().getName();
        }
        try {
            java.sql.ResultSet rs = thinQueryService.drillthrough(name, maxrows, returns);
            List<Map<String, String>> rows = new ArrayList<>();
            if (rs != null) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int c = 1; c <= colCount; c++) {
                        Object v = rs.getObject(c);
                        row.put(md.getColumnLabel(c), v == null ? "" : v.toString());
                    }
                    rows.add(row);
                }
                rs.close();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("queryId", queryId);
            body.put("rowCount", rows.size());
            body.put("rows", rows);
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.error("AI drillthrough failed for {}", queryId, e);
            return error("drillthrough failed: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */

    private AiQueryResponse buildResponse(ThinQuery tq, CellDataSet cds, long startedAt) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setQueryId(tq.getName());
        resp.setStatus(AiQueryResponse.Status.SUCCESS);
        AiQueryMetadata meta = new AiQueryMetadata();
        meta.setGeneratedMdx(tq.getMdx());
        resp.setMetadata(meta);

        if (cds != null) {
            // CellDataSet carries two parallel matrices: headers (column
            // captions) at the top and body (row headers + data cells).
            // We split each body row into its leading MemberCell run
            // (= row caption) and trailing DataCell run (= matrix entry).
            AbstractBaseCell[][] headers = cds.getCellSetHeaders();
            AbstractBaseCell[][] body = cds.getCellSetBody();
            int totalWidth = body != null && body.length > 0 ? body[0].length : 0;
            int rowHeaderCount = countRowHeaderColumns(body);

            // Column captions: take the last header row's data-cell section.
            List<AiQueryMetadata.Caption> cols = new ArrayList<>();
            if (headers != null && headers.length > 0) {
                AbstractBaseCell[] lastHeader = headers[headers.length - 1];
                for (int c = rowHeaderCount; c < lastHeader.length; c++) {
                    String caption = lastHeader[c] == null ? "" : safe(lastHeader[c].getFormattedValue());
                    cols.add(new AiQueryMetadata.Caption(caption, caption));
                }
            }
            meta.setColumns(cols);

            // Row captions + matrix.
            List<AiQueryMetadata.Caption> rows = new ArrayList<>();
            List<Map<String, String>> matrix = new ArrayList<>();
            if (body != null) {
                for (AbstractBaseCell[] row : body) {
                    rows.add(new AiQueryMetadata.Caption(rowName(row, rowHeaderCount), rowCaption(row, rowHeaderCount)));
                    Map<String, String> cells = new LinkedHashMap<>();
                    for (int c = rowHeaderCount; c < totalWidth; c++) {
                        cells.put(String.valueOf(c - rowHeaderCount),
                                row[c] == null ? "" : safe(row[c].getFormattedValue()));
                    }
                    matrix.add(cells);
                }
            }
            meta.setRows(rows);
            resp.setMatrix(matrix);
            resp.setTotalRows(rows.size());

            // Measures section: just echo the measure-name caption from
            // the last header row (the data-cell section captions).
            List<String> measureNames = new ArrayList<>();
            for (AiQueryMetadata.Caption c : cols) measureNames.add(c.getCaption());
            meta.setMeasures(measureNames);
        }
        resp.setRuntimeMs(System.currentTimeMillis() - startedAt);
        return resp;
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

    private static String safe(String s) { return s == null ? "" : s; }

    private Response badRequest(String field, String message, List<String> available) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);
        resp.setError(message);
        resp.setField(field);
        if (available != null) resp.setAvailable(available);
        return Response.status(Response.Status.BAD_REQUEST).entity(resp)
                .type(MediaType.APPLICATION_JSON).build();
    }

    private Response error(String message) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setStatus(AiQueryResponse.Status.EXECUTION_ERROR);
        resp.setError(message);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(resp)
                .type(MediaType.APPLICATION_JSON).build();
    }
}
