/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;

/**
 * Azure OpenAI Service adapter (saiku#1431).
 *
 * <p>Azure OpenAI is API-compatible with OpenAI's Chat Completions API but:
 *
 * <ul>
 *   <li>uses per-deployment URLs of the form
 *       {@code https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=<version>}
 *       — the deployment name in the URL takes the place of the {@code model} request field, and
 *       the {@code ?api-version=} query parameter selects the wire contract;
 *   <li>uses the {@code api-key} header for authentication instead of the OpenAI-style
 *       {@code Authorization: Bearer} header.
 * </ul>
 *
 * <p>Everything else — the tool-use request/response shape, the structured-output loop, error
 * handling — is identical to {@link OpenAINlAskProvider}. This subclass overrides only the auth
 * header seam so operators point the endpoint at their Azure deployment URL, drop
 * {@code AZURE_OPENAI_API_KEY} in the env, and get a working Azure-hosted ask surface.
 *
 * <p>Configuration reaches this provider through the same
 * {@code saiku.ai.ask.endpoint} / {@code saiku.ai.ask.apiKey} / {@code saiku.ai.ask.model}
 * properties as the OpenAI provider; the only novel setting is
 * {@code saiku.ai.ask.provider=azure-openai} on the selector.
 */
public final class AzureOpenAiNlAskProvider extends OpenAINlAskProvider {

    /**
     * Fallback deployment name for Azure customers who forget to set
     * {@code saiku.ai.ask.model}. Azure's routing key is the deployment name (embedded in the
     * URL), not the raw model id — but leaving the model field empty fails Azure's request
     * validation, so the factory nudges operators to name their deployment explicitly and this
     * default keeps the provider constructing even when they don't.
     */
    public static final String DEFAULT_AZURE_MODEL = "gpt-4o-mini";

    private final String apiKey;

    public AzureOpenAiNlAskProvider(Config config) {
        super(config);
        this.apiKey = config.apiKey();
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    AzureOpenAiNlAskProvider(Config config, HttpClient http) {
        super(config, http);
        this.apiKey = config.apiKey();
    }

    /**
     * Azure OpenAI authenticates with a plain {@code api-key} header — no {@code Bearer} scheme
     * prefix. This is the whole delta from the parent provider.
     */
    @Override
    protected void applyAuthHeaders(HttpRequest.Builder builder) {
        builder.header("api-key", apiKey);
    }
}
