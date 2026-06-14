/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiPolicyViolation;
import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * saiku#903 — call-site (wiring) coverage: proves {@link AiQueryResource}
 * actually consults the {@link AiPolicyGuard} at each data endpoint, not just
 * that the guard works in isolation. The guard check is the first statement of
 * each endpoint, so a too-restrictive policy throws {@link AiPolicyViolation}
 * before any service is touched (the JAX-RS {@code AiPolicyViolationMapper}
 * turns that into a 403 at runtime).
 */
public class AiQueryResourcePolicyTest {

    private static AiQueryResource resourceWith(AiPolicy policy) {
        AiQueryResource r = new AiQueryResource();
        r.setAiPolicyGuard(new AiPolicyGuard(policy));
        return r;
    }

    @Test
    public void query_blocked_under_schema_only() {
        AiQueryResource r = resourceWith(AiPolicy.SCHEMA_ONLY);
        try {
            r.executeAi(new AiQueryRequest(), "records");
            fail("schema-only must block /ai/query");
        } catch (AiPolicyViolation v) {
            assertEquals(AiDataKind.AGGREGATED_RESULT_VALUES, v.getKind());
            assertEquals(AiPolicy.SCHEMA_ONLY, v.getCurrent());
        }
    }

    @Test
    public void drillthrough_blocked_under_aggregated() {
        AiQueryResource r = resourceWith(AiPolicy.AGGREGATED);
        try {
            r.drillthrough("q", 100, null, null, null);
            fail("aggregated must block drillthrough");
        } catch (AiPolicyViolation v) {
            assertEquals(AiDataKind.RAW_ROW_DATA, v.getKind());
        }
    }

    @Test
    public void query_passes_guard_under_aggregated() {
        // AGGREGATED permits /query values → the guard must NOT throw. Downstream
        // fails on unwired services (returns/throws a non-policy error); we only
        // assert the gate opened.
        AiQueryResource r = resourceWith(AiPolicy.AGGREGATED);
        try {
            r.executeAi(new AiQueryRequest(), "records");
        } catch (AiPolicyViolation e) {
            fail("AGGREGATED must permit /ai/query");
        } catch (RuntimeException downstream) {
            // expected — no cubeMetadataService/thinQueryService wired
        }
    }

    @Test
    public void drillthrough_passes_guard_under_full() {
        AiQueryResource r = resourceWith(AiPolicy.FULL);
        try {
            r.drillthrough("q", 100, null, null, null);
        } catch (AiPolicyViolation e) {
            fail("FULL must permit drillthrough");
        } catch (RuntimeException downstream) {
            // expected — services unwired
        }
    }

    @Test
    public void unwired_guard_defaults_permissive_for_back_compat() {
        // No setAiPolicyGuard() → the field defaults to a FULL no-op so existing
        // callers behave as before; the Spring bean injects the real guard.
        AiQueryResource r = new AiQueryResource();
        try {
            r.executeAi(new AiQueryRequest(), "records");
        } catch (AiPolicyViolation e) {
            fail("un-wired guard must default permissive (FULL)");
        } catch (RuntimeException downstream) {
            // expected
        }
    }
}
