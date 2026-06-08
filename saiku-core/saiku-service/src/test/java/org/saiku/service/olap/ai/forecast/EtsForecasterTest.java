/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Locks the Holt's-linear ETS forecaster (saiku#908). */
public class EtsForecasterTest {

    private final EtsForecaster ets = new EtsForecaster();

    @Test
    public void capturesLinearTrend() {
        // Perfect line y = x: 1..10. Holt's should reproduce slope 1 and
        // continue 11, 12, 13 (errors are zero so the fit is exact).
        double[] s = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        List<ForecastPoint> f = ets.forecast(s, 3, 0.95);
        assertEquals(3, f.size());
        assertEquals(11.0, f.get(0).getValue(), 0.01);
        assertEquals(12.0, f.get(1).getValue(), 0.01);
        assertEquals(13.0, f.get(2).getValue(), 0.01);
    }

    @Test
    public void intervalWidensWithHorizon() {
        // Noisy upward series → sigma > 0, so the PI must widen with h.
        double[] s = {1, 2.1, 2.9, 4.2, 4.8, 6.1, 6.9, 8.2, 8.7, 10.3};
        List<ForecastPoint> f = ets.forecast(s, 5, 0.95);
        double w1 = f.get(0).getUpper() - f.get(0).getLower();
        double w5 = f.get(4).getUpper() - f.get(4).getLower();
        assertTrue("interval should be positive", w1 > 0);
        assertTrue("interval should widen with horizon (w5 > w1)", w5 > w1);
        // sqrt-of-h growth: w(5)/w(1) ≈ sqrt(5).
        assertEquals(Math.sqrt(5.0), w5 / w1, 0.001);
    }

    @Test
    public void flatSeries_forecastsFlat_withTinyInterval() {
        double[] s = {5, 5, 5, 5, 5, 5};
        List<ForecastPoint> f = ets.forecast(s, 3, 0.95);
        assertEquals(5.0, f.get(0).getValue(), 1e-9);
        assertEquals(0.0, f.get(0).getUpper() - f.get(0).getLower(), 1e-9);
    }

    @Test
    public void returnsExactlyHorizonPoints() {
        double[] s = {3, 6, 9, 12};
        assertEquals(1, ets.forecast(s, 1, 0.9).size());
        assertEquals(12, ets.forecast(s, 12, 0.9).size());
    }

    @Test
    public void singlePoint_isFlat() {
        double[] s = {7};
        List<ForecastPoint> f = ets.forecast(s, 2, 0.95);
        assertEquals(7.0, f.get(0).getValue(), 1e-9);
        assertEquals(7.0, f.get(1).getLower(), 1e-9);
    }

    @Test
    public void skipsNaNGaps() {
        // Gaps must not break the fit; trend still ~1.
        double[] s = {1, 2, Double.NaN, 4, 5, Double.NaN, 7, 8};
        List<ForecastPoint> f = ets.forecast(s, 1, 0.95);
        assertTrue("forecast should be finite", !Double.isNaN(f.get(0).getValue()));
        assertTrue("continues upward", f.get(0).getValue() > 8.0);
    }

    @Test
    public void higherConfidence_widerInterval() {
        double[] s = {1, 2.1, 2.9, 4.2, 4.8, 6.1, 6.9, 8.2};
        double w90 = width(ets.forecast(s, 1, 0.90).get(0));
        double w99 = width(ets.forecast(s, 1, 0.99).get(0));
        assertTrue("99% interval wider than 90%", w99 > w90);
    }

    @Test
    public void normalQuantile_matchesKnownZ() {
        assertEquals(1.6449, EtsForecaster.normalQuantile(0.95), 1e-3);
        assertEquals(1.9600, EtsForecaster.normalQuantile(0.975), 1e-3);
        assertEquals(2.5758, EtsForecaster.normalQuantile(0.995), 1e-3);
        assertEquals(0.0, EtsForecaster.normalQuantile(0.5), 1e-6);
    }

    private static double width(ForecastPoint p) {
        return p.getUpper() - p.getLower();
    }
}
