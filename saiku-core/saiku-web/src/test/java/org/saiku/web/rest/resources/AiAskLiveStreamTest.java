/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.olap.ai.ask.NlAskResponse;
import org.saiku.service.olap.ai.ask.NlAskStreamListener;

/**
 * Wire-level tests for true per-token LLM streaming (saiku#1484).
 *
 * <p>What matters here is not that tokens arrive (the decoders are covered in
 * {@code NlAskStreamingTest}) but that the SSE contract is unchanged while they do: {@code model}
 * still comes first, {@code intent} still lands before the first {@code chunk}, the deltas still
 * concatenate into exactly the prose the terminal envelope carries, and nothing is emitted twice
 * because the outcome writer no longer replays what already streamed.
 */
public class AiAskLiveStreamTest {

    private static final AiCubeRef CUBE = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");

    private static final String MARKDOWN = "Store Sales trended up 12% week-on-week.";

    private AiQueryResource resource;
    private AiSchema schema;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
    }

    private void wire(NlAskProvider provider) {
        resource.setAskService(new AiAskService(ref -> schema, provider));
    }

    private static AiAskApi.AskRequest body() {
        AiAskApi.AskRequest b = new AiAskApi.AskRequest();
        b.setQuestion("what is interesting?");
        b.setCube(CUBE);
        return b;
    }

    private static String drain(Response resp) throws Exception {
        assertTrue("streaming endpoint returns a StreamingOutput", resp.getEntity() instanceof StreamingOutput);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((StreamingOutput) resp.getEntity()).write(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The prose the client reconstructs by concatenating every {@code chunk} delta, in order. */
    private static String concatenatedDeltas(String frame) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder prose = new StringBuilder();
        for (String line : frame.split("\n")) {
            if (line.startsWith("data: {\"delta\":")) {
                try {
                    prose.append(mapper.readValue(line.substring("data: ".length()), String.class));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new AssertionError("unparseable chunk payload: " + line, e);
                }
            }
        }
        return prose.toString();
    }

    private static List<String> eventOrder(String frame) {
        List<String> events = new ArrayList<>();
        for (String line : frame.split("\n")) {
            if (line.startsWith("event: ")) {
                events.add(line.substring("event: ".length()));
            }
        }
        return events;
    }

    /**
     * A provider that streams for real: it announces the model, the tool, and the prose in pieces,
     * exactly as a wire decoder would. The buffered {@link #ask} returns the same response a
     * decoder would have assembled.
     */
    private static final class LiveProvider implements NlAskProvider {
        private final String payload;

        LiveProvider(String payload) {
            this.payload = payload;
        }

        @Override
        public NlAskResponse ask(NlAskRequest request) {
            return NlAskResponse.okInsight(payload, "claude-live", 12, 34);
        }

        @Override
        public NlAskResponse askStreaming(NlAskRequest request, NlAskStreamListener listener) {
            try {
                listener.onModel("claude-live");
                listener.onToolSelected("emit_insight");
                for (String piece : new String[] {"Store ", "Sales ", "trended up ", "12% week-on-week."}) {
                    listener.onDelta(piece);
                }
            } catch (java.io.IOException e) {
                // The test client is an in-memory writer, so this can't happen — but the SPI is
                // "never throw", so a failure here degrades exactly as a provider would.
                return NlAskResponse.degraded("stream consumer failed", "claude-live");
            }
            return ask(request);
        }
    }

    // ---- the live path ----

    @Test
    public void streamedTurnKeepsTheDocumentedEventOrder() throws Exception {
        wire(new LiveProvider("{\"markdown\":" + q(MARKDOWN) + "}"));
        Response resp = resource.askStream(body());
        assertEquals(200, resp.getStatus());
        String frame = drain(resp);

        assertEquals(List.of("model", "intent", "chunk", "chunk", "chunk", "chunk", "final"), eventOrder(frame));
        assertTrue("intent names the kind: " + frame, frame.contains("data: {\"kind\":\"INSIGHT\"}"));
        assertTrue("model is the one that answered: " + frame, frame.contains("claude-live"));
        assertEquals(MARKDOWN, concatenatedDeltas(frame));
        assertTrue("the terminal envelope still carries the full artefact: " + frame, frame.contains(MARKDOWN));
    }

    @Test
    public void proseIsNotEmittedTwice() throws Exception {
        wire(new LiveProvider("{\"markdown\":" + q(MARKDOWN) + "}"));
        String frame = drain(resource.askStream(body()));
        // Four streamed deltas, and not one more from the outcome writer replaying the markdown.
        assertEquals(4, eventOrder(frame).stream().filter("chunk"::equals).count());
        assertEquals(MARKDOWN, concatenatedDeltas(frame));
    }

    @Test
    public void queryIntentStreamsNoChunksAtAll() throws Exception {
        wire(new NlAskProvider() {
            @Override
            public NlAskResponse ask(NlAskRequest request) {
                return NlAskResponse.degraded("not used");
            }

            @Override
            public NlAskResponse askStreaming(NlAskRequest request, NlAskStreamListener listener) {
                try {
                    listener.onModel("claude-live");
                    listener.onToolSelected("emit_query"); // a half-built query is not user-facing prose
                } catch (java.io.IOException e) {
                    return NlAskResponse.degraded("stream consumer failed", "claude-live");
                }
                return NlAskResponse.okQuery(
                        "{\"cube\":{\"connectionName\":\"foodmart\",\"catalog\":\"FoodMart\","
                                + "\"schema\":\"FoodMart\",\"cubeName\":\"Sales\"}}",
                        "claude-live",
                        1,
                        1);
            }
        });
        String frame = drain(resource.askStream(body()));
        assertEquals(List.of("model", "intent", "final"), eventOrder(frame));
        assertTrue(frame.contains("QUERY"));
        assertEquals("no prose for a structured tool", "", concatenatedDeltas(frame));
    }

    // ---- the fallback path: a provider with no streaming transport keeps working ----

    @Test
    public void nonStreamingProviderStillProducesTheSameWireShape() throws Exception {
        // Only ask() is implemented — the default askStreaming replays the finished prose.
        wire(request -> NlAskResponse.okInsight("{\"markdown\":" + q(MARKDOWN) + "}", "claude-buffered", 5, 6));
        String frame = drain(resource.askStream(body()));

        assertEquals("model", eventOrder(frame).get(0));
        assertEquals("intent", eventOrder(frame).get(1));
        assertEquals("final", eventOrder(frame).get(eventOrder(frame).size() - 1));
        assertTrue("the replayed deltas still rebuild the prose", MARKDOWN.equals(concatenatedDeltas(frame)));
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
