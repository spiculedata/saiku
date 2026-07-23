/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

/**
 * Output of {@link NlAskProvider#ask(NlAskRequest)}.
 *
 * <p>The provider returns the raw JSON the model emitted, plus a {@link Kind} discriminator
 * identifying which tool the model picked. Four intents are routed by {@link AiAskService}:
 *
 * <ul>
 *   <li>{@link Kind#QUERY} — an {@code AiQueryRequest} the converter will execute.
 *   <li>{@link Kind#INSIGHT} — an {@code AiInsight} (markdown analysis of the user's current
 *       cellset). No execution.
 *   <li>{@link Kind#VIEW_CHANGE} — an {@code AiViewChange} (target viewMode + chartType). No
 *       execution.
 *   <li>{@link Kind#EMAIL_DRAFT} — an {@code AiEmailDraft} (email body summarising the user's
 *       current cellset). Draft-only — the AI never sends.
 *   <li>{@link Kind#REFUSAL} — the model called the refusal tool (off-topic question). Surface
 *       {@link #reason()} verbatim to the user.
 * </ul>
 *
 * <p>Validation against the live cube happens in the existing {@code /ai/query} converter for the
 * QUERY kind only — keeping this contract narrow (parse + emit) means a misbehaving provider can be
 * diagnosed without coupling it to the converter or the cellset.
 *
 * <p>Degradation modes (return this rather than throw):
 *
 * <ul>
 *   <li>Provider not configured (noop) — {@code degraded=true, reason="provider not configured"}.
 *   <li>Transport error / non-2xx upstream — {@code degraded=true, reason="HTTP NNN: ..."}.
 *   <li>Model refused or emitted non-tool output — {@code degraded=true, reason="no tool_use"}.
 *   <li>Parse failure — {@code degraded=true, reason="parse error: ..."}.
 * </ul>
 *
 * @param kind which intent the model picked; {@code null} when {@code degraded}
 * @param payloadJson the raw JSON the model emitted for the picked tool; {@code null} when {@code
 *     degraded}
 * @param degraded {@code true} iff the provider could not return a usable payload
 * @param reason short, human-readable explanation when {@code degraded}; empty otherwise. Also used
 *     to carry the refusal sentence when {@code kind == REFUSAL} (in which case {@code degraded} is
 *     false but {@code reason} carries the message).
 * @param model echo of the model id the provider used (for audit / telemetry); {@code null} when
 *     not applicable (noop)
 * @param inputTokens prompt token count when reported by the provider; {@code -1} when unknown
 * @param outputTokens completion token count when reported by the provider; {@code -1} when
 *     unknown
 * @param toolCallId provider-assigned id for the emitted tool call (Anthropic tool_use id / OpenAI
 *     tool_call id); {@code null} for degraded/refusal/insight/view-change responses
 * @param retryAfterMs {@code -1} when not a rate-limit; {@code >= 0} when the provider was rate
 *     limited (HTTP 429) and this is the suggested wait, in ms, before retrying ({@code 0} means
 *     rate-limited but the provider gave no usable hint). Also {@code >= 0} for {@link
 *     #retryableToolError(String, String)} — a transient tool-call failure — so both share the
 *     same paced-retry path in the chained-ask loop.
 */
public record NlAskResponse(
        Kind kind,
        String payloadJson,
        boolean degraded,
        String reason,
        String model,
        int inputTokens,
        int outputTokens,
        String toolCallId,
        long retryAfterMs) {

    public enum Kind {
        QUERY,
        INSIGHT,
        VIEW_CHANGE,
        EMAIL_DRAFT,
        REFUSAL
    }

    public NlAskResponse {
        reason = reason == null ? "" : reason;
    }

    public static NlAskResponse okQuery(String json, String model, int inputTokens, int outputTokens) {
        return okQuery(json, model, inputTokens, outputTokens, null);
    }

    public static NlAskResponse okQuery(
            String json, String model, int inputTokens, int outputTokens, String toolCallId) {
        return new NlAskResponse(Kind.QUERY, json, false, "", model, inputTokens, outputTokens, toolCallId, -1L);
    }

    public static NlAskResponse okInsight(String json, String model, int inputTokens, int outputTokens) {
        return new NlAskResponse(Kind.INSIGHT, json, false, "", model, inputTokens, outputTokens, null, -1L);
    }

    public static NlAskResponse okViewChange(String json, String model, int inputTokens, int outputTokens) {
        return new NlAskResponse(Kind.VIEW_CHANGE, json, false, "", model, inputTokens, outputTokens, null, -1L);
    }

    public static NlAskResponse okEmailDraft(String json, String model, int inputTokens, int outputTokens) {
        return new NlAskResponse(Kind.EMAIL_DRAFT, json, false, "", model, inputTokens, outputTokens, null, -1L);
    }

    public static NlAskResponse refusal(String reason, String model, int inputTokens, int outputTokens) {
        return new NlAskResponse(Kind.REFUSAL, null, false, reason, model, inputTokens, outputTokens, null, -1L);
    }

    public static NlAskResponse degraded(String reason) {
        return new NlAskResponse(null, null, true, reason, null, -1, -1, null, -1L);
    }

    public static NlAskResponse degraded(String reason, String model) {
        return new NlAskResponse(null, null, true, reason, model, -1, -1, null, -1L);
    }

    /**
     * Provider hit a rate limit (HTTP 429). Degraded like any transport failure, but carries the
     * suggested wait so a caller (the chained-ask loop) can pace + retry rather than give up.
     *
     * @param retryAfterMs suggested wait in ms; 0 when the provider gave no hint
     */
    public static NlAskResponse rateLimited(String reason, String model, long retryAfterMs) {
        return new NlAskResponse(null, null, true, reason, model, -1, -1, null, Math.max(0, retryAfterMs));
    }

    /** Short backoff for {@link #retryableToolError(String, String)} — the model call itself takes
     *  seconds, so this only avoids the 429 no-hint 5s default and gives a tiny gap before re-asking. */
    public static final long TOOL_ERROR_BACKOFF_MS = 500L;

    /**
     * Provider returned a transient tool-call failure (e.g. Groq HTTP 400 {@code tool_use_failed}):
     * the model fumbled the tool-call format. Degraded, but retryable — a re-ask usually succeeds —
     * so it carries a short {@link #TOOL_ERROR_BACKOFF_MS} wait, letting the chained-ask loop retry it
     * through the same paced-retry path as a rate limit.
     */
    public static NlAskResponse retryableToolError(String reason, String model) {
        return new NlAskResponse(null, null, true, reason, model, -1, -1, null, TOOL_ERROR_BACKOFF_MS);
    }

    /**
     * @deprecated Pre-multi-tool alias for {@link #okQuery(String, String, int, int)}. Kept so the
     *     existing provider tests don't churn. New code should use the kind-explicit factories.
     */
    @Deprecated
    public static NlAskResponse ok(String json, String model, int inputTokens, int outputTokens) {
        return okQuery(json, model, inputTokens, outputTokens);
    }

    /**
     * @return the payload JSON when {@link #kind()} is {@link Kind#QUERY}; {@code null} otherwise.
     * @deprecated Pre-multi-tool alias for {@link #payloadJson()}. Same churn-avoidance as {@link
     *     #ok(String, String, int, int)}.
     */
    @Deprecated
    public String aiQueryRequestJson() {
        return kind == Kind.QUERY ? payloadJson : null;
    }
}
