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
import org.saiku.service.olap.ai.forecast.ForecastPoint;
import org.saiku.service.olap.ai.forecast.Forecaster;

/**
 * Attach forecast projections to an {@link OssieAiQueryResponse}. For each metric column, take
 * the historical series along {@code timeAxis} and project {@code horizon} future points using
 * the given forecaster. Historical rows are left untouched; the projection lands on a new
 * top-level {@code forecast} block keyed by metric name.
 *
 * <p>Not a straight fork of the MDX augmenter — the response shapes differ enough that a
 * side-by-side implementation is cleaner than trying to genericise.
 */
public final class OssieAiForecastAugmenter {

    private OssieAiForecastAugmenter() {}

    public static void augment(
            OssieAiQueryResponse resp, Forecaster forecaster, int horizon, double confidence, String timeAxis) {
        if (resp == null) return;
        List<Map<String, Object>> records = resp.getRecords();
        if (records == null || records.isEmpty() || horizon <= 0) return;

        if (timeAxis != null && !timeAxis.isBlank()) {
            records.sort(Comparator.comparing(r -> String.valueOf(r.getOrDefault(timeAxis, ""))));
        }

        List<String> metricKeys = new ArrayList<>();
        for (OssieAiQueryResponse.Column c : resp.getColumns()) {
            if ("metric".equals(c.getType())) metricKeys.add(c.getKey());
        }
        if (metricKeys.isEmpty()) return;

        Map<String, Object> forecastBlock = new LinkedHashMap<>();
        for (String key : metricKeys) {
            double[] series = new double[records.size()];
            int finite = 0;
            for (int i = 0; i < records.size(); i++) {
                Object raw = records.get(i).get(key);
                OssieAiQueryResponse.CellValue cv =
                        raw instanceof OssieAiQueryResponse.CellValue ? (OssieAiQueryResponse.CellValue) raw : null;
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

            List<ForecastPoint> points = forecaster.forecast(series, horizon, confidence);
            List<Map<String, Object>> outPoints = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                ForecastPoint p = points.get(i);
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("index", records.size() + i);
                point.put("value", p.getValue());
                point.put("lower", p.getLower());
                point.put("upper", p.getUpper());
                outPoints.add(point);
            }
            Map<String, Object> perMetric = new LinkedHashMap<>();
            perMetric.put("method", forecaster.method());
            perMetric.put("horizon", horizon);
            perMetric.put("confidence", confidence);
            perMetric.put("points", outPoints);
            forecastBlock.put(key, perMetric);
        }
        resp.setForecast(forecastBlock);
    }
}
