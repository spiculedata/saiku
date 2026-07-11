/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.saiku.service.olap.ai.eval.EvalOutcome.Mismatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an {@link EvalSuite} through an {@link EvalAskAdapter} and returns a per-case
 * {@link EvalReport} (saiku#1424).
 *
 * <p>The runner is intentionally passive on scheduling — cases execute sequentially in the order
 * the suite defines them. Parallel execution across cases is deferred (it'd invalidate the
 * history-threaded turn model and complicate rate-limit accounting) but any case that itself
 * takes minutes can be moved to its own smaller suite so a failure in one doesn't block the
 * others.
 *
 * <p>Threading model: safe to call concurrently across suites (the runner is stateless). Not
 * safe to invoke twice against the same suite in parallel — the {@link EvalAskAdapter} contract
 * doesn't require thread-safety.
 */
public final class AgentEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentEvalRunner.class);

    private final EvalAskAdapter adapter;

    public AgentEvalRunner(EvalAskAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    /**
     * Execute every case in {@code suite} and return the aggregated report.
     *
     * @param suite the ground-truth cases
     * @throws NullPointerException if {@code suite} is null
     */
    public EvalReport run(EvalSuite suite) {
        Objects.requireNonNull(suite, "suite");
        long suiteStart = nowMillis();
        List<EvalOutcome> outcomes = new ArrayList<>(suite.cases().size());
        for (EvalCase c : suite.cases()) {
            outcomes.add(runOneCase(suite, c));
        }
        long total = nowMillis() - suiteStart;
        EvalReport report = new EvalReport(suite.name(), suite.description(), outcomes, total);
        log.info("Eval suite '{}' finished: {}", suite.name(), report.oneLine());
        return report;
    }

    private EvalOutcome runOneCase(EvalSuite suite, EvalCase c) {
        long start = nowMillis();
        EvalAskResult result;
        try {
            result = adapter.ask(suite.cube(), c.question(), c.history());
        } catch (RuntimeException e) {
            log.warn(
                    "Case '{}' raised {} — treating as degraded",
                    c.name(),
                    e.getClass().getSimpleName(),
                    e);
            return EvalOutcome.degraded(
                    c.name(), nowMillis() - start, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
        long durationMs = nowMillis() - start;

        if (result == null) {
            return EvalOutcome.degraded(c.name(), durationMs, "adapter returned null", null);
        }
        if (result.degraded()) {
            return EvalOutcome.degraded(c.name(), durationMs, result.degradedReason(), result.model());
        }

        List<Mismatch> mismatches = new ArrayList<>();

        // 1. Intent match (cheap check, runs first).
        if (c.expectedIntent() != null) {
            String expected = c.expectedIntent().toUpperCase(Locale.ROOT);
            String actual = result.intent() == null ? "" : result.intent().toUpperCase(Locale.ROOT);
            if (!expected.equals(actual)) {
                mismatches.add(new Mismatch("intent", "expected " + expected + ", got " + actual));
                // Bail out of the deeper checks — comparing REFUSED-shape expectations against a
                // QUERY-shape actual produces confusing cascade failures. Report the intent
                // mismatch and stop.
                return EvalOutcome.fail(c.name(), durationMs, result.intent(), result.model(), mismatches);
            }
        }

        // 2. Refusal reason substring match.
        if (c.expectedRefusalContains() != null) {
            String reason = result.refusalReason() == null ? "" : result.refusalReason();
            if (!reason.contains(c.expectedRefusalContains())) {
                mismatches.add(new Mismatch(
                        "refusalReason",
                        "expected reason to contain \"" + c.expectedRefusalContains() + "\", got \"" + reason + "\""));
            }
        }

        // 3. Rows comparison for QUERY intent.
        if (c.expectedRows() != null && !c.expectedRows().isEmpty()) {
            EvalTolerance tol = c.tolerance() == null ? EvalTolerance.EXACT : c.tolerance();
            mismatches.addAll(RowComparator.compare(c.expectedRows(), result.rows(), tol, c.orderMatters()));
        }

        // 4. Insight substring matches.
        if (c.expectedInsightContains() != null && !c.expectedInsightContains().isEmpty()) {
            String markdown = result.insightMarkdown() == null ? "" : result.insightMarkdown();
            for (String needle : c.expectedInsightContains()) {
                if (!markdown.contains(needle)) {
                    mismatches.add(new Mismatch("insight.markdown", "expected markdown to contain \"" + needle + "\""));
                }
            }
        }

        if (mismatches.isEmpty()) {
            return EvalOutcome.pass(c.name(), durationMs, result.intent(), result.model());
        }
        return EvalOutcome.fail(c.name(), durationMs, result.intent(), result.model(), mismatches);
    }

    /**
     * Package-private clock hook — overridden in tests so timings are deterministic. Production
     * always uses {@link System#currentTimeMillis}.
     */
    long nowMillis() {
        return System.currentTimeMillis();
    }
}
