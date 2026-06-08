/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import java.util.List;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Prophet forecaster — STUB (saiku#908).
 *
 * <p>Prophet (change-points + holiday effects) has no clean pure-JVM port; the
 * issue lists it as a stretch goal behind a py4j / REST shim. We deliberately
 * do NOT add that out-of-process dependency now, so {@link #forecast} throws a
 * structured {@link AiValidationException} (self-correcting 400) until/unless a
 * vetted integration lands. Use {@code ets} for in-JVM forecasting.
 */
public class ProphetForecaster implements Forecaster {

    public static final String METHOD = "prophet";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<ForecastPoint> forecast(double[] series, int horizon, double confidence) {
        throw new AiValidationException(
                "method",
                "Prophet forecasting is not yet supported (no in-JVM implementation). Use 'ets' for now.",
                List.of(EtsForecaster.METHOD));
    }
}
