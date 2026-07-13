/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.ratelimit;

import java.util.List;
import org.saiku.service.olap.ai.ask.AiAskApi;

/**
 * Size caps for the AI ask endpoint (saiku#1151). The question and prior
 * conversation turns are forwarded verbatim to a paid LLM provider, so an
 * oversize body inflates per-call token spend without bound. These limits cut
 * the request off before it reaches the provider.
 *
 * <p>Limits are tunable via system properties so an operator can widen them for
 * a heavier deployment without a rebuild; the defaults are generous for normal
 * BI questions (a few sentences + a short chat history) yet cap obvious abuse.
 */
public final class AiAskGuard {

    private AiAskGuard() {}

    /** Max characters in a single {@code question}. */
    public static final int MAX_QUESTION_CHARS = intProp("saiku.ai.ask.maxQuestionChars", 8_000);

    /** Max number of prior conversation turns accepted in {@code history}. */
    public static final int MAX_HISTORY_MESSAGES = intProp("saiku.ai.ask.maxHistoryMessages", 20);

    /** Max characters in any single history message's content. */
    public static final int MAX_MESSAGE_CHARS = intProp("saiku.ai.ask.maxMessageChars", 8_000);

    /** Max combined characters across the question + all history content. */
    public static final int MAX_TOTAL_CHARS = intProp("saiku.ai.ask.maxTotalChars", 40_000);

    /**
     * @return the first size violation found, or {@code null} if the request is
     *     within all limits. A {@code null} body is treated as in-bounds — the
     *     caller already rejects a null body with its own 400.
     */
    public static Violation checkSize(AiAskApi.AskRequest body) {
        if (body == null) return null;
        List<String> historyContents = new java.util.ArrayList<>();
        if (body.getHistory() != null) {
            for (AiAskApi.NlAskMessageDto m : body.getHistory()) {
                historyContents.add(m == null ? null : m.getContent());
            }
        }
        return checkSize(body.getQuestion(), historyContents);
    }

    /**
     * Primitive size check shared by the MDX ask endpoints (via {@link #checkSize(AiAskApi.AskRequest)})
     * and the Ossie ask endpoint (saiku#1459), which carries its question + history as raw strings
     * rather than an {@link AiAskApi.AskRequest}. A {@code null} question / history is treated as
     * in-bounds — the caller rejects an absent question with its own 400.
     *
     * @param question the free-form ask text
     * @param historyContents the {@code content} of each prior turn (nulls tolerated)
     */
    public static Violation checkSize(String question, List<String> historyContents) {
        long total = question == null ? 0 : question.length();
        if (question != null && question.length() > MAX_QUESTION_CHARS) {
            return new Violation(
                    "question",
                    "question exceeds the " + MAX_QUESTION_CHARS + "-character limit (" + question.length() + ").",
                    true);
        }

        if (historyContents != null) {
            if (historyContents.size() > MAX_HISTORY_MESSAGES) {
                // Count overflow is a malformed/abusive request shape, not an
                // oversize payload — surface as 400 so the client trims turns.
                return new Violation(
                        "history",
                        "history exceeds the " + MAX_HISTORY_MESSAGES + "-message limit (" + historyContents.size()
                                + ").",
                        false);
            }
            for (String content : historyContents) {
                if (content == null) continue;
                if (content.length() > MAX_MESSAGE_CHARS) {
                    return new Violation(
                            "history",
                            "a history message exceeds the " + MAX_MESSAGE_CHARS + "-character limit.",
                            true);
                }
                total += content.length();
            }
        }

        if (total > MAX_TOTAL_CHARS) {
            return new Violation(
                    "history",
                    "combined question + history exceeds the " + MAX_TOTAL_CHARS + "-character limit (" + total + ").",
                    true);
        }
        return null;
    }

    private static int intProp(String name, int def) {
        try {
            String v = System.getProperty(name);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** A single size-limit breach. */
    public static final class Violation {
        private final String field;
        private final String message;
        private final boolean payloadTooLarge;

        public Violation(String field, String message, boolean payloadTooLarge) {
            this.field = field;
            this.message = message;
            this.payloadTooLarge = payloadTooLarge;
        }

        public String getField() {
            return field;
        }

        public String getMessage() {
            return message;
        }

        /** True → map to HTTP 413 (oversize). False → HTTP 400 (bad shape, e.g. too many turns). */
        public boolean isPayloadTooLarge() {
            return payloadTooLarge;
        }
    }
}
