/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Median Absolute Deviation (MAD) anomaly detector — a robust alternative to
 * {@link ZScoreAnomalyDetector}. A point is flagged when
 * {@code |x - median| / (1.4826 * MAD) > threshold} (default
 * {@value #DEFAULT_THRESHOLD}).
 *
 * <p>MAD is the median of the absolute deviations from the series median. The
 * {@value #SCALE} constant makes the scaled MAD a consistent estimator of the
 * standard deviation for normally-distributed data, so the threshold reads in
 * the same "number of sigmas" units as Z-score. Because it relies on medians
 * rather than means, a single large training outlier barely moves the central
 * estimate — the spike is flagged without inflating the spread the way a
 * mean/stddev pair would (which is the whole point of the robust variant).
 *
 * <p>Edge cases mirror {@link ZScoreAnomalyDetector}: a flat series (MAD == 0)
 * yields no anomalies, NaN slots emit a non-anomalous zero-score point, and a
 * series with no finite points yields no anomalies.
 */
public class MadAnomalyDetector implements AnomalyDetector {

    public static final String METHOD = "mad";
    public static final double DEFAULT_THRESHOLD = 3.5;

    /** Consistency constant: 1 / Φ⁻¹(0.75). Scales MAD to estimate σ for normal data. */
    private static final double SCALE = 1.4826;

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public double defaultThreshold() {
        return DEFAULT_THRESHOLD;
    }

    @Override
    public List<AnomalyPoint> detect(double[] series, double threshold) {
        List<AnomalyPoint> out = new ArrayList<>(series == null ? 0 : series.length);
        if (series == null || series.length == 0) {
            return out;
        }

        double[] finite = Arrays.stream(series).filter(v -> !Double.isNaN(v)).toArray();
        double median = median(finite);

        double[] absDev = new double[finite.length];
        for (int i = 0; i < finite.length; i++) {
            absDev[i] = Math.abs(finite[i] - median);
        }
        double mad = median(absDev);
        double scaledMad = SCALE * mad;

        for (double v : series) {
            if (Double.isNaN(v)) {
                out.add(new AnomalyPoint(0.0, median, null, false));
                continue;
            }
            if (scaledMad == 0.0 || finite.length < 2) {
                out.add(new AnomalyPoint(0.0, median, null, false));
                continue;
            }
            double score = Math.abs(v - median) / scaledMad;
            boolean anomaly = score > threshold;
            String dir = anomaly ? AnomalyPoint.direction(v, median) : null;
            out.add(new AnomalyPoint(score, median, dir, anomaly));
        }
        return out;
    }

    /** Median of an array (sorts a copy). Returns 0 for an empty array. */
    static double median(double[] values) {
        if (values == null || values.length == 0) return 0.0;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
