/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.security.Principal;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.audit.AiAuditEntry;
import org.saiku.service.olap.ai.audit.AiAuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku#906 — the audit backbone: a JAX-RS filter that writes one
 * {@link AiAuditEntry} for <em>every</em> {@code /saiku/api/ai/*} call. Because
 * it's a single registered provider, coverage is structural — a new AI endpoint
 * is audited automatically, with no per-endpoint code to forget (the failure
 * mode that makes a compliance audit log worthless).
 *
 * <p>The filter records what it can see from the request/response envelope —
 * user, endpoint, the active policy tier + allow/deny, outcome, latency. Richer
 * per-call detail (cube, schema fingerprint, token counts) is <em>enrichment</em>
 * that resources opt into by setting the {@code P_*} request properties below;
 * the filter folds those in if present. A missing enrichment degrades an entry
 * gracefully — it never produces a missing entry, which is the whole point.
 *
 * <p>Never throws into the response path: an audit failure is logged, not
 * propagated (though {@link AiAuditLog} logs write failures loudly — they are a
 * compliance gap).
 */
@Provider
public class AiAuditFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger log = LoggerFactory.getLogger(AiAuditFilter.class);

    private static final String START_NANOS = "saiku.ai.audit.startNanos";

    /** Enrichment property keys — resources may set these on the request context
     *  ({@code requestContext.setProperty(...)}) and the filter folds them in. */
    public static final String P_CUBE = "saiku.ai.audit.cube";

    public static final String P_FINGERPRINT = "saiku.ai.audit.fingerprint";
    public static final String P_UPSTREAM = "saiku.ai.audit.upstream";
    public static final String P_MODEL = "saiku.ai.audit.model";
    public static final String P_PROMPT_TOKENS = "saiku.ai.audit.promptTokens";
    public static final String P_RESPONSE_TOKENS = "saiku.ai.audit.responseTokens";
    public static final String P_ERROR = "saiku.ai.audit.error";

    private AiAuditLog auditLog;
    private AiPolicyGuard policyGuard;

    public void setAuditLog(AiAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public void setPolicyGuard(AiPolicyGuard policyGuard) {
        this.policyGuard = policyGuard;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        // Stamp the start so the response side can compute latency.
        request.setProperty(START_NANOS, System.nanoTime());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        // Thin adapter: pull the primitives off the JAX-RS envelope, then defer
        // to the pure buildEntry() core (which is what the tests exercise).
        try {
            if (auditLog == null || !auditLog.isEnabled()) {
                return;
            }
            String path = "/" + request.getUriInfo().getPath();
            Long latencyMs = null;
            Object start = request.getProperty(START_NANOS);
            if (start instanceof Long) {
                latencyMs = (System.nanoTime() - (Long) start) / 1_000_000L;
            }
            java.util.Map<String, Object> enrichment = new java.util.HashMap<>();
            for (String k : ENRICH_KEYS) {
                Object v = request.getProperty(k);
                if (v != null) {
                    enrichment.put(k, v);
                }
            }
            AiAuditEntry e =
                    buildEntry(path, principalName(request), response.getStatus(), latencyMs, enrichment, policyGuard);
            if (e != null) {
                auditLog.record(e);
            }
        } catch (RuntimeException ex) {
            // Auditing must never break the response.
            log.warn("AI audit filter error (response still served): {}", ex.toString());
        }
    }

    /** Enrichment keys the response filter folds in if a resource set them. */
    private static final String[] ENRICH_KEYS = {
        P_CUBE, P_FINGERPRINT, P_UPSTREAM, P_MODEL, P_PROMPT_TOKENS, P_RESPONSE_TOKENS, P_ERROR
    };

    /**
     * Pure core: build the audit entry for a call, or {@code null} if the path
     * is not on the AI surface ({@code /ai/}). Extracted from the filter so it's
     * unit-testable without standing up JAX-RS contexts.
     */
    static AiAuditEntry buildEntry(
            String path,
            String user,
            int status,
            Long latencyMs,
            java.util.Map<String, Object> enrichment,
            AiPolicyGuard policyGuard) {
        // Only audit the AI surface. The admin read endpoint is at
        // /saiku/admin/ai-audit (no "/ai/" segment) so it isn't self-audited.
        if (path == null || !path.contains("/ai/")) {
            return null;
        }
        AiAuditEntry e = new AiAuditEntry();
        e.endpoint = path;
        e.user = (user == null || user.isBlank()) ? "anonymous" : user;
        e.outcome = outcomeFor(status);
        // A 403 on the AI surface is an AiPolicyViolation (the only 403 path here,
        // via AiPolicyViolationMapper) — i.e. the policy DENIED the call.
        e.policyDecision = status == 403 ? AiAuditEntry.DECISION_DENY : AiAuditEntry.DECISION_ALLOW;
        if (policyGuard != null && policyGuard.current() != null) {
            e.policy = policyGuard.current().displayName();
        }
        e.latencyMs = latencyMs;
        if (enrichment != null) {
            e.cube = str(enrichment.get(P_CUBE));
            e.schemaFingerprint = str(enrichment.get(P_FINGERPRINT));
            e.upstream = str(enrichment.get(P_UPSTREAM));
            e.model = str(enrichment.get(P_MODEL));
            e.promptTokens = intOrNull(enrichment.get(P_PROMPT_TOKENS));
            e.responseTokens = intOrNull(enrichment.get(P_RESPONSE_TOKENS));
        }
        if (!AiAuditEntry.OUTCOME_SUCCESS.equals(e.outcome)) {
            // Short, leak-safe error token: a resource-supplied error class if it
            // enriched one, else the HTTP status. Never the message.
            String enriched = enrichment == null ? null : str(enrichment.get(P_ERROR));
            e.error = enriched != null ? enriched : ("HTTP " + status);
        }
        return e;
    }

    private static String principalName(ContainerRequestContext request) {
        if (request.getSecurityContext() == null) {
            return "anonymous";
        }
        Principal p = request.getSecurityContext().getUserPrincipal();
        return p == null ? "anonymous" : p.getName();
    }

    static String outcomeFor(int status) {
        if (status >= 200 && status < 300) {
            return AiAuditEntry.OUTCOME_SUCCESS;
        }
        if (status == 400) {
            return AiAuditEntry.OUTCOME_VALIDATION_ERROR;
        }
        if (status == 403) {
            return AiAuditEntry.OUTCOME_DENIED;
        }
        return AiAuditEntry.OUTCOME_ERROR; // 5xx + anything else unexpected
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Integer) {
            return (Integer) o;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.valueOf(((String) o).trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
