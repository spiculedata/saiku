/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiQueryMetadata;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.AiValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final AiSchemaConverter converter = new AiSchemaConverter();

    public void setThinQueryService(ThinQueryService tqs) { this.thinQueryService = tqs; }
    public void setCubeMetadataService(AiCubeMetadataService svc) { this.cubeMetadataService = svc; }

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
