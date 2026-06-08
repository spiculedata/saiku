/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic Z-score (standard-score) anomaly detector. A point is flagged when
 * {@code |x - mean| / stddev > threshold} (default {@value #DEFAULT_THRESHOLD}).
 *
 * <p>Uses the population standard deviation (divide by N) computed over the
 * non-NaN points. This is sensitive to large outliers in the series itself —
 * for a robust alternative see {@link MadAnomalyDetector}.
 *
 * <p>Edge cases:
 * <ul>
 *   <li>A flat series (stddev == 0) yields zero scores and no anomalies.</li>
 *   <li>Fewer than two finite points yields no anomalies (stddev undefined).</li>
 *   <li>NaN slots emit a non-anomalous zero-score point so the output stays
 *       positionally aligned with the input.</li>
 * </ul>
 */
public class ZScoreAnomalyDetector implements AnomalyDetector {

    public static final String METHOD = "zscore";
    public static final double DEFAULT_THRESHOLD = 3.0;

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

        int count = 0;
        double sum = 0.0;
        for (double v : series) {
            if (!Double.isNaN(v)) {
                sum += v;
                count++;
            }
        }
        double mean = count > 0 ? sum / count : 0.0;

        double sqSum = 0.0;
        for (double v : series) {
            if (!Double.isNaN(v)) {
                double d = v - mean;
                sqSum += d * d;
            }
        }
        double stddev = count > 1 ? Math.sqrt(sqSum / count) : 0.0;

        for (double v : series) {
            if (Double.isNaN(v)) {
                out.add(new AnomalyPoint(0.0, mean, null, false));
                continue;
            }
            if (stddev == 0.0 || count < 2) {
                out.add(new AnomalyPoint(0.0, mean, null, false));
                continue;
            }
            double score = Math.abs(v - mean) / stddev;
            boolean anomaly = score > threshold;
            String dir = anomaly ? AnomalyPoint.direction(v, mean) : null;
            out.add(new AnomalyPoint(score, mean, dir, anomaly));
        }
        return out;
    }
}
