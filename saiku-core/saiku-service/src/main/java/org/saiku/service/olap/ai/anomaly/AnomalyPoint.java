/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-point output of an {@link AnomalyDetector}. One of these is produced
 * for every numeric point fed to the detector; {@link #anomaly} flags whether
 * the point crossed the configured threshold.
 *
 * <p>{@link #score} is the (unsigned) deviation in detector-native units —
 * standard deviations for Z-score, scaled MADs for MAD. {@link #expected} is
 * the central tendency the point was compared against (mean for Z-score,
 * median for MAD). {@link #direction} is {@code "above"} / {@code "below"}
 * relative to {@code expected}, or {@code null} when the point sits exactly on
 * it (or no direction can be inferred).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnomalyPoint {

    private final double score;
    private final double expected;
    private final String direction;
    private final boolean anomaly;

    public AnomalyPoint(double score, double expected, String direction, boolean anomaly) {
        this.score = score;
        this.expected = expected;
        this.direction = direction;
        this.anomaly = anomaly;
    }

    public double getScore() {
        return score;
    }

    public double getExpected() {
        return expected;
    }

    public String getDirection() {
        return direction;
    }

    public boolean isAnomaly() {
        return anomaly;
    }

    /** Direction helper: {@code "above"}, {@code "below"}, or {@code null} on a tie. */
    static String direction(double x, double centre) {
        if (x > centre) return "above";
        if (x < centre) return "below";
        return null;
    }
}
