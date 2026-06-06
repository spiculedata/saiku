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
 *   <li>never log API keys or prompt bodies at INFO or above (cube schemas may be sensitive).
 * </ul>
 *
 * <p>The {@link NoopNlAskProvider} is the default when no provider is configured — it always
 * returns degraded so the API can render a clear "not configured" message.
 */
@FunctionalInterface
public interface NlAskProvider {

    NlAskResponse ask(NlAskRequest request);
}
