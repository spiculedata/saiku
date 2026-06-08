/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import java.util.List;

/**
 * Server-side statistical forecasting over a single numeric time series
 * (saiku#908). Implementations are deliberately dependency-free and
 * deterministic — NO LLM, NO external model, all computation in-JVM (mirrors
 * the {@code ai.anomaly} package).
 *
 * <p>The contract: {@link #forecast(double[], int, double)} returns exactly
 * {@code horizon} {@link ForecastPoint}s — the projected continuation of the
 * series, each with a prediction interval at {@code confidence}. Implementations
 * must skip {@link Double#NaN} gaps when fitting and must produce intervals that
 * widen (never shrink) as the horizon step increases.
 */
public interface Forecaster {

    /** Stable identifier matching the {@code method} request field. */
    String method();

    /**
     * Forecast {@code horizon} steps beyond {@code series}.
     *
     * @param series numeric points in time order; {@link Double#NaN} marks a gap
     * @param horizon number of future points to project (&gt;= 1)
     * @param confidence prediction-interval confidence in (0,1), e.g. 0.95
     * @return exactly {@code horizon} forecast points, in time order
     */
    List<ForecastPoint> forecast(double[] series, int horizon, double confidence);
}
