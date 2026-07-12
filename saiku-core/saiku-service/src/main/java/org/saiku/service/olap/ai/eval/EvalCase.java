/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * One ground-truth case in an {@link EvalSuite} (saiku#1424).
 *
 * <p>A case pairs a natural-language question with a deterministic expectation. Expectations come
 * in four flavours, checked in order of specificity:
 *
 * <ol>
 *   <li>{@link #expectedIntent()} — the ask must be routed to a specific intent
 *       (QUERY / INSIGHT / VIEW_CHANGE / REFUSED). Cheap; runs first.
 *   <li>{@link #expectedRefusalContains()} — for REFUSED cases, the refusal reason must contain
 *       this substring. Guards against the model refusing for the wrong reason.
 *   <li>{@link #expectedRows()} — for QUERY cases, the executed result rows must match. The
 *       comparator applies {@link #tolerance()} and {@link #orderMatters()}.
 *   <li>{@link #expectedInsightContains()} — for INSIGHT cases, the markdown must contain each
 *       string. Substring match rather than exact — an LLM producing "Store Sales up 12%" should
 *       pass a case expecting {@code ["Store Sales", "up"]} without brittle exact-string
 *       matching.
 * </ol>
 *
 * <p>All expectation fields are optional; a case with none of them set only asserts that the
 * response wasn't degraded.
 *
 * @param name display name — used in the report. Should be unique within the suite.
 * @param question the natural-language ask. Fed to {@code POST /ai/ask} verbatim.
 * @param history prior turns to seed the ask with. Empty for single-shot cases.
 * @param expectedIntent required intent (case-insensitive). Null skips the check.
 * @param expectedRefusalContains substring the refusal reason must contain. Only meaningful with
 *     {@code expectedIntent == "REFUSED"}. Null skips the check.
 * @param expectedRows expected result rows for QUERY cases. Each row is a map from column key to
 *     expected value (numeric or string). Null skips the check. Frozen literals — correct only for
 *     a static snapshot of the cube. Use {@link #referenceQuery()} instead for live/evolving data.
 * @param referenceQuery a trusted "known-good" query whose live result-set becomes the ground
 *     truth for a QUERY case — the drift-proof alternative to {@link #expectedRows()}. The runner
 *     executes it against the same live cube at the same moment as the NL-generated query and
 *     diffs the two result-sets, so the case stays valid as the underlying data changes (a
 *     customer's warehouse, not a frozen demo). Takes precedence over {@code expectedRows} when
 *     both are set. Null skips reference-query comparison.
 * @param orderMatters if true (default), rows must match in order. If false, rows are sorted by
 *     their keys before diff — useful when the order isn't semantically meaningful.
 * @param expectedInsightContains list of substrings that must appear in the insight markdown.
 *     Null / empty skips the check.
 * @param tolerance numeric tolerance for row comparisons. Applied per-cell to any expected value
 *     that parses as a number. Null uses the {@link EvalTolerance#EXACT} default (exact match).
 */
public record EvalCase(
        String name,
        String question,
        List<Map<String, String>> history,
        String expectedIntent,
        String expectedRefusalContains,
        List<Map<String, Object>> expectedRows,
        AiQueryRequest referenceQuery,
        boolean orderMatters,
        List<String> expectedInsightContains,
        EvalTolerance tolerance) {

    public EvalCase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(question, "question");
        if (name.isBlank()) {
            throw new IllegalArgumentException("case name must be non-blank");
        }
        if (question.isBlank()) {
            throw new IllegalArgumentException("case question must be non-blank");
        }
        history = history == null ? List.of() : List.copyOf(history);
        expectedRows = expectedRows == null ? null : List.copyOf(expectedRows);
        expectedInsightContains = expectedInsightContains == null ? null : List.copyOf(expectedInsightContains);
    }

    /** Convenience — checks whether any expectation is set. Zero-expectation cases only assert the ask wasn't degraded. */
    public boolean hasExpectations() {
        return expectedIntent != null
                || expectedRefusalContains != null
                || (expectedRows != null && !expectedRows.isEmpty())
                || referenceQuery != null
                || (expectedInsightContains != null && !expectedInsightContains.isEmpty());
    }
}
