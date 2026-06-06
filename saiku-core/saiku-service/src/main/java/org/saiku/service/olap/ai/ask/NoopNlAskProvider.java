/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

/**
 * Default {@link NlAskProvider} returned by {@link NlAskProviderFactory} when no provider is
 * configured (the out-of-the-box state). Always returns a degraded response so the {@code /ai/ask}
 * endpoint can render a clear "AI ask is not configured" 503 instead of silently breaking.
 */
public final class NoopNlAskProvider implements NlAskProvider {

    public static final String REASON = "AI ask is not configured. Set saiku.ai.ask.provider "
            + "to 'anthropic' or 'openai' and supply the matching API key (env: ANTHROPIC_API_KEY "
            + "or OPENAI_API_KEY) to enable the feature.";

    @Override
    public NlAskResponse ask(NlAskRequest request) {
        return NlAskResponse.degraded(REASON);
    }
}
