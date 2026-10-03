/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.olap.ai.ask.NlAskResponse;

/** The LLM narrator: grounding, the egress gate, and total degradation to the template (saiku#1119). */
public class NlAskDigestNarratorTest {

    private static final AiCubeRef CUBE = new AiCubeRef("FoodMart", "FoodMart", "FoodMart", "Sales");

    private static MeasureDelta delta(String label, double current, double previous) {
        return new MeasureDelta(CUBE, label, current, previous);
    }

    /** Records what it was asked and replies with whatever the test scripted. */
    private static final class ScriptedProvider implements NlAskProvider {
        final List<NlAskRequest> requests = new ArrayList<>();
        NlAskResponse response;
        boolean configured = true;

        @Override
        public NlAskResponse ask(NlAskRequest request) {
            requests.add(request);
            return response;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }
    }

    private static AiSchema simpleSchema() {
        return new AiSchema("conn/cat/schema/Sales", "Sales", "[FoodMart].[Sales]");
    }

    /**
     * The narrator has a test seam ({@link NlAskDigestNarrator.CubeSchemaSource}) alongside the
     * production AiCubeMetadataService constructor. Both are single-abstract-method types, so the
     * lambda is cast once here rather than at every call site.
     */
    private static NlAskDigestNarrator narrator(ScriptedProvider provider, AiPolicyGuard guard) {
        return NlAskDigestNarrator.forSchemaSource(provider, c -> simpleSchema(), guard);
    }

    private static ScriptedProvider insightProvider(String markdown) {
        ScriptedProvider p = new ScriptedProvider();
        p.response = NlAskResponse.okInsight(
                "{\"markdown\":\"" + markdown.replace("\"", "\\\"") + "\"}", "test-model", 10, 10);
        return p;
    }

