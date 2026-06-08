/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import java.util.ArrayList;
import java.util.List;

/**
 * Exponential smoothing with a linear trend (Holt's method) — the default
 * {@code ets} forecaster (saiku#908). Dependency-free, deterministic, in-JVM.
 *
 * <p>Holt's recurrences over the gap-stripped series:
 *
 * <pre>{@code
 *   level_t = a * x_t       + (1-a) * (level_{t-1} + trend_{t-1})
 *   trend_t = b * (level_t - level_{t-1}) + (1-b) * trend_{t-1}
 *   forecast(level_n, trend_n, h) = level_n + h * trend_n
 * }</pre>
 *
 * <p>The smoothing parameters {@code a} (alpha) and {@code b} (beta) are fit by
 * a coarse grid search that minimises the in-sample one-step SSE — good enough
 * for a dashboard forecast without an optimisation dependency. Prediction
 * intervals are {@code forecast +/- z * sigma * sqrt(h)} where {@code sigma} is
 * the one-step residual standard deviation and {@code z} the normal quantile
 * for the requested confidence; intervals therefore widen monotonically with
 * the horizon.
 *
 * <p>Seasonal Holt-Winters is a documented future extension (it needs a known
 * seasonal period); this implementation captures level + trend, which covers
 * the common "where is this measure heading" case.
 */
public class EtsForecaster implements Forecaster {

    public static final String METHOD = "ets";

    private static final double[] GRID = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<ForecastPoint> forecast(double[] series, int horizon, double confidence) {
        double[] x = stripGaps(series);
        double z = normalQuantile(0.5 + confidence / 2.0);
        List<ForecastPoint> out = new ArrayList<>(Math.max(0, horizon));

        if (x.length == 0) {
            // Nothing to fit — caller should have skipped, but be defensive.
            for (int h = 1; h <= horizon; h++) out.add(new ForecastPoint(Double.NaN, Double.NaN, Double.NaN));
            return out;
        }
        if (x.length == 1) {
            // A single observation: flat forecast, no spread information.
            for (int h = 1; h <= horizon; h++) out.add(new ForecastPoint(x[0], x[0], x[0]));
            return out;
        }

        double bestAlpha = 0.5;
        double bestBeta = 0.1;
        double bestSse = Double.POSITIVE_INFINITY;
        for (double a : GRID) {
            for (double b : GRID) {
                double sse = fitSse(x, a, b);
                if (sse < bestSse) {
                    bestSse = sse;
                    bestAlpha = a;
                    bestBeta = b;
                }
            }
        }

        // Re-run the best fit to recover the final level/trend + residual sigma.
        Fit fit = run(x, bestAlpha, bestBeta);
        double sigma = fit.residualStd();
        for (int h = 1; h <= horizon; h++) {
            double point = fit.level + h * fit.trend;
            double spread = z * sigma * Math.sqrt(h);
            out.add(new ForecastPoint(point, point - spread, point + spread));
        }
        return out;
    }

    /** One-step in-sample SSE for given smoothing params (for the grid search). */
    private static double fitSse(double[] x, double alpha, double beta) {
        return run(x, alpha, beta).sse;
    }

    private static Fit run(double[] x, double alpha, double beta) {
        double level = x[0];
        double trend = x[1] - x[0];
        double sse = 0.0;
        int errs = 0;
        for (int t = 1; t < x.length; t++) {
            double predicted = level + trend; // one-step-ahead forecast of x[t]
            double err = x[t] - predicted;
            sse += err * err;
            errs++;
            double prevLevel = level;
            level = alpha * x[t] + (1 - alpha) * (prevLevel + trend);
            trend = beta * (level - prevLevel) + (1 - beta) * trend;
        }
        double variance = errs > 0 ? sse / errs : 0.0;
        return new Fit(level, trend, sse, Math.sqrt(variance));
    }

    private record Fit(double level, double trend, double sse, double residualStdValue) {
        double residualStd() {
            return residualStdValue;
        }
    }

    /** Drop {@link Double#NaN} gaps, preserving order. */
    private static double[] stripGaps(double[] series) {
        if (series == null) return new double[0];
        double[] tmp = new double[series.length];
        int n = 0;
        for (double v : series) {
            if (!Double.isNaN(v)) tmp[n++] = v;
        }
        double[] out = new double[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /**
     * Inverse standard-normal CDF (probit) via Acklam's rational approximation
     * — |error| &lt; 1.15e-9, dependency-free. Used to turn a confidence level
     * into the prediction-interval z-multiplier.
     */
    static double normalQuantile(double p) {
        if (p <= 0) return Double.NEGATIVE_INFINITY;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        final double[] a = {
            -3.969683028665376e+01,
            2.209460984245205e+02,
            -2.759285104469687e+02,
            1.383577518672690e+02,
            -3.066479806614716e+01,
            2.506628277459239e+00
        };
        final double[] b = {
            -5.447609879822406e+01,
            1.615858368580409e+02,
            -1.556989798598866e+02,
            6.680131188771972e+01,
            -1.328068155288572e+01
        };
        final double[] c = {
            -7.784894002430293e-03,
            -3.223964580411365e-01,
            -2.400758277161838e+00,
            -2.549732539343734e+00,
            4.374664141464968e+00,
            2.938163982698783e+00
        };
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        final double plow = 0.02425;
        final double phigh = 1 - plow;
        double q;
        double r;
        if (p < plow) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p <= phigh) {
            q = p - 0.5;
            r = q * q;
            return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5])
                    * q
                    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        } else {
            q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
    }
}
