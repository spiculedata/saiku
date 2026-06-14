/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyViolation;
import org.saiku.service.olap.ai.AiQueryResponse;

/**
 * saiku#903 follow-up: locks the JAX-RS {@link AiPolicyViolationMapper} envelope.
 * The endpoint call-site tests ({@code AiQueryResourcePolicyTest}) prove the guard
 * THROWS; this proves the throw becomes the right client response — a typed
 * {@code 403 PERMISSION_DENIED} with {@code field=ai.policy}, {@code available=[required tier]},
 * a remediation hint naming the knob, and (the security point SEC verified by code
 * review) no internal cause / SQL / credential leaked into the body.
 */
public class AiPolicyViolationMapperTest {

    @Test
    public void maps_violation_to_403_permission_denied_envelope() {
        // SCHEMA_ONLY policy asked to send RAW_ROW_DATA → the required tier is FULL.
        AiPolicyViolation v = new AiPolicyViolation(AiDataKind.RAW_ROW_DATA, AiPolicy.SCHEMA_ONLY);
        Response r = new AiPolicyViolationMapper().toResponse(v);

        assertEquals(403, r.getStatus());
        AiQueryResponse body = (AiQueryResponse) r.getEntity();
        assertEquals(AiQueryResponse.Status.PERMISSION_DENIED, body.getStatus());
        assertEquals("ai.policy", body.getField());
        // available points the caller at exactly the knob value that would permit it.
        assertEquals(List.of(AiPolicy.FULL.displayName()), body.getAvailable());
    }

    @Test
    public void error_message_names_the_knob_and_required_tier_and_does_not_leak() {
        AiPolicyViolation v = new AiPolicyViolation(AiDataKind.AGGREGATED_RESULT_VALUES, AiPolicy.SCHEMA_ONLY);
        AiQueryResponse body =
                (AiQueryResponse) new AiPolicyViolationMapper().toResponse(v).getEntity();
        String msg = body.getError();

        assertTrue("remediation names the ai.policy knob: " + msg, msg.contains(AiPolicy.PROP));
        assertTrue("remediation names the required tier: " + msg, msg.contains(AiPolicy.AGGREGATED.displayName()));
        // A policy refusal must never echo backend internals (the #1261/#1283 leak class).
        String lower = msg.toLowerCase(Locale.ROOT);
        assertFalse(
                "policy 403 must not leak internals: " + msg,
                lower.contains("jdbc:") || lower.contains("sqlstate") || lower.contains("password"));
    }
}
