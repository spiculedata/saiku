/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.Test;

/** Unit tests for {@link NlAskProviderFactory}. */
public class NlAskProviderFactoryTest {

    private static final String PROP = "saiku.ai.ask.timeoutSeconds";

    /* ---- askRequestTimeout() ----
     * Note: the env-var path (SAIKU_AI_ASK_TIMEOUT_SECONDS) can't be set per-test from within the
     * JVM, so only the system-property fallback is exercised here; the property path is
     * representative of the same parse/clamp logic the env path shares. */

    @Test
    public void askRequestTimeoutDefaultsTo60sWhenUnset() {
        String saved = System.getProperty(PROP);
        System.clearProperty(PROP);
        try {
            assertEquals(Duration.ofSeconds(60), NlAskProviderFactory.askRequestTimeout());
        } finally {
            restore(saved);
        }
    }

    @Test
    public void askRequestTimeoutUsesSystemProperty() {
        String saved = System.getProperty(PROP);
        System.setProperty(PROP, "300");
        try {
            assertEquals(Duration.ofSeconds(300), NlAskProviderFactory.askRequestTimeout());
        } finally {
            restore(saved);
        }
    }

    @Test
    public void askRequestTimeoutClampsLow() {
        String saved = System.getProperty(PROP);
        System.setProperty(PROP, "1");
        try {
            assertEquals(Duration.ofSeconds(5), NlAskProviderFactory.askRequestTimeout());
        } finally {
            restore(saved);
        }
    }

    @Test
    public void askRequestTimeoutClampsHigh() {
        String saved = System.getProperty(PROP);
        System.setProperty(PROP, "99999");
        try {
            assertEquals(Duration.ofSeconds(900), NlAskProviderFactory.askRequestTimeout());
        } finally {
            restore(saved);
        }
    }

    @Test
    public void askRequestTimeoutFallsBackOnNonNumeric() {
        String saved = System.getProperty(PROP);
        System.setProperty(PROP, "abc");
        try {
            assertEquals(Duration.ofSeconds(60), NlAskProviderFactory.askRequestTimeout());
        } finally {
            restore(saved);
        }
    }

