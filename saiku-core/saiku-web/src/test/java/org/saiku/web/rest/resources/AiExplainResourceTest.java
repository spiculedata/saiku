/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.explain.CellExplainException;
import org.saiku.service.olap.ai.explain.CellExplainRequest;
import org.saiku.service.olap.ai.explain.CellExplainResult;
import org.saiku.service.olap.ai.explain.CellExplainService;

/**
 * saiku#1118 — the HTTP contract of {@code POST /saiku/api/ai/explain}: the status each failure
 * mode answers with, and that the result is handed back untouched on success.
 */
public class AiExplainResourceTest {

    /** Runs {@code explain}, answering with the given result or throwing the given failure. */
    private static AiExplainResource resourceReturning(Object outcome) {
        AiExplainResource resource = new AiExplainResource();
        resource.setExplainService(new CellExplainService() {
            @Override
            public CellExplainResult explain(CellExplainRequest request) {
                if (outcome instanceof CellExplainException e) {
                    throw e;
                }
                if (outcome instanceof RuntimeException e) {
                    throw e;
                }
                return (CellExplainResult) outcome;
            }
        });
        return resource;
    }

    private static CellExplainRequest request() {
        CellExplainRequest request = new CellExplainRequest();
        request.setQueryId("q1");
        request.setPosition(new CellExplainRequest.CellCoordinate(0, 0));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Response response) {
        assertTrue(response.getEntity() instanceof Map);
        return (Map<String, Object>) response.getEntity();
    }

    @Test
    public void returnsTheResultOnSuccess() {
        CellExplainResult result = new CellExplainResult();
        result.setNarrativeSource(CellExplainResult.NarrativeSource.COMPUTED);

        Response response = resourceReturning(result).explain(request());

        assertEquals(200, response.getStatus());
        assertEquals(result, response.getEntity());
    }

    @Test
    public void unknownQueryIs404() {
        Response response = resourceReturning(
                        new CellExplainException(CellExplainException.Code.UNKNOWN_QUERY, "no query named 'q1'"))
                .explain(request());

        assertEquals(404, response.getStatus());
        assertEquals("UNKNOWN_QUERY", error(response).get("code"));
        assertEquals("no query named 'q1'", error(response).get("error"));
    }

    @Test
    public void anUnexecutedQueryIs409() {
        Response response = resourceReturning(
                        new CellExplainException(CellExplainException.Code.NOT_EXECUTED, "run it first"))
                .explain(request());
        assertEquals(409, response.getStatus());
        assertEquals("NOT_EXECUTED", error(response).get("code"));
    }

    @Test
    public void aBadCoordinateIs400() {
        Response response = resourceReturning(
                        new CellExplainException(CellExplainException.Code.VALIDATION_ERROR, "position 9:0 is outside"))
                .explain(request());
        assertEquals(400, response.getStatus());
        assertEquals("VALIDATION_ERROR", error(response).get("code"));
        assertEquals("ERROR", error(response).get("status"));
    }

    /** An unexpected throwable must not leak planner or datasource text to the caller. */
    @Test
    public void anUnexpectedFailureIsAnOpaque500() {
        Response response = resourceReturning(new IllegalStateException("jdbc:h2:mem:secret failed"))
                .explain(request());

        assertEquals(500, response.getStatus());
        assertEquals("EXPLAIN_FAILED", error(response).get("code"));
        assertEquals("explain failed", error(response).get("error"));
    }

    @Test
    public void unwiredServiceIs503RatherThanAnNpe() {
        Response response = new AiExplainResource().explain(request());

        assertEquals(503, response.getStatus());
        assertEquals("NOT_CONFIGURED", error(response).get("code"));
    }
}
