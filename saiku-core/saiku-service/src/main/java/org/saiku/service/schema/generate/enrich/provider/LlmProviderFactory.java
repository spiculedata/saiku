/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config-driven {@link LlmProvider} selector used by Spring wiring.
 *
 * <p>Given a provider name and (optional) credentials, returns the matching provider
 * implementation. Unknown/blank names, missing API keys, and unresolved {@code ${...}} property
 * placeholders all fall back to {@link NoopProvider} with a WARN log rather than throwing — the
 * schema-generation pipeline stays usable even when the LLM layer is misconfigured.
 *
 * <p>Supported providers:
 *
 * <ul>
 *   <li>{@code noop} (default) — offline rule-based {@link NoopProvider}.
 *   <li>{@code anthropic} — {@link AnthropicProvider}; requires an API key from either the
 *       explicit {@code apiKey} argument or the {@code ANTHROPIC_API_KEY} environment variable.
 *   <li>{@code openai} (saiku#904) — {@link OpenAiCompatProvider} against the hosted OpenAI API;
 *       requires an API key from the explicit {@code openAiApiKey} argument or the {@code
 *       OPENAI_API_KEY} environment variable.
 *   <li>{@code ollama} (saiku#904) — {@link OpenAiCompatProvider} pointed at a local/self-hosted
 *       Ollama instance's OpenAI-compatible endpoint. NO API key is required — Ollama doesn't check
 *       one — so a missing key resolves to a harmless placeholder instead of falling back to
 *       {@link NoopProvider}. Defaults: endpoint {@code http://localhost:11434/v1/chat/completions},
 *       model {@value #DEFAULT_OLLAMA_MODEL}.
 * </ul>
 *
 * <p>API keys are never logged. When a non-noop provider is selected, a single INFO line records
 * {@code provider=..., model=...} at construction time.
 */
public final class LlmProviderFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmProviderFactory.class);

    /** Property value indicating the default offline provider. */
    public static final String PROVIDER_NOOP = "noop";

    /** Property value indicating the Anthropic Messages API provider. */
    public static final String PROVIDER_ANTHROPIC = "anthropic";

    /** saiku#904 — the hosted OpenAI Chat Completions API. */
    public static final String PROVIDER_OPENAI = "openai";

    /** saiku#904 — local/self-hosted Ollama via its OpenAI-compatible endpoint. */
    public static final String PROVIDER_OLLAMA = "ollama";

    /** Environment-variable fallback for the Anthropic API key. */
    public static final String ENV_ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";

    /** saiku#904 — environment-variable fallback for the OpenAI API key. */
    public static final String ENV_OPENAI_API_KEY = "OPENAI_API_KEY";

    /** saiku#904 — optional; Ollama itself never checks this, only an authenticating proxy would. */
    public static final String ENV_OLLAMA_API_KEY = "OLLAMA_API_KEY";

    /** saiku#904 — default Ollama OpenAI-compat endpoint (local daemon, default port). */
    public static final String DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434/v1/chat/completions";

    /** saiku#904 — a broadly-available default tag; operators override via {@code openAiModel}. */
    public static final String DEFAULT_OLLAMA_MODEL = "llama3.1";

    /**
     * saiku#904 — placeholder sent as the bearer token when no key is configured for {@code
     * ollama}. See {@link OpenAiCompatProvider.Config}, which requires a non-blank key.
     */
    public static final String OLLAMA_KEYLESS_PLACEHOLDER = "ollama";

    private final String providerName;
    private final String apiKey;
    private final String model;
    private final String openAiApiKey;
    private final String openAiModel;
    private final String openAiEndpoint;
    private final Function<String, String> envLookup;

    /**
     * Production constructor — uses {@link System#getenv(String)} for env-var lookups. No
     * openai/ollama config; use the 4-arg overload below to enable those providers.
     *
     * @param providerName provider id; {@code null} / blank / unresolved placeholder → noop
     * @param apiKey Anthropic API key; may be null (will try env fallback)
     * @param model Anthropic model id; may be null (uses {@link AnthropicProvider#DEFAULT_MODEL})
     */
    public LlmProviderFactory(String providerName, String apiKey, String model) {
        this(providerName, apiKey, model, null, null, null, System::getenv);
    }

    /** Package-visible constructor for tests — env lookup is injectable; no openai/ollama config. */
    public LlmProviderFactory(String providerName, String apiKey, String model, Function<String, String> envLookup) {
        this(providerName, apiKey, model, null, null, null, envLookup);
    }

    /**
     * Production constructor with openai/ollama support (saiku#904) — uses {@link
     * System#getenv(String)} for env-var lookups.
     *
     * @param providerName provider id; {@code null} / blank / unresolved placeholder → noop
     * @param apiKey Anthropic API key; may be null (will try env fallback); unused for openai/ollama
     * @param model Anthropic model id; may be null; unused for openai/ollama
     * @param openAiApiKey API key for {@code openai}/{@code ollama}; may be null (openai will try
     *     env fallback and then fall back to noop; ollama falls back to a keyless placeholder)
     * @param openAiModel model id for {@code openai}/{@code ollama}; may be null
     * @param openAiEndpoint endpoint override for {@code openai}/{@code ollama}; may be null
     */
    public LlmProviderFactory(
            String providerName,
            String apiKey,
            String model,
            String openAiApiKey,
            String openAiModel,
            String openAiEndpoint) {
        this(providerName, apiKey, model, openAiApiKey, openAiModel, openAiEndpoint, System::getenv);
    }

    /** Package-visible constructor for tests with full openai/ollama control. */
    LlmProviderFactory(
            String providerName,
            String apiKey,
            String model,
            String openAiApiKey,
            String openAiModel,
            String openAiEndpoint,
            Function<String, String> envLookup) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        this.model = model;
        this.openAiApiKey = openAiApiKey;
        this.openAiModel = openAiModel;
        this.openAiEndpoint = openAiEndpoint;
        this.envLookup = envLookup == null ? name -> null : envLookup;
    }

    /** Build the configured {@link LlmProvider}. Never returns null. */
    public LlmProvider build() {
        String name = normalise(providerName);
        if (name == null || name.isEmpty() || PROVIDER_NOOP.equalsIgnoreCase(name)) {
            LOGGER.info("Schema-gen LLM provider: noop (offline rule-based)");
            return new NoopProvider();
        }
        if (PROVIDER_ANTHROPIC.equalsIgnoreCase(name)) {
            String resolvedKey = resolveApiKey(apiKey, ENV_ANTHROPIC_API_KEY);
            if (resolvedKey == null) {
                LOGGER.warn(
                        "Schema-gen LLM provider set to 'anthropic' but no API key configured "
                                + "(neither saiku.schemagen.llm.anthropic.apiKey nor {} is set); "
                                + "falling back to NoopProvider.",
                        ENV_ANTHROPIC_API_KEY);
                return new NoopProvider();
            }
            String resolvedModel = normalise(model);
            String effectiveModel = resolvedModel == null ? AnthropicProvider.DEFAULT_MODEL : resolvedModel;
            LOGGER.info("Schema-gen LLM provider: anthropic (model={})", effectiveModel);
            AnthropicProvider.Config cfg =
                    new AnthropicProvider.Config(resolvedKey, effectiveModel, 0.0, 4096, Duration.ofSeconds(60));
            return new AnthropicProvider(cfg);
        }
        if (PROVIDER_OPENAI.equalsIgnoreCase(name)) {
            String resolvedKey = resolveApiKey(openAiApiKey, ENV_OPENAI_API_KEY);
            if (resolvedKey == null) {
                LOGGER.warn(
                        "Schema-gen LLM provider set to 'openai' but no API key configured "
                                + "(neither saiku.schemagen.llm.openai.apiKey nor {} is set); "
                                + "falling back to NoopProvider.",
                        ENV_OPENAI_API_KEY);
                return new NoopProvider();
            }
            String resolvedModel = normalise(openAiModel);
            String effectiveModel = resolvedModel == null ? OpenAiCompatProvider.DEFAULT_MODEL : resolvedModel;
            String resolvedEndpoint = normalise(openAiEndpoint);
            String effectiveEndpoint =
                    resolvedEndpoint == null ? OpenAiCompatProvider.DEFAULT_ENDPOINT : resolvedEndpoint;
            LOGGER.info("Schema-gen LLM provider: openai (model={}, endpoint={})", effectiveModel, effectiveEndpoint);
            OpenAiCompatProvider.Config cfg = new OpenAiCompatProvider.Config(
                    resolvedKey, effectiveModel, effectiveEndpoint, 0.0, 4096, Duration.ofSeconds(60));
            return new OpenAiCompatProvider(cfg);
        }
        if (PROVIDER_OLLAMA.equalsIgnoreCase(name)) {
            // saiku#904: Ollama never validates a key — resolve explicit -> env -> a keyless
            // placeholder, so this provider is usable with zero credentials configured.
            String resolvedKey = resolveApiKey(openAiApiKey, ENV_OLLAMA_API_KEY);
            String effectiveKey = resolvedKey == null ? OLLAMA_KEYLESS_PLACEHOLDER : resolvedKey;
            String resolvedModel = normalise(openAiModel);
            String effectiveModel = resolvedModel == null ? DEFAULT_OLLAMA_MODEL : resolvedModel;
            String resolvedEndpoint = normalise(openAiEndpoint);
            String effectiveEndpoint = resolvedEndpoint == null ? DEFAULT_OLLAMA_ENDPOINT : resolvedEndpoint;
            LOGGER.info("Schema-gen LLM provider: ollama (model={}, endpoint={})", effectiveModel, effectiveEndpoint);
            OpenAiCompatProvider.Config cfg = new OpenAiCompatProvider.Config(
                    effectiveKey, effectiveModel, effectiveEndpoint, 0.0, 4096, Duration.ofSeconds(60));
            return new OpenAiCompatProvider(cfg);
        }
        LOGGER.warn("Unknown schema-gen LLM provider '{}'; falling back to NoopProvider.", providerName);
        return new NoopProvider();
    }

    /**
     * Config summary for the {@code /saiku/info/diagnostics} surface (saiku#904) — the same provider
     * selection {@link #build()} performs, but without constructing an HTTP client or making any
     * network call. {@code configured} is {@code false} whenever {@link #build()} would fall back to
     * {@link NoopProvider} (unknown provider, or a hosted provider missing its API key); {@code
     * ollama} is always {@code true} once selected, since it never requires a key.
     */
    public record Descriptor(String provider, String model, String endpoint, boolean configured) {}

    /** Describe the configured provider without building it. Never returns null. */
    public Descriptor describe() {
        String name = normalise(providerName);
        if (name == null || name.isEmpty() || PROVIDER_NOOP.equalsIgnoreCase(name)) {
            return new Descriptor(PROVIDER_NOOP, null, null, false);
        }
        if (PROVIDER_ANTHROPIC.equalsIgnoreCase(name)) {
            boolean configured = resolveApiKey(apiKey, ENV_ANTHROPIC_API_KEY) != null;
            String effectiveModel = normalise(model) == null ? AnthropicProvider.DEFAULT_MODEL : normalise(model);
            return new Descriptor(PROVIDER_ANTHROPIC, effectiveModel, null, configured);
        }
        if (PROVIDER_OPENAI.equalsIgnoreCase(name)) {
            boolean configured = resolveApiKey(openAiApiKey, ENV_OPENAI_API_KEY) != null;
            String effectiveModel =
                    normalise(openAiModel) == null ? OpenAiCompatProvider.DEFAULT_MODEL : normalise(openAiModel);
            String effectiveEndpoint = normalise(openAiEndpoint) == null
                    ? OpenAiCompatProvider.DEFAULT_ENDPOINT
                    : normalise(openAiEndpoint);
            return new Descriptor(PROVIDER_OPENAI, effectiveModel, effectiveEndpoint, configured);
        }
        if (PROVIDER_OLLAMA.equalsIgnoreCase(name)) {
            String effectiveModel = normalise(openAiModel) == null ? DEFAULT_OLLAMA_MODEL : normalise(openAiModel);
            String effectiveEndpoint =
                    normalise(openAiEndpoint) == null ? DEFAULT_OLLAMA_ENDPOINT : normalise(openAiEndpoint);
            return new Descriptor(PROVIDER_OLLAMA, effectiveModel, effectiveEndpoint, true);
        }
        return new Descriptor(providerName, null, null, false);
    }

    private String resolveApiKey(String explicitKey, String envKey) {
        String explicit = normalise(explicitKey);
        if (explicit != null) {
            return explicit;
        }
        return normalise(envLookup.apply(envKey));
    }

    /**
     * Trim and null-out blank / unresolved Spring placeholders (e.g. when a property wasn't
     * defined and {@code ignore-unresolvable=true} left the literal {@code ${...}} in place).
     */
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