    @Test
    public void groundsThePromptOnTheDeltasAndForcesTheInsightTool() {
        ScriptedProvider provider = insightProvider("- Units rose to 1,200\n- Margin held at 110");
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.FULL));

        List<String> bullets = narrator.narrate("Exec Overview", List.of(delta("Units", 1200d, 1000d)), 3);

        assertEquals(List.of("Units rose to 1,200", "Margin held at 110"), bullets);
        assertEquals(1, provider.requests.size());
        NlAskRequest req = provider.requests.get(0);
        assertEquals(NlAskRequest.ForceTool.INSIGHT, req.forceTool());
        // The figures the model may talk about are the ones the server computed.
        assertTrue(req.cellsetDigest().contains("1,200"));
        assertTrue(req.cellsetDigest().contains("1,000"));
        assertTrue(req.question().contains("3 bullet points"));
        assertTrue(req.question().contains("Exec Overview"));
    }

    @Test
    public void honoursTheBulletCapOnModelOutput() {
        ScriptedProvider provider = insightProvider("- one\n- two\n- three\n- four");
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.FULL));
        assertEquals(
                2,
                narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 2).size());
    }

    @Test
    public void refusesToSendAggregatesWhenEgressPolicyForbidsIt() {
        ScriptedProvider provider = insightProvider("- should never be used");
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));

        List<String> bullets = narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 3);

        // Template wording, and the provider was never called — no figure left the box.
        assertTrue(provider.requests.isEmpty());
        assertEquals("Units rose +20.0% to 1,200 (was 1,000).", bullets.get(0));
    }

    @Test
    public void anUnwiredEgressGuardFailsClosed() {
        ScriptedProvider provider = insightProvider("- should never be used");
        NlAskDigestNarrator narrator = NlAskDigestNarrator.forSchemaSource(provider, c -> simpleSchema(), null);
        narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 3);
        assertTrue(provider.requests.isEmpty());
    }

    @Test
    public void anUnconfiguredProviderSkipsTheModelEntirely() {
        ScriptedProvider provider = insightProvider("- nope");
        provider.configured = false;
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.FULL));
        List<String> bullets = narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 3);
        assertTrue(provider.requests.isEmpty());
        assertTrue(bullets.get(0).startsWith("Units rose"));
    }

    @Test
    public void aTransportFailureDegradesToTheTemplate() {
        ScriptedProvider provider = new ScriptedProvider();
        provider.response = NlAskResponse.degraded("HTTP 503: upstream", "test-model");
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.FULL));
        List<String> bullets = narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 3);
        assertEquals("Units rose +20.0% to 1,200 (was 1,000).", bullets.get(0));
    }

    @Test
    public void aRefusalOrWrongToolDegradesToTheTemplate() {
        ScriptedProvider provider = new ScriptedProvider();
        provider.response = NlAskResponse.refusal("OFF_TOPIC: no", "test-model", 1, 1);
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.FULL));
        assertTrue(narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 3)
                .get(0)
                .startsWith("Units rose"));
    }

    @Test
    public void proseWithNoBulletsDegradesToTheTemplate() {
        ScriptedProvider provider = insightProvider("I looked at the data and things seem fine.");
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.FULL));
        assertTrue(narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 3)
                .get(0)
                .startsWith("Units rose"));
    }

    @Test
    public void anUnloadableSchemaDegradesToTheTemplateRatherThanPromptingBlind() {
        ScriptedProvider provider = insightProvider("- ungrounded");
        NlAskDigestNarrator narrator = NlAskDigestNarrator.forSchemaSource(
                provider,
                c -> {
                    throw new IllegalStateException("connection refused");
                },
                new AiPolicyGuard(AiPolicy.FULL));
        List<String> bullets = narrator.narrate(null, List.of(delta("Units", 1200d, 1000d)), 3);
        assertTrue(provider.requests.isEmpty());
        assertTrue(bullets.get(0).startsWith("Units rose"));
    }

    @Test
    public void noDeltasCostsNoModelCall() {
        ScriptedProvider provider = insightProvider("- nothing to say");
        NlAskDigestNarrator narrator = narrator(provider, new AiPolicyGuard(AiPolicy.FULL));
        assertTrue(narrator.narrate(null, List.of(), 3).isEmpty());
        assertTrue(provider.requests.isEmpty());
    }

    @Test
    public void markdownBulletShapesAreAllAccepted() {
        assertEquals(
                List.of("alpha", "beta", "gamma"),
                NlAskDigestNarrator.toBullets("{\"markdown\":\"- alpha\\n* beta\\n1. gamma\"}", 3));
        assertEquals(List.of("alpha"), NlAskDigestNarrator.toBullets("{\"markdown\":\"* alpha\"}", 3));
        assertEquals(List.of("alpha"), NlAskDigestNarrator.toBullets("{\"markdown\":\"12) alpha\"}", 3));
    }

    @Test
    public void multiLineBulletsAreFlattenedToOneLine() {
        List<String> bullets = NlAskDigestNarrator.toBullets("{\"markdown\":\"- alpha\\n  continued\"}", 3);
        assertEquals(List.of("alpha"), bullets);
    }

    @Test
    public void unusableInsightPayloadYieldsNoBulletsSoTheCallerFallsBack() {
        assertTrue(NlAskDigestNarrator.toBullets(null, 3).isEmpty());
        assertTrue(NlAskDigestNarrator.toBullets("", 3).isEmpty());
        assertTrue(NlAskDigestNarrator.toBullets("not json", 3).isEmpty());
        assertTrue(NlAskDigestNarrator.toBullets("{}", 3).isEmpty());
    }

    @Test
    public void aDecimalLookingLineIsNotMistakenForANumberedBullet() {
        assertTrue(NlAskDigestNarrator.toBullets("{\"markdown\":\"- 1.5% growth\"}", 3)
                .contains("1.5% growth"));
    }

    @Test
    public void nullProviderAndSchemaSourceAreRejectedAtConstruction() {
        try {
            NlAskDigestNarrator.forSchemaSource(null, c -> simpleSchema(), null);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
