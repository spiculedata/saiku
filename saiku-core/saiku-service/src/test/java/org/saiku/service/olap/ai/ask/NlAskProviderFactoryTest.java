/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/** Unit tests for {@link NlAskProviderFactory}. */
public class NlAskProviderFactoryTest {

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

    private static java.util.function.Function<String, String> env(Map<String, String> map) {
        return map::get;
    }
}
