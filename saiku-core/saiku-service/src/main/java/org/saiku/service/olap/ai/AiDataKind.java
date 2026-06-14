/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * Classifies a piece of data the AI surface is about to expose, so the
 * {@link AiPolicyGuard} can decide whether the active {@link AiPolicy} permits
 * it (saiku#903). Each kind declares the minimum policy tier required to send
 * it across the trust boundary.
 */
public enum AiDataKind {
    /** Cube metadata: dim/hier/level names, measure captions. */
    SCHEMA_METADATA(AiPolicy.SCHEMA_ONLY),
    /** Sample member captions (already PII-filtered by saiku#902). */
    SAMPLE_MEMBERS(AiPolicy.SCHEMA_ONLY),
    /** Aggregated query result values — the typed {@code {value, formatted}}
     *  cells from {@code /ai/query} and its anomaly/forecast/ask siblings. */
    AGGREGATED_RESULT_VALUES(AiPolicy.AGGREGATED),
    /** Raw fact rows from drillthrough. */
    RAW_ROW_DATA(AiPolicy.FULL);

    private final AiPolicy minPolicy;

    AiDataKind(AiPolicy minPolicy) {
        this.minPolicy = minPolicy;
    }

    /** Lowest {@link AiPolicy} tier that permits sending this kind. */
    public AiPolicy minPolicy() {
        return minPolicy;
    }
}
