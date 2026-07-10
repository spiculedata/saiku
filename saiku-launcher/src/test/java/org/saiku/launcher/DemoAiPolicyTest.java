/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.saiku.launcher.SaikuLauncher.ServeCommand;

/**
 * Regression coverage for the demo dashboard breaking on first-boot every time — the welcome
 * dashboard's KPI + chart tiles call {@code /ai/query} for aggregated result values, but the
 * process-wide {@link org.saiku.service.olap.ai.AiPolicy} default is {@code SCHEMA_ONLY}
 * (fail-closed), so every tile renders the "AI policy 'schema-only' does not permit sending
 * AGGREGATED_RESULT_VALUES" error. This suite pins the demo-only defaulting logic so it can't
 * silently regress the next time someone edits the launcher boot sequence.
 *
 * <p>The behaviour under test:
 *
 * <ul>
 *   <li>Non-demo boots leave {@code ai.policy} untouched — production must stay fail-closed.</li>
 *   <li>Demo mode with no explicit config returns the relaxed {@code "aggregated"} default.</li>
 *   <li>An explicit {@code SAIKU_AI_POLICY} env var wins — operators pinning to a lower or
 *       higher tier keep control.</li>
 *   <li>An explicit {@code -Dai.policy=...} system property wins — same reason.</li>
 *   <li>Empty / whitespace values on either input count as unset — so a Kubernetes ConfigMap
 *       with the key present but blank still gets the relaxed default.</li>
 * </ul>
 */
public class DemoAiPolicyTest {

    @Test
    public void nonDemoBootLeavesPolicyUntouched() {
        // Production posture — fail-closed. The launcher must NOT rewrite the property.
        assertNull(ServeCommand.resolveDemoAiPolicyDefault(false, null, null));
    }

    @Test
    public void nonDemoBootLeavesPolicyUntouchedEvenWhenExplicitlySet() {
        // Confirming demo=false short-circuits before any input inspection.
        assertNull(ServeCommand.resolveDemoAiPolicyDefault(false, "full", "aggregated"));
    }

    @Test
    public void demoModeWithNoConfigDefaultsToAggregated() {
        assertEquals("aggregated", ServeCommand.resolveDemoAiPolicyDefault(true, null, null));
    }

    @Test
    public void explicitEnvVarWinsOverDemoDefault() {
        assertNull(ServeCommand.resolveDemoAiPolicyDefault(true, "full", null));
        assertNull(ServeCommand.resolveDemoAiPolicyDefault(true, "schema-only", null));
    }

    @Test
    public void explicitSystemPropertyWinsOverDemoDefault() {
        assertNull(ServeCommand.resolveDemoAiPolicyDefault(true, null, "full"));
        assertNull(ServeCommand.resolveDemoAiPolicyDefault(true, null, "schema-only"));
    }

    @Test
    public void blankEnvVarCountsAsUnset() {
        // Kubernetes ConfigMap with the key present but blank — treat as absent.
        assertEquals("aggregated", ServeCommand.resolveDemoAiPolicyDefault(true, "", null));
        assertEquals("aggregated", ServeCommand.resolveDemoAiPolicyDefault(true, "   ", null));
    }

    @Test
    public void blankSystemPropertyCountsAsUnset() {
        assertEquals("aggregated", ServeCommand.resolveDemoAiPolicyDefault(true, null, ""));
        assertEquals("aggregated", ServeCommand.resolveDemoAiPolicyDefault(true, null, "   "));
    }

    @Test
    public void returnValueIsRecognisedByAiPolicyParse() {
        // The relaxed default we return must be a value AiPolicy.parse() accepts — else the
        // whole exercise ships a broken value that only surfaces at boot. Guard against that.
        String v = ServeCommand.resolveDemoAiPolicyDefault(true, null, null);
        // Exception on unknown values so the test fails loudly on drift.
        org.saiku.service.olap.ai.AiPolicy parsed = org.saiku.service.olap.ai.AiPolicy.parse(v);
        assertEquals(org.saiku.service.olap.ai.AiPolicy.AGGREGATED, parsed);
    }
}
