/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.saiku.service.olap.ai.eval.EvalOutcome.Mismatch;

/**
 * Deterministic row-set diff for QUERY-intent eval cases (saiku#1424).
 *
 * <p>Cube's Evals is billed as "deterministic result-set diff (no LLM-as-judge)" for a reason:
 * LLM-as-judge is expensive, non-reproducible, and lets subtle wrong answers slip through. This
 * comparator does the same job the hard way — direct value comparison with numeric tolerance and
 * explicit key normalisation.
 *
 * <p>Comparison rules:
 *
 * <ul>
 *   <li><b>Key normalisation.</b> Column keys are compared case-insensitively with whitespace
 *       collapsed. {@code "Store Sales"}, {@code "storeSales"}, and {@code "STORE_SALES"} all map
 *       to the same normalised key. Prevents a rename in the schema-projector's output shape
 *       from failing every eval.
 *   <li><b>Order handling.</b> When {@link EvalCase#orderMatters()} is true, rows compare
 *       positionally. When false, both sides sort by a stable serialisation of their normalised
 *       keys before diffing.
 *   <li><b>Numeric tolerance.</b> Any expected value that parses as a number is compared via
 *       {@link EvalTolerance#isWithin(double, double)}. Non-numeric values compare
 *       string-equality-after-normalisation.
 *   <li><b>Missing keys.</b> An expected key not present in the actual row is a mismatch. An
 *       actual key not expected is NOT — expectations are additive so the eval doesn't break when
 *       the schema grows a new column.
 * </ul>
 *
 * <p>Every divergence surfaces as an {@link Mismatch} with a precise structural path
 * ({@code "rows[2].storeSales"}) so the report reader knows exactly where to look.
 */
public final class RowComparator {

    private RowComparator() {}

    /**
     * Diff expected against actual. Returns an empty list on match, one or more mismatches
     * otherwise.
     *
     * @param expected the case's expected rows. Null / empty means no row expectation set — the
     *     comparator returns empty immediately.
     * @param actual rows returned by the ask surface. Null is treated as empty.
     * @param tolerance numeric tolerance. Use {@link EvalTolerance#EXACT} for exact match.
     * @param orderMatters when true, rows compare positionally; when false, both sides are sorted
     *     by normalised keys before diff.
     */
    public static List<Mismatch> compare(
            List<Map<String, Object>> expected,
            List<Map<String, Object>> actual,
            EvalTolerance tolerance,
            boolean orderMatters) {
        List<Mismatch> mismatches = new ArrayList<>();
        if (expected == null || expected.isEmpty()) {
            return mismatches;
        }
        List<Map<String, Object>> actualList = actual == null ? List.of() : actual;

        // Row-count parity — an eval that expects 3 rows and gets 5 is a bug even if the first
        // 3 match. Surface both sizes so the report is self-descriptive.
        if (expected.size() != actualList.size()) {
            mismatches.add(new Mismatch("rows", "expected " + expected.size() + " row(s), got " + actualList.size()));
            // Continue comparing to surface which rows differ — better report than "size mismatch,
            // giving up".
        }

        // Expected side is iterated with its ORIGINAL keys (so mismatch paths read as the operator
        // wrote them in the YAML — `rows[2].storeSales`, not `rows[2].storesales`). Actual side is
        // normalised for the lookup so `Store Sales` on the wire matches `storeSales` in the YAML.
        List<Map<String, Object>> orderedExpected = orderMatters ? expected : sortStable(expected);
        List<Map<String, Object>> normalisedActual =
                actualList.stream().map(RowComparator::normaliseKeys).toList();
        if (!orderMatters) {
            normalisedActual = sortStable(normalisedActual);
        }

        int commonSize = Math.min(orderedExpected.size(), normalisedActual.size());
        for (int i = 0; i < commonSize; i++) {
            compareRow(i, orderedExpected.get(i), normalisedActual.get(i), tolerance, mismatches);
        }
        return mismatches;
    }

