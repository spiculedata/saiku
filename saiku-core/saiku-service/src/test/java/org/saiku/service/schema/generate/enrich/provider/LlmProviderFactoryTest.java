/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.function.Function;
import org.junit.Test;

/**
 * Unit tests for {@link LlmProviderFactory}. Config-driven selection between NoopProvider and
 * AnthropicProvider; env-var fallback for the Anthropic API key is injected via a
 * {@link Function} so we don't touch the real process environment.
 */
public class LlmProviderFactoryTest {

    private static Function<String, String> emptyEnv() {
        return name -> null;
    }

    private static Function<String, String> envWith(String key, String value) {
        return name -> key.equals(name) ? value : null;
    }

    private static void assertIsNoop(LlmProvider p) {
        assertNotNull(p);
        assertTrue("expected NoopProvider, got " + p.getClass().getName(), p instanceof NoopProvider);
    }

    private static void assertIsAnthropic(LlmProvider p) {
        assertNotNull(p);
        assertTrue("expected AnthropicProvider, got " + p.getClass().getName(), p instanceof AnthropicProvider);
    }

    @Test
    public void defaultProviderIsNoop() {
        LlmProviderFactory factory = new LlmProviderFactory(null, null, null, emptyEnv());
        assertIsNoop(factory.build());
    }

    @Test
    public void blankProviderFallsBackToNoop() {
        LlmProviderFactory factory = new LlmProviderFactory("", "", "", emptyEnv());
        assertIsNoop(factory.build());
    }

    @Test
    public void unknownProviderFallsBackToNoop() {
        LlmProviderFactory factory = new LlmProviderFactory("gpt-6-turbo-ultra", "key", null, emptyEnv());
        assertIsNoop(factory.build());
    }

    @Test
    public void anthropicWithExplicitKeyYieldsAnthropicProvider() {
        LlmProviderFactory factory = new LlmProviderFactory("anthropic", "sk-test-123", null, emptyEnv());
        assertIsAnthropic(factory.build());
    }

    @Test
    public void anthropicIsCaseInsensitive() {
        LlmProviderFactory factory = new LlmProviderFactory("ANTHROPIC", "sk-test-123", null, emptyEnv());
        assertIsAnthropic(factory.build());
    }

    @Test
    public void anthropicWithoutKeyFallsBackToNoop() {
        LlmProviderFactory factory = new LlmProviderFactory("anthropic", null, null, emptyEnv());
        assertIsNoop(factory.build());
    }

    @Test
    public void anthropicBlankKeyFallsBackToNoop() {
        LlmProviderFactory factory = new LlmProviderFactory("anthropic", "   ", null, emptyEnv());
        assertIsNoop(factory.build());
    }

    @Test
    public void anthropicPlaceholderKeyFallsBackToNoop() {
        // Unresolved ${...} placeholder — treat as unset.
        LlmProviderFactory factory = new LlmProviderFactory("anthropic", "${anthropic.key}", null, emptyEnv());
        assertIsNoop(factory.build());
    }

    @Test
    public void envVarFallbackSuppliesKey() {
        LlmProviderFactory factory =
                new LlmProviderFactory("anthropic", null, null, envWith("ANTHROPIC_API_KEY", "sk-from-env"));
        assertIsAnthropic(factory.build());
    }

    @Test
    public void explicitKeyTakesPrecedenceOverEnv() {
        LlmProviderFactory factory =
                new LlmProviderFactory("anthropic", "sk-explicit", null, envWith("ANTHROPIC_API_KEY", "sk-from-env"));
        assertIsAnthropic(factory.build());
    }

    @Test
    public void explicitModelIsAccepted() {
        LlmProviderFactory factory = new LlmProviderFactory("anthropic", "sk-test-123", "claude-opus-4-7", emptyEnv());
        assertIsAnthropic(factory.build());
    }
}
