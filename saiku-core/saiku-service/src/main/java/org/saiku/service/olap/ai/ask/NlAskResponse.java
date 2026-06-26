/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

/**
 * Output of {@link NlAskProvider#ask(NlAskRequest)}.
 *
 * <p>The provider returns the raw {@link org.saiku.service.olap.ai.AiQueryRequest} JSON emitted by
 * the model. Validation against the live cube happens in the existing {@code /ai/query} converter
 * — keeping this contract narrow (parse + emit) means a misbehaving provider can be diagnosed
 * without coupling it to the converter.
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
 * @param aiQueryRequestJson the structured request emitted by the model; {@code null} when
 *     {@code degraded}
 * @param degraded {@code true} iff the provider could not return a usable request
 * @param reason short, human-readable explanation when {@code degraded}; empty otherwise
 * @param model echo of the model id the provider used (for audit / telemetry); {@code null} when
 *     not applicable (noop)
 * @param inputTokens prompt token count when reported by the provider; {@code -1} when unknown
 * @param outputTokens completion token count when reported by the provider; {@code -1} when
 *     unknown
 */
public record NlAskResponse(
        String aiQueryRequestJson, boolean degraded, String reason, String model, int inputTokens, int outputTokens) {

    public NlAskResponse {
        reason = reason == null ? "" : reason;
    }

    public static NlAskResponse ok(String json, String model, int inputTokens, int outputTokens) {
        return new NlAskResponse(json, false, "", model, inputTokens, outputTokens);
    }

    public static NlAskResponse degraded(String reason) {
        return new NlAskResponse(null, true, reason, null, -1, -1);
    }

    public static NlAskResponse degraded(String reason, String model) {
        return new NlAskResponse(null, true, reason, model, -1, -1);
    }
}
