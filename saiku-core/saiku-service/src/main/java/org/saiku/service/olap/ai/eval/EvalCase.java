/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 *     expected value (numeric or string). Null skips the check.
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
                || (expectedInsightContains != null && !expectedInsightContains.isEmpty());
    }
}
