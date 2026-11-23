/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

/**
 * SPI for the natural-language ask layer that powers the workspace AI Query panel and any future
 * "ask in plain English" surface (slash-commands, KPI explanations, etc.).
 *
 * <p>An implementation receives a {@link NlAskRequest} (user question + cube schema + JSON-Schema
 * for the structured target) and returns a {@link NlAskResponse} whose
 * {@link NlAskResponse#aiQueryRequestJson()} is the model's emission as a string ready to deserialise
 * into an {@link org.saiku.service.olap.ai.AiQueryRequest}. The provider does <strong>not</strong>
 * execute the query — that's the caller's job, via the existing {@code /ai/query} pipeline.
 *
 * <p>Implementations MUST:
 *
 * <ul>
 *   <li>be stateless and thread-safe;
 *   <li>force the model to emit JSON conforming to the {@code requestJsonSchema} (e.g. via tool /
 *       function calling); raw-prose output is treated as a degradation;
 *   <li>never throw — degraded paths return a {@link NlAskResponse#degraded(String)} response so
 *       the API surface can present a clean error to the user;
 *   <li>never log API keys or prompt bodies at INFO or above (cube schemas may be sensitive);
 *   <li>never throw — degraded paths return a {@link NlAskResponse#degraded(String)} response so
 *       the API surface can present a clean error to the user. A {@link NlAskStreamListener} that
 *       throws {@link java.io.IOException} is not an exception to this rule either: it degrades
 *       too, because the client that went away is no longer there to receive an error.
 * </ul>
 *
 * <p>The {@link NoopNlAskProvider} is the default when no provider is configured — it always
 * returns degraded so the API can render a clear "not configured" message.
 */
public interface NlAskProvider {

    NlAskResponse ask(NlAskRequest request);

    /**
     * Answer {@code request} while handing the model's prose to {@code listener} as it is written
     * (saiku#1484) — true per-token streaming rather than replaying a finished response.
     *
     * <p>The returned {@link NlAskResponse} is identical to what {@link #ask} would have returned:
     * providers implement streaming by <em>reconstructing</em> the buffered response from their
     * event stream and running the ordinary parser over it, so routing, degradation reasons and
     * token accounting can't drift between the two paths.
     *
     * <p>The default implementation doesn't stream from the wire: it calls {@link #ask} and then
     * replays the finished prose to {@code listener} word by word. That keeps every provider
     * correct without implementing anything, and is exactly the pre-#1484 behaviour — a provider
     * that overrides this method is the only thing that can make first-token latency real.
     */
    default NlAskResponse askStreaming(NlAskRequest request, NlAskStreamListener listener) {
        NlAskResponse response = ask(request);
        if (listener != null && !response.degraded() && response.payloadJson() != null) {
            ProseFallback.replay(response, listener);
        }
        return response;
    }

    /**
     * Whether this provider can actually answer a request. {@code false} only for
     * {@link NoopNlAskProvider} — the placeholder returned when no provider name / API key is
     * configured. Concrete providers (Anthropic, OpenAI, etc.) always return {@code true} because
     * the factory wouldn't construct them without credentials.
     *
     * <p>Used by the {@code /ai/ask/health} endpoint so the UI can hide the "Ask the AI" toolbar
     * button on instances that haven't wired up an LLM key — wasted clicks otherwise.
     */
    default boolean isConfigured() {
        return true;
    }
}
