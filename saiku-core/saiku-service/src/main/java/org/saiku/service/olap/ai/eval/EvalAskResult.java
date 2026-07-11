/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.List;
import java.util.Map;

/**
 * Neutral projection of an ask flow's outcome for the eval runner (saiku#1424). Adapters
 * translate their own response shapes into this record so the runner can compare against
 * expectations without knowing whether it's talking to {@code AiAskService}, a REST mock, or a
 * recorded fixture.
 *
 * <p>Exactly one of the intent-specific slots ({@code rows} for QUERY, {@code insightMarkdown}
 * for INSIGHT, {@code viewMode} for VIEW_CHANGE, {@code refusalReason} for REFUSED) is populated
 * on success; all are null on {@code degraded=true}.
 *
 * @param intent one of {@code "QUERY"}, {@code "INSIGHT"}, {@code "VIEW_CHANGE"}, {@code "REFUSED"},
 *     or {@code null} on degraded.
 * @param model the LLM model id that answered, for the report.
 * @param rows executed result rows for QUERY intent. Each row is a column-key → cell-value map.
 * @param insightMarkdown insight markdown for INSIGHT intent.
 * @param viewMode target view mode for VIEW_CHANGE intent.
 * @param refusalReason the model's refusal sentence for REFUSED intent.
 * @param degraded true if the ask failed at the provider layer.
 * @param degradedReason human-readable explanation when {@code degraded}.
 */
public record EvalAskResult(
        String intent,
        String model,
        List<Map<String, Object>> rows,
        String insightMarkdown,
        String viewMode,
        String refusalReason,
        boolean degraded,
        String degradedReason) {

    public EvalAskResult {
        rows = rows == null ? null : List.copyOf(rows);
    }

    public static EvalAskResult forQuery(String model, List<Map<String, Object>> rows) {
        return new EvalAskResult("QUERY", model, rows, null, null, null, false, null);
    }

    public static EvalAskResult forInsight(String model, String markdown) {
        return new EvalAskResult("INSIGHT", model, null, markdown, null, null, false, null);
    }

    public static EvalAskResult forViewChange(String model, String viewMode) {
        return new EvalAskResult("VIEW_CHANGE", model, null, null, viewMode, null, false, null);
    }

    public static EvalAskResult forRefusal(String model, String reason) {
        return new EvalAskResult("REFUSED", model, null, null, null, reason, false, null);
    }

    public static EvalAskResult forDegraded(String model, String reason) {
        return new EvalAskResult(null, model, null, null, null, null, true, reason);
    }
}
