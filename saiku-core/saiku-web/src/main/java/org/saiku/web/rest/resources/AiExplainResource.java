/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.saiku.service.olap.ai.explain.CellExplainException;
import org.saiku.service.olap.ai.explain.CellExplainRequest;
import org.saiku.service.olap.ai.explain.CellExplainResult;
import org.saiku.service.olap.ai.explain.CellExplainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Explain this number" (saiku#1118) — {@code POST /saiku/api/ai/explain}.
 *
 * <p>Body is {@link CellExplainRequest} ({@code {queryId | queryName, position:{row,column}}}),
 * response is {@link CellExplainResult} ({@code {mdx, cellMdx, sql, drivers, narrative, …}}).
 * Unlike the rest of this surface the endpoint is for the person looking at a cellset, not for an
 * agent: it names a query in the caller's own session and explains one cell of the result already
 * on their screen.
 *
 * <p>It shares the {@code /saiku/api/ai} path and therefore the AI audit filter, so every explain
 * lands in {@code ai-audit.jsonl} alongside the asks. It deliberately does NOT re-check the
 * data-exposure policy on the way out: every figure in the response is already visible to this
 * authenticated caller in the cellset they right-clicked. What leaves the box is the LLM prompt,
 * and that path is the ask service's, which applies the egress policy itself.
 */
@Path("/saiku/api/ai")
public class AiExplainResource {

    private static final Logger log = LoggerFactory.getLogger(AiExplainResource.class);

    private CellExplainService explainService;

    public void setExplainService(CellExplainService explainService) {
        this.explainService = explainService;
    }

    @POST
    @Path("/explain")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response explain(CellExplainRequest request) {
        if (explainService == null) {
            return error(503, "NOT_CONFIGURED", "the explain service is not wired on this deployment");
        }
        try {
            return Response.ok(explainService.explain(request))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (CellExplainException e) {
            return error(e.code().status(), e.code().code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Explain this number failed", e);
            return error(500, CellExplainException.Code.EXPLAIN_FAILED.code(), "explain failed");
        }
    }

    private Response error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("error", message == null ? code : message);
        body.put("code", code);
        return Response.status(status)
                .entity(body)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
