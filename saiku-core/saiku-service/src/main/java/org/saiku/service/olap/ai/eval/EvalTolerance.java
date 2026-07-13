/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

/**
 * Numeric tolerance for row-cell comparisons (saiku#1424).
 *
 * <p>Applied per-cell whenever the expected value parses as a number. Non-numeric expected values
 * (strings, member captions) are always exact-matched. Cases whose data is inherently noisy —
 * warehouse floating-point drift, sub-cent rounding differences — set a small relative tolerance
 * so the eval doesn't flag noise as regression.
 *
 * @param absolute maximum absolute difference. {@code |actual - expected| <= absolute} passes.
 *     Zero means exact match (subject to {@link #relative}).
 * @param relative maximum fractional difference. {@code |actual - expected| / |expected| <= relative}
 *     passes. Zero means exact match. Relative dominates absolute — a cell passes if EITHER
 *     tolerance is satisfied.
 */
public record EvalTolerance(double absolute, double relative) {

    /** Zero tolerance — expected must equal actual bit-for-bit. */
    public static final EvalTolerance EXACT = new EvalTolerance(0.0, 0.0);

    public EvalTolerance {
        if (absolute < 0.0 || Double.isNaN(absolute)) {
            throw new IllegalArgumentException("absolute tolerance must be >= 0 (got " + absolute + ")");
        }
        if (relative < 0.0 || Double.isNaN(relative)) {
            throw new IllegalArgumentException("relative tolerance must be >= 0 (got " + relative + ")");
        }
    }

    /**
     * Check whether {@code actual} is within tolerance of {@code expected}. Returns true for
     * exact-match on non-numeric-comparable values (both must be NaN or infinite the same way to
     * pass — a strict interpretation but tests won't produce those legitimately).
     */
    public boolean isWithin(double expected, double actual) {
        if (Double.isNaN(expected) && Double.isNaN(actual)) return true;
        if (Double.isNaN(expected) || Double.isNaN(actual)) return false;
        if (expected == actual) return true;
        double diff = Math.abs(actual - expected);
        if (absolute > 0.0 && diff <= absolute) return true;
        if (relative > 0.0 && expected != 0.0 && diff / Math.abs(expected) <= relative) return true;
        return false;
    }
}
