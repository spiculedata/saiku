/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;

import jakarta.ws.rs.core.Response;
import org.junit.Test;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.forecast.AiForecastRequest;

/**
 * saiku#908 — input-validation coverage for the {@code POST /ai/forecast} endpoint
 * ({@link AiQueryResource#forecast}). The endpoint validates body / query / timeAxis / method /
 * horizon / confidence BEFORE executing the (expensive) query, so every bad-input path returns a
 * {@code VALIDATION_ERROR} 400 without touching the injected query services — which makes it
 * cleanly unit-testable on a bare resource (no mocking).
 *
 * <p>Most importantly this locks the <b>endpoint-only</b> {@code horizon} upper bound (1..365):
 * {@code ForecastAssembler} only enforces {@code >= 1}, so the 365 ceiling — a resource-bound guard
 * against an unbounded forecast — lives ONLY in this endpoint and otherwise had no test. The 19
 * detector/assembler/registry tests cover the maths; this covers the wiring (the recurring
 * "primitive tested, call-site not" gap).
 */
public class AiForecastEndpointValidationTest {

    private final AiQueryResource resource = new AiQueryResource();

    /** Assert a 400 VALIDATION_ERROR and return the offending field name. */
    private String rejectedField(Response r) {
        assertEquals(400, r.getStatus());
        AiQueryResponse entity = (AiQueryResponse) r.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, entity.getStatus());
        return entity.getField();
    }

    private static AiForecastRequest body(
            boolean withQuery, String timeAxis, String method, Integer horizon, Double confidence) {
        AiForecastRequest b = new AiForecastRequest();
        if (withQuery) {
            b.setQuery(new AiQueryRequest());
        }
        b.setTimeAxis(timeAxis);
        b.setMethod(method);
        b.setHorizon(horizon);
        b.setConfidence(confidence);
        return b;
    }

    @Test
    public void nullBody_rejected() {
        assertEquals("body", rejectedField(resource.forecast(null)));
    }

    @Test
    public void nullQuery_rejected() {
        assertEquals("query", rejectedField(resource.forecast(body(false, "[Time].[Month]", "ets", 6, 0.95))));
    }

    @Test
    public void blankTimeAxis_rejected() {
        assertEquals("timeAxis", rejectedField(resource.forecast(body(true, "   ", "ets", 6, 0.95))));
    }

    @Test
    public void unknownMethod_rejected() {
        assertEquals("method", rejectedField(resource.forecast(body(true, "[Time].[Month]", "magic", 6, 0.95))));
    }

    /** The endpoint-only upper cap — previously untested. horizon 366 (> 365) must be a 400. */
    @Test
    public void horizonAboveCap_rejected() {
        assertEquals("horizon", rejectedField(resource.forecast(body(true, "[Time].[Month]", "ets", 366, 0.95))));
    }

    @Test
    public void horizonBelowOne_rejected() {
        assertEquals("horizon", rejectedField(resource.forecast(body(true, "[Time].[Month]", "ets", 0, 0.95))));
    }

    @Test
    public void confidenceOutOfRange_rejected() {
        assertEquals("confidence", rejectedField(resource.forecast(body(true, "[Time].[Month]", "ets", 6, 1.5))));
        assertEquals("confidence", rejectedField(resource.forecast(body(true, "[Time].[Month]", "ets", 6, 0.0))));
    }
}
