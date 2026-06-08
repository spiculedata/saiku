/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.AiQueryMetadata;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Runs a {@link AnomalyDetector} over the measure series of an already-executed
 * {@link AiQueryResponse} and writes the per-point verdicts back onto the
 * flagged {@link AiCell}s (saiku#907).
 *
 * <p>Time-series chart tiles lay the time members down the <em>rows</em> axis
 * (one row per time point, in order) and the measure(s) across the
 * <em>columns</em> axis. Each column is therefore one numeric series in time
 * order; we run the detector once per column and attach an {@link AnomalyPoint}
 * to every cell the detector flags ({@code anomaly == true}). Non-anomalous
 * cells are left untouched, so the wire payload stays lean.
 *
 * <p>Operates on the <strong>records</strong> format only — that is the shape
 * the dashboard chart tiles consume. The caller is responsible for building the
 * response in records format before augmenting.
 */
public final class AnomalyAugmenter {

    private AnomalyAugmenter() {}

    /**
     * Augment {@code resp} in place.
     *
     * @param resp the executed query response (records format)
     * @param detector the resolved detector
     * @param threshold cutoff (caller has already applied the default)
     * @param timeAxis the requested time-axis unique name (for validation only)
     * @throws AiValidationException if the response carries no usable numeric
     *     series along the time axis
     */
    public static void augment(AiQueryResponse resp, AnomalyDetector detector, double threshold, String timeAxis) {
        if (resp == null) return;
        List<Map<String, Object>> data = resp.getData();
        if (data == null || data.isEmpty()) {
            // Empty result set is not an error — there is simply nothing to flag.
            return;
        }

        AiQueryMetadata meta = resp.getMetadata();
        List<String> measureCols = measureColumnKeys(meta);
        if (measureCols.isEmpty()) {
            throw new AiValidationException(
                    "timeAxis",
                    "No numeric measure columns found in the result for time axis '" + timeAxis
                            + "'. Anomaly detection needs at least one measure plotted over time.",
                    null);
        }

        boolean attachedAny = false;
        for (String col : measureCols) {
            // Collect the column's series + remember which record each value came
            // from so we can write the verdict straight back onto the AiCell.
            double[] series = new double[data.size()];
            List<AiCell> cellRefs = new ArrayList<>(data.size());
            int finiteCount = 0;
            for (int r = 0; r < data.size(); r++) {
                Object raw = data.get(r).get(col);
                AiCell cell = (raw instanceof AiCell) ? (AiCell) raw : null;
                cellRefs.add(cell);
                Double v = cell == null ? null : cell.getValue();
                if (v == null || v.isNaN()) {
                    series[r] = Double.NaN;
                } else {
                    series[r] = v;
                    finiteCount++;
                }
            }
            if (finiteCount == 0) continue;

            List<AnomalyPoint> points = detector.detect(series, threshold);
            for (int r = 0; r < points.size() && r < cellRefs.size(); r++) {
                AnomalyPoint p = points.get(r);
                AiCell cell = cellRefs.get(r);
                if (p != null && p.isAnomaly() && cell != null) {
                    cell.setAnomaly(p);
                    attachedAny = true;
                }
            }
        }
        // attachedAny == false is a valid "no anomalies" outcome; nothing more to do.
        // (The REST layer reports the empty-anomalies case via the count field.)
        if (!attachedAny) {
            // no-op — left explicit for readers
            return;
        }
    }

    /**
     * Column keys that carry numeric measure cells. Prefers the metadata's
     * declared column captions (which key the records map for data columns);
     * falls back to scanning the first record for {@link AiCell}-valued keys
     * (row-header columns hold plain strings, so they're naturally excluded).
     */
    private static List<String> measureColumnKeys(AiQueryMetadata meta) {
        List<String> keys = new ArrayList<>();
        if (meta != null && meta.getColumns() != null) {
            for (AiQueryMetadata.Caption c : meta.getColumns()) {
                if (c != null && c.getCaption() != null && !c.getCaption().isEmpty()) {
                    keys.add(c.getCaption());
                }
            }
        }
        return keys;
    }

    /**
     * Count the anomaly cells attached to a response — used to populate the
     * endpoint's {@code anomalyCount} so "no anomalies" is an explicit zero,
     * never a missing field (per the issue's test requirement).
     */
    public static int countAnomalies(AiQueryResponse resp) {
        if (resp == null || resp.getData() == null) return 0;
        int n = 0;
        for (Map<String, Object> row : resp.getData()) {
            for (Object v : row.values()) {
                if (v instanceof AiCell && ((AiCell) v).getAnomaly() != null) n++;
            }
        }
        return n;
    }
}