    private static void restore(String saved) {
        if (saved == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, saved);
        }
    }

    @Test
    public void defaultsToNoopWhenProviderIsNull() {
        NlAskProvider p = new NlAskProviderFactory(null, null, null, null, env(Map.of())).build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void defaultsToNoopWhenProviderIsExplicitNoop() {
        NlAskProvider p = new NlAskProviderFactory("noop", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void defaultsToNoopWhenProviderUnknown() {
        NlAskProvider p = new NlAskProviderFactory("badger", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void defaultsToNoopWhenSpringPlaceholderUnresolved() {
        NlAskProvider p = new NlAskProviderFactory("${saiku.ai.ask.provider}", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void anthropicWithoutKeyFallsBackToNoop() {
        NlAskProvider p = new NlAskProviderFactory("anthropic", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void anthropicUsesExplicitKey() {
        NlAskProvider p = new NlAskProviderFactory("anthropic", "sk-anthropic", null, null, env(Map.of())).build();
        assertTrue(p instanceof AnthropicNlAskProvider);
    }

    @Test
    public void anthropicFallsBackToEnvKey() {
        NlAskProvider p = new NlAskProviderFactory(
                        "anthropic", null, null, null, env(Map.of("ANTHROPIC_API_KEY", "sk-from-env")))
                .build();
        assertTrue(p instanceof AnthropicNlAskProvider);
    }

    @Test
    public void openaiWithoutKeyFallsBackToNoop() {
        NlAskProvider p = new NlAskProviderFactory("openai", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void openaiUsesEnvKeyAndCustomEndpoint() {
        NlAskProvider p = new NlAskProviderFactory(
                        "openai",
                        null,
                        "gpt-x",
                        "http://my.proxy/v1/chat/completions",
                        env(Map.of("OPENAI_API_KEY", "k")))
                .build();
        assertTrue(p instanceof OpenAINlAskProvider);
    }

    @Test
    public void caseInsensitiveProviderName() {
        NlAskProvider p = new NlAskProviderFactory("Anthropic", "k", null, null, env(Map.of())).build();
        assertTrue(p instanceof AnthropicNlAskProvider);
    }

    /* ---- saiku#1431 Azure OpenAI adapter ---- */

    @Test
    public void azureOpenAiWithoutKeyFallsBackToNoop() {
        NlAskProvider p = new NlAskProviderFactory("azure-openai", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void azureOpenAiWithoutEndpointFallsBackToNoop() {
        // Azure has no default endpoint — the deployment URL is per-resource and per-deployment.
        // A misconfiguration where the key is set but the endpoint isn't must not silently emit
        // to OpenAI's default host on the wrong auth header.
        NlAskProvider p = new NlAskProviderFactory(
                        "azure-openai", null, null, null, env(Map.of("AZURE_OPENAI_API_KEY", "k")))
                .build();
        assertTrue(p instanceof NoopNlAskProvider);
    }

    @Test
    public void azureOpenAiUsesExplicitKey() {
        NlAskProvider p = new NlAskProviderFactory(
                        "azure-openai",
                        "azure-key",
                        "my-deployment",
                        "https://my-resource.openai.azure.com/openai/deployments/my-deployment/chat/completions?api-version=2024-02-15-preview",
                        env(Map.of()))
                .build();
        assertTrue(p instanceof AzureOpenAiNlAskProvider);
    }

    @Test
    public void azureOpenAiFallsBackToEnvKey() {
        NlAskProvider p = new NlAskProviderFactory(
                        "azure-openai",
                        null,
                        "my-deployment",
                        "https://my-resource.openai.azure.com/openai/deployments/my-deployment/chat/completions?api-version=2024-02-15-preview",
                        env(Map.of("AZURE_OPENAI_API_KEY", "k-from-env")))
                .build();
        assertTrue(p instanceof AzureOpenAiNlAskProvider);
    }

    @Test
    public void azureOpenAiCaseInsensitive() {
        NlAskProvider p = new NlAskProviderFactory(
                        "Azure-OpenAI",
                        "k",
                        "my-deployment",
                        "https://x.openai.azure.com/openai/deployments/d/chat/completions?api-version=2024-02-15",
                        env(Map.of()))
                .build();
        assertTrue(p instanceof AzureOpenAiNlAskProvider);
    }

    /* ---- saiku#904 ollama alias ---- */

    @Test
    public void ollamaWithNoKeyStillBuildsOpenAiCompatProvider() {
        // Ollama never checks a key — this must NOT fall back to Noop like openai/anthropic do.
        NlAskProvider p = new NlAskProviderFactory("ollama", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof OpenAINlAskProvider);
    }

    @Test
    public void ollamaUsesEnvKeyWhenProvided() {
        NlAskProvider p = new NlAskProviderFactory(
                        "ollama", null, null, null, env(Map.of("OLLAMA_API_KEY", "proxy-key")))
                .build();
        assertTrue(p instanceof OpenAINlAskProvider);
    }

    @Test
    public void ollamaHonoursExplicitModelAndEndpoint() {
        String model = "llama3.1:8b-instruct-q4_K_M";
        String endpoint = "http://gpu-box:11434/v1/chat/completions";
        NlAskProvider p = new NlAskProviderFactory("ollama", null, model, endpoint, env(Map.of())).build();
        assertTrue(p instanceof OpenAINlAskProvider);
    }

    @Test
    public void ollamaCaseInsensitive() {
        NlAskProvider p = new NlAskProviderFactory("OLLAMA", null, null, null, env(Map.of())).build();
        assertTrue(p instanceof OpenAINlAskProvider);
    }

    /* ---- saiku#904 describe() — diagnostics summary, no provider construction ---- */

    @Test
    public void describeNoopWhenUnconfigured() {
        NlAskProviderFactory.Descriptor d = new NlAskProviderFactory(null, null, null, null, env(Map.of())).describe();
        assertEquals("noop", d.provider());
        assertFalse(d.configured());
    }

    @Test
    public void describeAnthropicUnconfiguredWithoutKey() {
        NlAskProviderFactory.Descriptor d =
                new NlAskProviderFactory("anthropic", null, null, null, env(Map.of())).describe();
        assertEquals("anthropic", d.provider());
        assertFalse(d.configured());
    }

    @Test
    public void describeAnthropicConfiguredWithKey() {
        NlAskProviderFactory.Descriptor d =
                new NlAskProviderFactory("anthropic", "sk-x", null, null, env(Map.of())).describe();
        assertEquals("anthropic", d.provider());
        assertTrue(d.configured());
        assertEquals(AnthropicNlAskProvider.DEFAULT_MODEL, d.model());
    }

    @Test
    public void describeOllamaAlwaysConfiguredWithDefaults() {
        NlAskProviderFactory.Descriptor d =
                new NlAskProviderFactory("ollama", null, null, null, env(Map.of())).describe();
        assertEquals("ollama", d.provider());
        assertTrue(d.configured());
        assertEquals(NlAskProviderFactory.DEFAULT_OLLAMA_MODEL, d.model());
        assertEquals(NlAskProviderFactory.DEFAULT_OLLAMA_ENDPOINT, d.endpoint());
    }

    @Test
    public void describeOllamaHonoursOverrides() {
        String endpoint = "http://gpu-box:11434/v1/chat/completions";
        NlAskProviderFactory.Descriptor d =
                new NlAskProviderFactory("ollama", null, "custom-model", endpoint, env(Map.of())).describe();
        assertEquals("custom-model", d.model());
        assertEquals(endpoint, d.endpoint());
    }

    @Test
    public void describeUnknownProviderIsNotConfigured() {
        NlAskProviderFactory.Descriptor d =
                new NlAskProviderFactory("badger", null, null, null, env(Map.of())).describe();
        assertFalse(d.configured());
    }

    private static java.util.function.Function<String, String> env(Map<String, String> map) {
        return map::get;
    }
}
