/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.audit.AiAuditEntry;

/**
 * saiku#906 — coverage for the audit backbone's pure core ({@code buildEntry}):
 * derives outcome + allow/deny from the response status, only fires for the
 * {@code /ai/} surface, records the active policy tier, and folds in enrichment.
 */
public class AiAuditFilterTest {

    private static final AiPolicyGuard GUARD = new AiPolicyGuard(AiPolicy.SCHEMA_ONLY);

    private static Map<String, Object> enrich(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void audits_an_ai_call_success() {
        AiAuditEntry e = AiAuditFilter.buildEntry("/saiku/api/ai/query", "alice", 200, 12L, null, GUARD);
        assertEquals("/saiku/api/ai/query", e.endpoint);
        assertEquals("alice", e.user);
        assertEquals(AiAuditEntry.OUTCOME_SUCCESS, e.outcome);
        assertEquals(AiAuditEntry.DECISION_ALLOW, e.policyDecision);
        assertEquals("schema-only", e.policy);
        assertEquals(Long.valueOf(12L), e.latencyMs);
        assertNull("no error on success", e.error);
    }

    @Test
    public void records_denied_on_403() {
        AiAuditEntry e = AiAuditFilter.buildEntry("/saiku/api/ai/query", "bob", 403, 5L, null, GUARD);
        assertEquals(AiAuditEntry.OUTCOME_DENIED, e.outcome);
        assertEquals(AiAuditEntry.DECISION_DENY, e.policyDecision);
        assertEquals("HTTP 403", e.error);
    }

    @Test
    public void records_validation_error_on_400() {
        AiAuditEntry e = AiAuditFilter.buildEntry("/saiku/api/ai/query", "bob", 400, 5L, null, GUARD);
        assertEquals(AiAuditEntry.OUTCOME_VALIDATION_ERROR, e.outcome);
        assertEquals(AiAuditEntry.DECISION_ALLOW, e.policyDecision);
    }

    @Test
    public void server_error_maps_to_error_outcome() {
        AiAuditEntry e = AiAuditFilter.buildEntry("/saiku/api/ai/query", "bob", 500, 5L, null, GUARD);
        assertEquals(AiAuditEntry.OUTCOME_ERROR, e.outcome);
        assertEquals("HTTP 500", e.error);
    }

    @Test
    public void skips_non_ai_paths() {
        // The admin audit read endpoint (/saiku/admin/ai-audit) and other non-AI
        // calls must NOT be audited (no "/ai/" segment) -> null entry.
        assertNull(AiAuditFilter.buildEntry("/saiku/admin/ai-audit", "admin", 200, 1L, null, GUARD));
        assertNull(AiAuditFilter.buildEntry("/saiku/session", "admin", 200, 1L, null, GUARD));
    }

    @Test
    public void anonymous_when_no_principal() {
        AiAuditEntry e = AiAuditFilter.buildEntry("/saiku/api/ai/cubes", null, 200, 1L, null, GUARD);
        assertEquals("anonymous", e.user);
    }

    @Test
    public void folds_in_resource_enrichment() {
        Map<String, Object> en = enrich(
                AiAuditFilter.P_CUBE, "Sales",
                AiAuditFilter.P_FINGERPRINT, "sha256:abc",
                AiAuditFilter.P_PROMPT_TOKENS, 412,
                AiAuditFilter.P_UPSTREAM, "ollama");
        AiAuditEntry e = AiAuditFilter.buildEntry("/saiku/api/ai/ask", "alice", 200, 9L, en, GUARD);
        assertEquals("Sales", e.cube);
        assertEquals("sha256:abc", e.schemaFingerprint);
        assertEquals(Integer.valueOf(412), e.promptTokens);
        assertEquals("ollama", e.upstream);
    }

    @Test
    public void outcome_for_status_boundaries() {
        assertEquals(AiAuditEntry.OUTCOME_SUCCESS, AiAuditFilter.outcomeFor(204));
        assertEquals(AiAuditEntry.OUTCOME_VALIDATION_ERROR, AiAuditFilter.outcomeFor(400));
        assertEquals(AiAuditEntry.OUTCOME_DENIED, AiAuditFilter.outcomeFor(403));
        assertEquals(AiAuditEntry.OUTCOME_ERROR, AiAuditFilter.outcomeFor(503));
    }
}