    private static void compareRow(
            int index,
            Map<String, Object> expected,
            Map<String, Object> actualNormalised,
            EvalTolerance tolerance,
            List<Mismatch> out) {
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            String originalKey = e.getKey();
            String normalisedKey = normaliseKey(originalKey);
            Object expectedValue = e.getValue();
            if (!actualNormalised.containsKey(normalisedKey)) {
                out.add(new Mismatch("rows[" + index + "]." + originalKey, "expected key not present in actual row"));
                continue;
            }
            Object actualValue = actualNormalised.get(normalisedKey);
            Double expectedNumber = asNumber(expectedValue);
            if (expectedNumber != null) {
                Double actualNumber = asNumber(actualValue);
                if (actualNumber == null) {
                    out.add(new Mismatch(
                            "rows[" + index + "]." + originalKey,
                            "expected numeric " + expectedNumber + " but actual is not a number: " + actualValue));
                    continue;
                }
                if (!tolerance.isWithin(expectedNumber, actualNumber)) {
                    out.add(new Mismatch(
                            "rows[" + index + "]." + originalKey,
                            "expected " + expectedNumber + " ± tol(" + tolerance.absolute() + "," + tolerance.relative()
                                    + "), got " + actualNumber));
                }
            } else {
                // Non-numeric expected — string-normalise both sides for comparison.
                String expectedStr = normaliseString(expectedValue);
                String actualStr = normaliseString(actualValue);
                if (!expectedStr.equals(actualStr)) {
                    out.add(new Mismatch(
                            "rows[" + index + "]." + originalKey,
                            "expected \"" + expectedStr + "\", got \"" + actualStr + "\""));
                }
            }
        }
    }

    /**
     * Normalise a column key: lower-case, strip whitespace + underscores + hyphens. Preserves
     * insertion order via {@link LinkedHashMap} so per-row iteration matches the report.
     */
    private static Map<String, Object> normaliseKeys(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row.size());
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(normaliseKey(e.getKey()), e.getValue());
        }
        return out;
    }

    static String normaliseKey(String key) {
        if (key == null) return "";
        StringBuilder b = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isWhitespace(c) || c == '_' || c == '-') continue;
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    private static String normaliseString(Object v) {
        if (v == null) return "";
        return v.toString().trim();
    }

    /**
     * Parse {@code v} as a double. Returns null for non-numeric values or unparseable strings.
     * Handles {@link Number}, boxed primitives, and String representations
     * ({@code "123"}, {@code "$1,234.56"}, {@code "-1.5e3"}). Currency prefixes and thousands
     * separators are stripped before parsing so eval authors can write {@code "$565,238.13"} and
     * have it compare cleanly against an unformatted number.
     */
    static Double asNumber(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        // Strip common decoration.
        String cleaned = s.replaceAll("[\\$£€¥,]", "").trim();
        // Handle parenthesised negatives: "(500)" -> -500.
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            cleaned = "-" + cleaned.substring(1, cleaned.length() - 1);
        }
        // Handle trailing % — treat as-is (an expected "12%" compares to a "12" numerically).
        // Callers who want "0.12 vs 12%" semantics can use tolerance.
        if (cleaned.endsWith("%")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sort rows by a stable serialisation of their normalised keys. Ties break on insertion
     * order via {@link Comparator#thenComparing}. Only used when {@code orderMatters = false}.
     */
    private static List<Map<String, Object>> sortStable(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(RowComparator::rowKey));
        return copy;
    }

    /** Serialise a row into a stable key for sorting. Uses TreeMap iteration order so equal rows produce equal keys. */
    private static String rowKey(Map<String, Object> row) {
        StringBuilder b = new StringBuilder();
        // TreeMap for deterministic iteration order regardless of the source map's ordering.
        for (Map.Entry<String, Object> e : new TreeMap<>(row).entrySet()) {
            b.append(e.getKey())
                    .append('=')
                    .append(String.valueOf(e.getValue()).toLowerCase(Locale.ROOT))
                    .append('|');
        }
        return b.toString();
    }
}
