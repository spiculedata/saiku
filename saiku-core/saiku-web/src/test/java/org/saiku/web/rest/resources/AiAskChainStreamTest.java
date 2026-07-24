/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.AiInsight;
import org.saiku.web.rest.resources.AiQueryResource.SseWriter;

/**
 * Unit tests for {@link AiQueryResource#streamChainAsSse(AiAskService.AskChain, SseWriter)} — the
 * multi-step SSE emitter for {@code POST /ai/ask/chain/stream} (Task 7). Drives the package-visible
 * seam directly with hand-built {@link AiAskService.AskChain}s, mirroring the harness {@code
 * AiAskSpaceStreamTest} uses for the single-step {@code streamOutcomeAsSse}.
 */
public class AiAskChainStreamTest {

    private AiQueryResource resource;

    @Before
    public void setUp() {
        resource = new AiQueryResource();
    }

    private String stream(AiAskService.AskChain chain) throws Exception {
        StringWriter sw = new StringWriter();
        SseWriter sse = new SseWriter(sw);
        resource.streamChainAsSse(chain, sse);
        return sw.toString();
    }

    /** Count occurrences of an SSE event name in the raw frame. */
    private static int countEvents(String frame, String name) {
        Matcher m = Pattern.compile("event: " + name + "\\n").matcher(frame);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static AiQueryRequest queryRequest() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        return req;
    }

    @Test
    public void twoStepChainEmitsModelThenStepThenFinal() throws Exception {
        AiInsight insight = new AiInsight("Store sales trended up.", "Sales up");
        AiAskService.AskChain chain = new AiAskService.AskChain(
                List.of(
                        AiAskService.AskOutcome.ok(queryRequest(), "claude-x"),
                        AiAskService.AskOutcome.okInsight(insight, "claude-x")),
                false);

        String frame = stream(chain);

        // Exactly one `model` event, at the very front.
        assertEquals("model event fires once: " + frame, 1, countEvents(frame, "model"));
        assertTrue(frame.startsWith("event: model"));

        // Two intent events, in step order.
        assertEquals(2, countEvents(frame, "intent"));
        assertTrue("QUERY intent first: " + frame, frame.indexOf("QUERY") < frame.indexOf("INSIGHT"));

        // Intermediate step emits `step`, not `final`; only the LAST step emits `final`.
        assertEquals("only the last step emits final: " + frame, 1, countEvents(frame, "final"));
        assertEquals("the QUERY step emits `step`: " + frame, 1, countEvents(frame, "step"));

        // The QUERY step's envelope carries the request but NOT a response/queryModel — proves no
        // server-side re-execution happened for the chained step.
        int stepIdx = frame.indexOf("event: step");
        int finalIdx = frame.indexOf("event: final");
        String stepEnvelope = frame.substring(stepIdx, finalIdx);
        assertTrue("step envelope carries the request: " + stepEnvelope, stepEnvelope.contains("\"request\""));
        assertFalse("step envelope must not carry a response (no re-execution)", stepEnvelope.contains("\"response\""));
        assertFalse(
                "step envelope must not carry a queryModel (no re-execution)", stepEnvelope.contains("\"queryModel\""));

        // The final envelope carries the insight.
        String finalEnvelope = frame.substring(finalIdx);
        assertTrue("final envelope carries the insight: " + finalEnvelope, finalEnvelope.contains("\"insight\""));

        // Prose from the INSIGHT step is chunked.
        assertTrue("insight markdown chunked: " + frame, frame.contains("event: chunk"));

        // note: no step-limit hit — no `note` event.
        assertEquals(0, countEvents(frame, "note"));
    }

    @Test
    public void stepLimitChainEmitsNoteAfterFinal() throws Exception {
        AiAskService.AskChain chain =
                new AiAskService.AskChain(List.of(AiAskService.AskOutcome.ok(queryRequest(), "claude-x")), true);

        String frame = stream(chain);

        assertEquals(1, countEvents(frame, "model"));
        assertEquals(1, countEvents(frame, "intent"));
        assertEquals("single step is also the last: final, not step", 1, countEvents(frame, "final"));
        assertEquals(0, countEvents(frame, "step"));
        assertEquals("step-limit note fires: " + frame, 1, countEvents(frame, "note"));
        assertTrue("note explains the step limit: " + frame, frame.contains("step limit"));
        assertTrue("note comes after final", frame.indexOf("event: note") > frame.indexOf("event: final"));
    }

    @Test
    public void degradedTerminalStepEmitsErrorThenFinal() throws Exception {
        AiAskService.AskChain chain =
                new AiAskService.AskChain(List.of(AiAskService.AskOutcome.degraded("boom", "claude-x")), false);

        String frame = stream(chain);

        assertEquals(1, countEvents(frame, "model"));
        assertTrue("error event on degrade: " + frame, frame.contains("event: error"));
        assertEquals(1, countEvents(frame, "final"));
        assertTrue(frame.indexOf("event: final") > frame.indexOf("event: error"));
        assertTrue("final envelope is degraded: " + frame, frame.contains("\"degraded\":true"));
        // Degraded steps skip the `intent` event entirely (mirrors streamOutcomeAsSse).
        assertEquals(0, countEvents(frame, "intent"));
    }
}
