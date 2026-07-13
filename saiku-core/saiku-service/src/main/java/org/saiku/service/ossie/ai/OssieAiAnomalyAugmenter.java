/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.anomaly.AnomalyDetector;
import org.saiku.service.olap.ai.anomaly.AnomalyPoint;

/**
 * In-place augmentation of an {@link OssieAiQueryResponse} with anomaly flags. Ossie-side
 * counterpart of {@code AnomalyAugmenter} — same detector API, different result envelope.
 *
 * <p>Reads each numeric metric column into a series (NaN for null/missing), asks the detector
 * for verdicts, then writes an {@code anomaly:{score, expected, direction}} block onto every
 * metric CellValue whose point flagged as anomalous. Also writes a top-level {@code anomaly}
 * summary block with the method, threshold, and total count.
 */
public final class OssieAiAnomalyAugmenter {

    private OssieAiAnomalyAugmenter() {}

    public static void augment(OssieAiQueryResponse resp, AnomalyDetector detector, double threshold, String timeAxis) {
        if (resp == null) return;
        List<Map<String, Object>> records = resp.getRecords();
        if (records == null || records.isEmpty()) return;

        // Time-sort in-place so the detector sees a monotone series. If the caller already
        // ORDER BY'd the time column the sort is a no-op; sorting again is cheap.
        if (timeAxis != null && !timeAxis.isBlank()) {
            records.sort(Comparator.comparing(r -> String.valueOf(r.getOrDefault(timeAxis, ""))));
        }

        List<String> metricKeys = new ArrayList<>();
        for (OssieAiQueryResponse.Column c : resp.getColumns()) {
            if ("metric".equals(c.getType())) metricKeys.add(c.getKey());
        }
        if (metricKeys.isEmpty()) return;

        int totalAnomalies = 0;
        for (String key : metricKeys) {
            double[] series = new double[records.size()];
            List<OssieAiQueryResponse.CellValue> cells = new ArrayList<>(records.size());
            int finite = 0;
            for (int i = 0; i < records.size(); i++) {
                Object raw = records.get(i).get(key);
                OssieAiQueryResponse.CellValue cv =
                        raw instanceof OssieAiQueryResponse.CellValue ? (OssieAiQueryResponse.CellValue) raw : null;
                cells.add(cv);
                if (cv != null && cv.getValue() instanceof Number n) {
                    double v = n.doubleValue();
                    if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                        series[i] = v;
                        finite++;
                        continue;
                    }
                }
                series[i] = Double.NaN;
            }
            if (finite == 0) continue;

            List<AnomalyPoint> points = detector.detect(series, threshold);
            for (int i = 0; i < points.size() && i < cells.size(); i++) {
                AnomalyPoint p = points.get(i);
                OssieAiQueryResponse.CellValue cv = cells.get(i);
                if (p == null || !p.isAnomaly() || cv == null) continue;
                // Attach the anomaly verdict as a Map on the CellValue's raw value. We can't
                // change CellValue's shape without breaking the sync-response contract; use a
                // sidecar Map keyed with the same detector fields the MDX side publishes.
                Map<String, Object> annotated = new LinkedHashMap<>();
                annotated.put("value", cv.getValue());
                annotated.put("formatted", cv.getFormatted());
                if (cv.getUnit() != null) annotated.put("unit", cv.getUnit());
                Map<String, Object> anomaly = new LinkedHashMap<>();
                anomaly.put("score", p.getScore());
                anomaly.put("expected", p.getExpected());
                if (p.getDirection() != null) anomaly.put("direction", p.getDirection());
                annotated.put("anomaly", anomaly);
                records.get(i).put(key, annotated);
                totalAnomalies++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("method", detector.method());
        summary.put("threshold", threshold);
        summary.put("anomalyCount", totalAnomalies);
        summary.put("timeAxis", timeAxis);
        resp.getMeta().setSuppressed(resp.getMeta().getSuppressed()); // keep the object
        // We add anomaly to a distinct top-level slot via a Map — the response DTO's meta is
        // typed; a follow-up could add an explicit anomaly field. For R4 we stash it on the
        // meta reference by attaching a getter/setter — but the simpler path is a sidecar
        // returned by the resource.
        // Instead of extending Meta, hand the summary back by parking it as a suppression-like
        // record — no, that's confusing. Use a private map hook: expose via getAnomaly on
        // the resource-layer wrapping (see AiOssieResource.detectAnomalies).
        // Store the summary on the response object itself:
        resp.setAnomalySummary(summary);
    }
}
