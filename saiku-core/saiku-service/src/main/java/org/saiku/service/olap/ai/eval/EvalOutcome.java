/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.Collections;
import java.util.List;

/**
 * The result of running one {@link EvalCase} through the runner (saiku#1424).
 *
 * <p>Composed of a status enum (pass / fail / degraded / skipped) and a list of {@link Mismatch}es
 * describing every point where the actual outcome diverged from the case's expectations. The
 * report generator surfaces mismatches directly so an operator reading a failed CI can see
 * exactly which row / cell / intent broke without spelunking through logs.
 *
 * @param caseName mirror of the source case's name
 * @param status pass / fail / degraded / skipped
 * @param mismatches ordered list of specific expectation failures. Empty on {@link Status#PASS}.
 * @param durationMs wall-clock elapsed to run this case, for reporting
 * @param actualIntent intent returned by the ask surface, for reporting on failure ("expected
 *     QUERY, got REFUSED")
 * @param actualModel LLM model id that answered, for reporting
 */
public record EvalOutcome(
        String caseName,
        Status status,
        List<Mismatch> mismatches,
        long durationMs,
        String actualIntent,
        String actualModel) {

    public EvalOutcome {
        mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
    }

    public enum Status {
        /** Every expectation matched. */
        PASS,
        /** At least one expectation didn't match. */
        FAIL,
        /** The ask surface returned {@code degraded=true} (provider transport / parse / config error). */
        DEGRADED,
        /** The case was intentionally not run (dry-run mode, missing config, etc.). */
        SKIPPED
    }

    /**
     * A single divergence between expected and actual.
     *
     * @param path structural path to the mismatch — e.g. {@code "intent"}, {@code "rows[2].storeSales"},
     *     {@code "insight.markdown"}. Empty on top-level failures.
     * @param message human-readable one-liner describing the divergence.
     */
    public record Mismatch(String path, String message) {}

    /** Convenience: build a passing outcome. */
    public static EvalOutcome pass(String caseName, long durationMs, String intent, String model) {
        return new EvalOutcome(caseName, Status.PASS, Collections.emptyList(), durationMs, intent, model);
    }

    /** Convenience: build a failing outcome from a mismatch list. */
    public static EvalOutcome fail(
            String caseName, long durationMs, String intent, String model, List<Mismatch> mismatches) {
        return new EvalOutcome(caseName, Status.FAIL, mismatches, durationMs, intent, model);
    }

    /** Convenience: build a degraded outcome for provider failures. */
    public static EvalOutcome degraded(String caseName, long durationMs, String reason, String model) {
        return new EvalOutcome(
                caseName, Status.DEGRADED, List.of(new Mismatch("provider", reason)), durationMs, null, model);
    }
}
