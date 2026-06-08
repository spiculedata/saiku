/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.AiQueryMetadata;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Builds the forecast block for an already-executed {@link AiQueryResponse}
 * (records format) by extracting each measure series in time order and running
 * a {@link Forecaster} over it (saiku#908).
 *
 * <p>Unlike the sibling anomaly augmenter, forecasting does NOT mutate the
 * observed {@code data} — the future points live in a separate, keyed block
 * ({@code measureCaption -> [ForecastPoint x horizon]}) that the chart tile
 * appends as a dashed continuation with a confidence band. Time members are
 * expected down the rows axis (one row per point, in order) and measures across
 * the columns — the same layout the anomaly path assumes.
 */
public final class ForecastAssembler {

    private ForecastAssembler() {}

    /**
     * @return ordered map of measure caption -&gt; horizon forecast points.
     * @throws AiValidationException if the response carries no numeric measure
     *     series to forecast, or horizon/confidence are out of range
     */
    public static Map<String, List<ForecastPoint>> assemble(
            AiQueryResponse resp, Forecaster forecaster, int horizon, double confidence, String timeAxis) {
        if (horizon < 1) {
            throw new AiValidationException("horizon", "horizon must be >= 1", null);
        }
        if (confidence <= 0 || confidence >= 1 || Double.isNaN(confidence)) {
            throw new AiValidationException("confidence", "confidence must be between 0 and 1 (exclusive)", null);
        }
        Map<String, List<ForecastPoint>> out = new LinkedHashMap<>();
        if (resp == null) return out;
        List<Map<String, Object>> data = resp.getData();
        if (data == null || data.isEmpty()) {
            // Nothing observed → nothing to project. Not an error.
            return out;
        }

        List<String> measureCols = measureColumnKeys(resp.getMetadata());
        if (measureCols.isEmpty()) {
            throw new AiValidationException(
                    "timeAxis",
                    "No numeric measure columns found in the result for time axis '" + timeAxis
                            + "'. Forecasting needs at least one measure plotted over time.",
                    null);
        }

        for (String col : measureCols) {
            double[] series = new double[data.size()];
            int finite = 0;
            for (int r = 0; r < data.size(); r++) {
                Object raw = data.get(r).get(col);
                Double v = (raw instanceof AiCell) ? ((AiCell) raw).getValue() : null;
                if (v == null || v.isNaN()) {
                    series[r] = Double.NaN;
                } else {
                    series[r] = v;
                    finite++;
                }
            }
            // A column with no usable points can't be forecast — skip it (a
            // mixed result may still have other forecastable measures).
            if (finite == 0) continue;
            out.put(col, forecaster.forecast(series, horizon, confidence));
        }
        return out;
    }

    /** Measure-column keys, from the metadata's declared column captions
     *  (these key the records map for data columns). Mirrors AnomalyAugmenter. */
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
}
