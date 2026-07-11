/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.List;
import java.util.Objects;

/**
 * The aggregated result of running an {@link EvalSuite} (saiku#1424).
 *
 * <p>Contains one {@link EvalOutcome} per case plus tallies for the CI summary line
 * ({@code 47/50 passed, 3 failed, 0 degraded}). Serialisable to JSON (for CI archive) via a
 * plain {@code ObjectMapper} — the fields are all records or primitives.
 *
 * @param suiteName mirror of the source suite's name
 * @param suiteDescription mirror of the source suite's description
 * @param outcomes ordered outcomes, one per case
 * @param totalDurationMs wall-clock elapsed across the whole run
 */
public record EvalReport(String suiteName, String suiteDescription, List<EvalOutcome> outcomes, long totalDurationMs) {

    public EvalReport {
        Objects.requireNonNull(suiteName, "suiteName");
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    /** Number of cases in the suite. */
    public int totalCases() {
        return outcomes.size();
    }

    public int passedCount() {
        return countByStatus(EvalOutcome.Status.PASS);
    }

    public int failedCount() {
        return countByStatus(EvalOutcome.Status.FAIL);
    }

    public int degradedCount() {
        return countByStatus(EvalOutcome.Status.DEGRADED);
    }

    public int skippedCount() {
        return countByStatus(EvalOutcome.Status.SKIPPED);
    }

    /** True when every case passed. Used by CI to decide the run's exit code. */
    public boolean allPassed() {
        return failedCount() == 0 && degradedCount() == 0;
    }

    /** Single-line CI summary: {@code "3/5 passed, 1 failed, 1 degraded, 0 skipped"}. */
    public String oneLine() {
        return passedCount() + "/" + totalCases() + " passed, " + failedCount() + " failed, " + degradedCount()
                + " degraded, " + skippedCount() + " skipped";
    }

    private int countByStatus(EvalOutcome.Status s) {
        int n = 0;
        for (EvalOutcome o : outcomes) {
            if (o.status() == s) n++;
        }
        return n;
    }
}
