/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import java.util.List;

/**
 * Server-side statistical anomaly detection over a single numeric time
 * series (saiku#907). Implementations are deliberately dependency-free and
 * deterministic — NO LLM, NO external model, all computation in-JVM.
 *
 * <p>The contract is positional: {@link #detect(double[], double)} returns a
 * list aligned 1:1 with the input array, one {@link AnomalyPoint} per input
 * point (including non-anomalous points, whose {@code anomaly} flag is
 * {@code false}). Callers decide how to surface the result — the REST layer
 * attaches a point to a cell only where {@code anomaly} is true.
 *
 * <p>Null input cells (gaps in the series) are represented as
 * {@link Double#NaN} in the input array; detectors must skip NaN values when
 * computing their statistics and emit a non-anomalous, zero-score point for
 * those slots so positional alignment is preserved.
 */
public interface AnomalyDetector {

    /** Stable identifier matching the {@code method} request field. */
    String method();

    /**
     * Detect anomalies in {@code series} at the given {@code threshold}.
     *
     * @param series numeric points in time order; {@link Double#NaN} marks a gap
     * @param threshold method-specific cutoff (e.g. number of sigmas)
     * @return one {@link AnomalyPoint} per input point, positionally aligned
     */
    List<AnomalyPoint> detect(double[] series, double threshold);

    /** Reasonable default threshold for this method when the caller omits one. */
    double defaultThreshold();
}
