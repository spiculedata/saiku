/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import java.util.List;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * STL (Seasonal-Trend decomposition using Loess) anomaly detector — STUB.
 *
 * <p>STL decomposes the series into seasonal + trend + remainder components
 * and flags points whose remainder is anomalous, which requires a known
 * seasonal period and a vetted Loess smoother. saiku#907 deliberately does
 * NOT pull a heavy/unvetted JVM dependency for this; until a clean in-tree
 * implementation lands, {@link #detect} throws a structured
 * {@link AiValidationException} so the REST layer returns the same
 * self-correcting 400 envelope agents already understand (with the
 * {@code method} field and the list of methods that ARE supported).
 *
 * <p>Z-score and MAD are fully implemented and cover the common
 * "spike against a roughly stationary series" case the issue's acceptance
 * tests exercise.
 */
public class StlAnomalyDetector implements AnomalyDetector {

    public static final String METHOD = "stl";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public double defaultThreshold() {
        return 3.0;
    }

    @Override
    public List<AnomalyPoint> detect(double[] series, double threshold) {
        throw new AiValidationException(
                "method",
                "STL (seasonal-trend) anomaly detection is not yet supported. "
                        + "Use 'zscore' or 'mad' for now.",
                List.of(ZScoreAnomalyDetector.METHOD, MadAnomalyDetector.METHOD));
    }
}
