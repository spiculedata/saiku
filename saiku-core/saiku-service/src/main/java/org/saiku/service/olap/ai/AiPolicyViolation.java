/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * Thrown by {@link AiPolicyGuard#assertCanSend} when the active
 * {@link AiPolicy} does not permit exposing a given {@link AiDataKind}
 * (saiku#903). Carries the offending kind + the active policy so the web layer
 * can return a clear 403 the caller can act on (raise the policy, or stop
 * asking for that tier of data). Lives in the service layer (no JAX-RS
 * dependency); {@code AiPolicyViolationMapper} translates it to HTTP 403.
 */
public class AiPolicyViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AiDataKind kind;
    private final AiPolicy current;

    public AiPolicyViolation(AiDataKind kind, AiPolicy current) {
        super("AI policy '" + current.displayName() + "' does not permit sending " + kind.name() + " (requires '"
                + kind.minPolicy().displayName() + "' or higher).");
        this.kind = kind;
        this.current = current;
    }

    public AiDataKind getKind() {
        return kind;
    }

    public AiPolicy getCurrent() {
        return current;
    }

    /** The minimum policy the operator would need to set to allow this. */
    public AiPolicy getRequired() {
        return kind.minPolicy();
    }
}
