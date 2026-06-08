/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import java.util.List;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * ARIMA(p,d,q) forecaster — STUB (saiku#908).
 *
 * <p>Auto-fitting ARIMA orders via AIC/BIC needs a vetted optimiser + linear
 * algebra (Levinson-Durbin / MLE), which we will not pull as a heavy/unvetted
 * JVM dependency right now. Until a clean in-tree implementation lands,
 * {@link #forecast} throws a structured {@link AiValidationException} so the
 * REST layer returns the same self-correcting 400 envelope agents already
 * understand — exactly as {@code StlAnomalyDetector} does in the sibling
 * {@code ai.anomaly} package. {@code ets} is fully implemented and covers the
 * common trend case.
 */
public class ArimaForecaster implements Forecaster {

    public static final String METHOD = "arima";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<ForecastPoint> forecast(double[] series, int horizon, double confidence) {
        throw new AiValidationException(
                "method",
                "ARIMA forecasting is not yet supported. Use 'ets' (exponential smoothing) for now.",
                List.of(EtsForecaster.METHOD));
    }
}
