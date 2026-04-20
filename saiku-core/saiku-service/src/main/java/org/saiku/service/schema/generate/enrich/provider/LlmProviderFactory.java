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
 * </ul>
 *
 * <p>API keys are never logged. When the {@code anthropic} provider is selected, a single INFO
 * line records {@code provider=anthropic, model=...} at construction time.
 */
public final class LlmProviderFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmProviderFactory.class);

    /** Property value indicating the default offline provider. */
    public static final String PROVIDER_NOOP = "noop";

    /** Property value indicating the Anthropic Messages API provider. */
    public static final String PROVIDER_ANTHROPIC = "anthropic";

    /** Environment-variable fallback for the Anthropic API key. */
    public static final String ENV_ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";

    private final String providerName;
    private final String apiKey;
    private final String model;
    private final Function<String, String> envLookup;

    /**
     * Production constructor — uses {@link System#getenv(String)} for env-var lookups.
     *
     * @param providerName provider id; {@code null} / blank / unresolved placeholder → noop
     * @param apiKey Anthropic API key; may be null (will try env fallback)
     * @param model Anthropic model id; may be null (uses {@link AnthropicProvider#DEFAULT_MODEL})
     */
    public LlmProviderFactory(String providerName, String apiKey, String model) {
        this(providerName, apiKey, model, System::getenv);
    }

    /** Package-visible constructor for tests — env lookup is injectable. */
    public LlmProviderFactory(String providerName, String apiKey, String model, Function<String, String> envLookup) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        this.model = model;
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
            String resolvedKey = resolveApiKey();
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
        LOGGER.warn("Unknown schema-gen LLM provider '{}'; falling back to NoopProvider.", providerName);
        return new NoopProvider();
    }

    private String resolveApiKey() {
        String explicit = normalise(apiKey);
        if (explicit != null) {
            return explicit;
        }
        String fromEnv = normalise(envLookup.apply(ENV_ANTHROPIC_API_KEY));
        return fromEnv;
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
