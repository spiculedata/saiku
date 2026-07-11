/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;

/**
 * Formatters for an {@link EvalReport} (saiku#1424).
 *
 * <p>Two shapes:
 *
 * <ul>
 *   <li>{@link #toJson(EvalReport)} — pretty-printed JSON. What CI archives; what a downstream
 *       tool re-parses.
 *   <li>{@link #toText(EvalReport)} — human-readable multi-line summary. What operators eyeball
 *       when a run fails and they want to know which case broke without opening the JSON.
 * </ul>
 *
 * <p>Both are static utilities — the report itself carries all the information; the writer just
 * decides how to render it.
 */
public final class EvalReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReportWriter() {}

    /** Pretty-print the report as JSON. */
    public static String toJson(EvalReport report) {
        try {
            return JSON.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            // Records + primitives — should never fail. Fall back to the one-liner if it does.
            return "{\"error\":\"json write failed: " + e.getMessage() + "\", \"summary\":\"" + report.oneLine()
                    + "\"}";
        }
    }

    /**
     * Human-readable text summary. Format:
     *
     * <pre>{@code
     * Suite: foodmart-sales-evals
     * Description: Ground-truth cases for FoodMart Sales
     * 3/5 passed, 1 failed, 1 degraded, 0 skipped (elapsed 421ms)
     *
     * FAIL: top-3-product-families (128ms, intent=QUERY, model=claude-sonnet-4-6)
     *   rows[0].productFamily: expected "Food", got "Drink"
     *   rows[1].productFamily: expected "Non-Consumable", got "Food"
     *
     * DEGRADED: refuse-off-topic (12ms, model=null)
     *   provider: HTTP 503: upstream unavailable
     * }</pre>
     */
    public static String toText(EvalReport report) {
        StringBuilder b = new StringBuilder();
        b.append("Suite: ").append(report.suiteName()).append('\n');
        if (report.suiteDescription() != null && !report.suiteDescription().isBlank()) {
            b.append("Description: ").append(report.suiteDescription()).append('\n');
        }
        b.append(report.oneLine())
                .append(" (elapsed ")
                .append(report.totalDurationMs())
                .append("ms)\n\n");

        for (EvalOutcome o : report.outcomes()) {
            if (o.status() == EvalOutcome.Status.PASS) {
                // Successes get a single-line log — the CI summary handles the passing case
                // overview. Detailed failure lines below are what matter to the reader.
                b.append("PASS: ")
                        .append(o.caseName())
                        .append(" (")
                        .append(o.durationMs())
                        .append("ms, intent=")
                        .append(nullSafe(o.actualIntent()))
                        .append(", model=")
                        .append(nullSafe(o.actualModel()))
                        .append(")\n");
                continue;
            }
            b.append(o.status().name())
                    .append(": ")
                    .append(o.caseName())
                    .append(" (")
                    .append(o.durationMs())
                    .append("ms, intent=")
                    .append(nullSafe(o.actualIntent()))
                    .append(", model=")
                    .append(nullSafe(o.actualModel()))
                    .append(")\n");
            writeMismatches(b, o.mismatches());
        }
        return b.toString();
    }

    private static void writeMismatches(StringBuilder b, List<EvalOutcome.Mismatch> mismatches) {
        for (EvalOutcome.Mismatch m : mismatches) {
            b.append("  ").append(m.path()).append(": ").append(m.message()).append('\n');
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "null" : s;
    }
}
