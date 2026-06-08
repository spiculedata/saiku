/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

/**
 * One forecast horizon point produced by a {@link Forecaster} (saiku#908):
 * the point estimate plus the lower/upper bounds of the prediction interval
 * at the requested confidence level. {@code forecast} is always {@code true}
 * so the wire payload is self-describing alongside observed cells.
 */
public class ForecastPoint {

    private final double value;
    private final double lower;
    private final double upper;
    private final boolean forecast = true;

    public ForecastPoint(double value, double lower, double upper) {
        this.value = value;
        this.lower = lower;
        this.upper = upper;
    }

    public double getValue() {
        return value;
    }

    public double getLower() {
        return lower;
    }

    public double getUpper() {
        return upper;
    }

    public boolean isForecast() {
        return forecast;
    }
}
