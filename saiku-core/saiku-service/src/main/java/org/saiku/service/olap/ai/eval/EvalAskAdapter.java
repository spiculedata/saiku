/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * SPI the eval runner uses to call an ask surface (saiku#1424).
 *
 * <p>Deliberately narrow: the runner passes in a cube ref + question + history + tolerance-hint
 * and gets an {@link EvalAskResult} back. Adapters translate their own configured ask flow into
 * that neutral shape:
 *
 * <ul>
 *   <li><b>Live adapter</b> — wraps {@code AiAskService.ask} + query execution. Used by CI runs
 *       against a real LLM. Requires a launcher (or in-process bean wiring) with a configured
 *       provider.
 *   <li><b>Fixture adapter</b> — returns canned {@link EvalAskResult}s from disk. Used by
 *       runner unit tests and by CI runs that want deterministic regression checks without
 *       spending LLM budget.
 *   <li><b>Recording adapter</b> — wraps a live adapter and writes each response to disk, so the
 *       fixture adapter has something to replay from later.
 * </ul>
 *
 * <p>Adapters are single-shot per case — the runner invokes {@link #ask} once per case in order.
 * Implementations are free to be stateful (a recording adapter accumulates its output between
 * calls) as long as they're safe to call sequentially.
 */
@FunctionalInterface
public interface EvalAskAdapter {

    /**
     * Execute one case's ask.
     *
     * @param cube the suite's cube ref
     * @param question the case's natural-language question
     * @param history prior turns from the case (empty for single-shot cases)
     * @return the ask outcome projected into the neutral {@link EvalAskResult}. Never null;
     *     adapters that hit a hard error return {@link EvalAskResult#forDegraded(String, String)}.
     */
    EvalAskResult ask(AiCubeRef cube, String question, List<Map<String, String>> history);

    /**
     * Execute a case's trusted {@code referenceQuery} against the live cube and return its rows as
     * the ground truth for the row comparison (saiku#1424 — drift-proof evals). This is what lets
     * a suite run against a customer's evolving data: the reference query and the NL-generated
     * query hit the same data at the same moment, so the diff is meaningful even as the numbers
     * change day to day.
     *
     * <p>Default implementation throws — only the live adapter can execute against a real cube; a
     * fixture/replay adapter that has no executor should either override this with recorded rows or
     * only be used with {@code expectedRows}-style cases.
     *
     * @param cube the suite's cube ref (used when the reference query doesn't pin its own)
     * @param referenceQuery the trusted query to execute
     * @return the reference result-set flattened to records, same shape as {@link #ask}'s query rows
     * @throws UnsupportedOperationException if this adapter can't execute live queries
     */
    default List<Map<String, Object>> executeReference(AiCubeRef cube, AiQueryRequest referenceQuery) {
        throw new UnsupportedOperationException(
                "this EvalAskAdapter cannot execute reference queries; use expectedRows or the live adapter");
    }
}
