/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config-driven {@link NlAskProvider} selector used by Spring wiring.
 *
 * <p>Given a provider name + optional credentials + model, returns the matching provider
 * implementation. Unknown / blank names, missing API keys, and unresolved {@code ${...}} property
 * placeholders all fall back to {@link NoopNlAskProvider} with a WARN log rather than throwing —
 * the workspace must boot even when the AI ask layer is misconfigured.
 *
 * <p>Supported providers:
 *
 * <ul>
 *   <li>{@code noop} (default) — {@link NoopNlAskProvider}; renders a clear "not configured" 503.
 *   <li>{@code anthropic} — {@link AnthropicNlAskProvider}; API key from {@code apiKey} arg or the
 *       {@code ANTHROPIC_API_KEY} env var.
 *   <li>{@code openai} — {@link OpenAINlAskProvider}; API key from {@code apiKey} arg or the
 *       {@code OPENAI_API_KEY} env var.
 *   <li>{@code azure-openai} — {@link AzureOpenAiNlAskProvider} (saiku#1431); API key from
 *       {@code apiKey} arg or the {@code AZURE_OPENAI_API_KEY} env var. Requires
 *       {@code endpoint} pointing at the deployment URL
 *       ({@code https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=<version>}).
 * </ul>
 *
 * <p>API keys are never logged. When a non-noop provider is selected, a single INFO line records
 * {@code provider=..., model=...} at construction time.
 */
public final class NlAskProviderFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(NlAskProviderFactory.class);

    public static final String PROVIDER_NOOP = "noop";
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_OPENAI = "openai";
    /** saiku#1431 — the Azure OpenAI Service. */
    public static final String PROVIDER_AZURE_OPENAI = "azure-openai";

    public static final String ENV_ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    public static final String ENV_OPENAI_API_KEY = "OPENAI_API_KEY";
    public static final String ENV_AZURE_OPENAI_API_KEY = "AZURE_OPENAI_API_KEY";

    private final String providerName;
    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final Function<String, String> envLookup;

    /** Production constructor — uses {@link System#getenv(String)} for env-var lookups. */
    public NlAskProviderFactory(String providerName, String apiKey, String model, String endpoint) {
        this(providerName, apiKey, model, endpoint, System::getenv);
    }

    /** Package-visible constructor for tests — env lookup is injectable. */
    public NlAskProviderFactory(
            String providerName, String apiKey, String model, String endpoint, Function<String, String> envLookup) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
        this.envLookup = envLookup == null ? name -> null : envLookup;
    }

    /** Build the configured {@link NlAskProvider}. Never returns null. */
    public NlAskProvider build() {
        String name = normalise(providerName);
        if (name == null || name.isEmpty() || PROVIDER_NOOP.equalsIgnoreCase(name)) {
            LOGGER.info("AI ask provider: noop (not configured)");
            return new NoopNlAskProvider();
        }
        if (PROVIDER_ANTHROPIC.equalsIgnoreCase(name)) {
            String resolvedKey = resolveApiKey(ENV_ANTHROPIC_API_KEY);
            if (resolvedKey == null) {
                LOGGER.warn(
                        "AI ask provider set to 'anthropic' but no API key configured "
                                + "(neither saiku.ai.ask.apiKey nor {} is set); falling back to NoopProvider.",
                        ENV_ANTHROPIC_API_KEY);
                return new NoopNlAskProvider();
            }
            String resolvedModel = normalise(model);
            String effectiveModel = resolvedModel == null ? AnthropicNlAskProvider.DEFAULT_MODEL : resolvedModel;
            LOGGER.info("AI ask provider: anthropic (model={})", effectiveModel);
            return new AnthropicNlAskProvider(
                    new AnthropicNlAskProvider.Config(resolvedKey, effectiveModel, 0.0, 4096, Duration.ofSeconds(60)));
        }
        if (PROVIDER_OPENAI.equalsIgnoreCase(name)) {
            String resolvedKey = resolveApiKey(ENV_OPENAI_API_KEY);
            if (resolvedKey == null) {
                LOGGER.warn(
                        "AI ask provider set to 'openai' but no API key configured "
                                + "(neither saiku.ai.ask.apiKey nor {} is set); falling back to NoopProvider.",
                        ENV_OPENAI_API_KEY);
                return new NoopNlAskProvider();
            }
            String resolvedModel = normalise(model);
            String effectiveModel = resolvedModel == null ? OpenAINlAskProvider.DEFAULT_MODEL : resolvedModel;
            String resolvedEndpoint = normalise(endpoint);
            String effectiveEndpoint =
                    resolvedEndpoint == null ? OpenAINlAskProvider.DEFAULT_ENDPOINT : resolvedEndpoint;
            LOGGER.info("AI ask provider: openai (model={}, endpoint={})", effectiveModel, effectiveEndpoint);
            return new OpenAINlAskProvider(new OpenAINlAskProvider.Config(
                    resolvedKey, effectiveModel, effectiveEndpoint, 0.0, 4096, Duration.ofSeconds(60)));
        }
        if (PROVIDER_AZURE_OPENAI.equalsIgnoreCase(name)) {
            String resolvedKey = resolveApiKey(ENV_AZURE_OPENAI_API_KEY);
            if (resolvedKey == null) {
                LOGGER.warn(
                        "AI ask provider set to 'azure-openai' but no API key configured "
                                + "(neither saiku.ai.ask.apiKey nor {} is set); falling back to NoopProvider.",
                        ENV_AZURE_OPENAI_API_KEY);
                return new NoopNlAskProvider();
            }
            String resolvedEndpoint = normalise(endpoint);
            if (resolvedEndpoint == null) {
                // Azure has no default endpoint — the deployment URL is per-resource and
                // per-deployment, so we can't invent one. Refuse to construct rather than
                // silently emitting to OpenAI's default host on the wrong auth header.
                LOGGER.warn("AI ask provider set to 'azure-openai' but saiku.ai.ask.endpoint is unset. "
                        + "Configure it to your deployment URL "
                        + "(https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=<version>). "
                        + "Falling back to NoopProvider.");
                return new NoopNlAskProvider();
            }
            String resolvedModel = normalise(model);
            String effectiveModel =
                    resolvedModel == null ? AzureOpenAiNlAskProvider.DEFAULT_AZURE_MODEL : resolvedModel;
            LOGGER.info(
                    "AI ask provider: azure-openai (deployment/model={}, endpoint={})",
                    effectiveModel,
                    resolvedEndpoint);
            return new AzureOpenAiNlAskProvider(new OpenAINlAskProvider.Config(
                    resolvedKey, effectiveModel, resolvedEndpoint, 0.0, 4096, Duration.ofSeconds(60)));
        }
        LOGGER.warn("Unknown AI ask provider '{}'; falling back to NoopProvider.", providerName);
        return new NoopNlAskProvider();
    }

    private String resolveApiKey(String envKey) {
        String explicit = normalise(apiKey);
        if (explicit != null) {
            return explicit;
        }
        return normalise(envLookup.apply(envKey));
    }

    private static String normalise(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("${") && t.endsWith("}")) {
            return null;
        }
        return t;
    }
}
