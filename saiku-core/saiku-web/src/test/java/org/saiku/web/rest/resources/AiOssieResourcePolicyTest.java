/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiPolicyViolation;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;

/**
 * saiku#2322 — call-site coverage: proves {@link AiOssieResource} actually consults the
 * {@link AiPolicyGuard} at every data endpoint, not just that the guard works in isolation
 * (modelled on {@link AiQueryResourcePolicyTest}). The guard check is the first statement of
 * each endpoint, so a too-restrictive policy throws {@link AiPolicyViolation} before any
 * service is touched — no warehouse connection is opened, and the JAX-RS
 * {@code AiPolicyViolationMapper} turns the exception into a 403 at runtime.
 */
public class AiOssieResourcePolicyTest {

    /** A query service that fails the test if anything ever reaches execution. */
    private static class MustNotExecute extends OssieQueryService {
        @Override
        public org.saiku.olap.dto.resultset.CellDataSet execute(org.saiku.olap.query2.ThinQuery tq) throws Exception {
            throw new AssertionError("warehouse execution must never be reached under the policy gate");
        }
    }

    private AiOssieResource resource;

    @Before
    public void setUp() {
        resource = new AiOssieResource();
        resource.setOssieQueryService(new MustNotExecute());
    }

    private static AiOssieResource resourceWith(AiPolicy policy) {
        AiOssieResource r = new AiOssieResource();
        r.setOssieQueryService(new MustNotExecute());
        r.setAiPolicyGuard(new AiPolicyGuard(policy));
        return r;
    }

    private static OssieAiQueryRequest query() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setConnection("Flights");
        return req;
    }

    /* -------------------------- blocked under schema-only -------------------------- */

    @Test
    public void query_blocked_under_schema_only() {
        AiOssieResource r = resourceWith(AiPolicy.SCHEMA_ONLY);
        try {
            r.executeAi(new OssieAiQueryRequest(), "records");
            fail("schema-only must block /ai/ossie/query");
        } catch (AiPolicyViolation v) {
            assertEquals(AiDataKind.AGGREGATED_RESULT_VALUES, v.getKind());
            assertEquals(AiPolicy.SCHEMA_ONLY, v.getCurrent());
        }
    }

    @Test
    public void anomaly_forecast_async_blocked_under_schema_only() {
        // One case each for the aggregate-returning siblings of /query: /anomaly, /forecast,
        // async submit and async result. The async RESULT gate means a policy tightened between
        // submit and fetch (restart with a new env) still fails closed.
        for (String endpoint : java.util.List.of("anomaly", "forecast", "async-submit", "async-result")) {
            AiOssieResource r = resourceWith(AiPolicy.SCHEMA_ONLY);
            try {
                switch (endpoint) {
                    case "anomaly" -> r.detectAnomalies(null);
                    case "forecast" -> r.forecast(null);
                    case "async-submit" -> r.executeAsync(null);
                    case "async-result" -> r.asyncResult("ossie-ai-async-x", null);
                    default -> throw new IllegalStateException(endpoint);
                }
                fail(endpoint + " must be blocked under schema-only");
            } catch (AiPolicyViolation v) {
                assertEquals(endpoint, AiDataKind.AGGREGATED_RESULT_VALUES, v.getKind());
                assertEquals(endpoint, AiPolicy.SCHEMA_ONLY, v.getCurrent());
            }
        }
    }

    @Test
    public void unwiredResourceDefaultsToSchemaOnly() {
        // No setAiPolicyGuard() → the field defaults to the FAIL-CLOSED AiPolicy.DEFAULT
        // (schema-only), not the old permissive FULL. This is the saiku#2322 fix itself: every
        // production wiring used to run this class unguarded because no XML injected the bean.
        try {
            resource.executeAi(new OssieAiQueryRequest(), "records");
            fail("an unwired AiOssieResource must refuse aggregated data");
        } catch (AiPolicyViolation v) {
            assertEquals(AiDataKind.AGGREGATED_RESULT_VALUES, v.getKind());
            assertEquals(AiPolicy.SCHEMA_ONLY, v.getCurrent());
        }
    }

    /* -------------------------- blocked under aggregated --------------------------- */

    @Test
    public void rowDetail_blocked_under_aggregated() {
        AiOssieResource r = resourceWith(AiPolicy.AGGREGATED);
        try {
            r.rowDetail(query(), 10);
            fail("aggregated must block /ai/ossie/row-detail (raw rows)");
        } catch (AiPolicyViolation v) {
            assertEquals(AiDataKind.RAW_ROW_DATA, v.getKind());
            assertEquals(AiPolicy.AGGREGATED, v.getCurrent());
            assertEquals(
                    "the caller is told which knob to raise",
                    "full",
                    v.getRequired().displayName());
        }
    }

    /* --------------------------- passes the guard cases ---------------------------- */

    @Test
    public void query_passes_guard_under_aggregated() {
        // AGGREGATED permits /query values → the guard must NOT throw. Downstream fails on
        // unwired services (the error envelope, 500); we only assert the gate opened.
        AiOssieResource r = resourceWith(AiPolicy.AGGREGATED);
        try {
            r.executeAi(new OssieAiQueryRequest(), "records");
        } catch (AiPolicyViolation e) {
            fail("AGGREGATED must permit /ai/ossie/query");
        }
    }

    @Test
    public void rowDetail_passes_guard_under_full() {
        // FULL permits raw rows → the guard must NOT throw; downstream fails on unwired services
        // (a 400 for the missing connection), which is not a policy result.
        AiOssieResource r = resourceWith(AiPolicy.FULL);
        Response resp = r.rowDetail(query(), 10);
        assertNotEquals(403, resp.getStatus());
    }

    @Test
    public void schema_metadata_stays_available_under_schema_only() {
        // The tier floor, not a blanket block: schema metadata, ontology, preview and the
        // PII-filtered distinct-value search must all survive a schema-only guard. Each hits its
        // unwired-service error (or a 400 bad-request) but never a policy refusal.
        AiOssieResource r = resourceWith(AiPolicy.SCHEMA_ONLY);
        assertNotEquals(403, r.getSchema("Flights", "Flights", null).getStatus());
        assertNotEquals(403, r.getOntology("Flights", "Flights").getStatus());
        assertNotEquals(403, r.previewAi(new OssieAiQueryRequest()).getStatus());
        assertNotEquals(
                403,
                r.searchValues("Flights", null, "flight", "carrier_id", "DL", 10)
                        .getStatus());
    }
}
