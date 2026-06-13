/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.List;

/**
 * Specialisation of {@link AiValidationException} for the saiku#902 PII gate.
 *
 * <p>Thrown by {@link AiReturnsResolver} when a drillthrough {@code returns=}
 * token resolves to a measure or level whose schema annotation marks it
 * {@code saiku.semantic.pii=true}. The caller (drillthrough controller) catches
 * {@code AiValidationException} as today; the dedicated subclass exists so
 * tests and downstream tooling can identify the PII refusal without parsing
 * the message string.
 *
 * <p>The error message itself is deliberately analyst-friendly: it names the
 * offending column and the policy reason, so a CISO reading the audit log can
 * confirm the gate fired correctly. The candidate list passed to the parent
 * carries the NON-PII columns the agent could legitimately drill into — same
 * self-correction posture as the standard "unknown column" error.
 */
public class AiPiiException extends AiValidationException {

    private static final long serialVersionUID = 1L;

    public AiPiiException(String field, String message, List<String> available) {
        super(field, message, available);
    }
}
