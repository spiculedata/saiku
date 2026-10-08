/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * True per-token streaming (saiku#1484): the decoders that fold each provider's event stream back
 * into a buffered response body while handing the model's prose downstream as it is written.
 *
 * <p>No HTTP here — the shared pump {@link AbstractNlAskProvider#consumeStream} takes an
 * {@link java.util.Iterator} of SSE lines, so a fixture transcript is all a test needs. The
 * interesting invariants are that (a) the assembled body parses to exactly what a buffered call
 * would have returned, and (b) nothing a user shouldn't see (half a JSON query) reaches the sink.
 */
public class NlAskStreamingTest {

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "cat", "sch", "Sales");

    /** Records the callbacks in arrival order so a test can assert the sequence, not just the sum. */
    private static final class RecordingListener implements NlAskStreamListener {
        final List<String> models = new ArrayList<>();
        final List<String> tools = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();
        boolean failOnDelta;

        @Override
        public void onModel(String model) {
            models.add(model);
        }

        @Override
        public void onToolSelected(String toolName) {
            tools.add(toolName);
        }

        @Override
        public void onDelta(String delta) throws IOException {
            if (failOnDelta) {
                throw new IOException("client hung up");
            }
            deltas.add(delta);
        }

        String prose() {
            return String.join("", deltas);
        }
    }

    private static final class FixedClock implements java.util.function.LongSupplier {
        long now;
        private final long step;

        FixedClock() {
            this(1_000_000L);
        }

        FixedClock(long step) {
            this.step = step;
        }

        @Override
        public long getAsLong() {
            long value = now;
            now += step;
            return value;
        }
    }

    // ---------- helpers: transcript builders ----------

    private static List<String> anthropicStream(String model, String toolName, String toolId, String toolInput) {
        List<String> lines = new ArrayList<>();
        lines.add("event: message_start");
        lines.add("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"" + model
                + "\",\"usage\":{\"input_tokens\":120}}}");
        lines.add("");
        lines.add("event: content_block_start");
        lines.add(
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\""
                        + toolId + "\",\"name\":\"" + toolName + "\",\"input\":{}}}");
        lines.add("");
        for (String fragment : fragments(toolInput, 12)) {
            lines.add("event: content_block_delta");
            lines.add(
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":"
                            + quote(fragment) + "}}");
            lines.add("");
        }
        lines.add("event: content_block_stop");
        lines.add("data: {\"type\":\"content_block_stop\",\"index\":0}");
        lines.add("");
        lines.add("event: message_delta");
        lines.add(
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":77}}");
        lines.add("");
        lines.add("event: message_stop");
        lines.add("data: {\"type\":\"message_stop\"}");
        lines.add("");
        return lines;
    }

    private static List<String> openAiStream(String model, String toolName, String toolCallId, String toolInput) {
        return openAiStream(model, toolName, toolCallId, toolInput, false);
    }

    private static List<String> openAiStream(
            String model, String toolName, String toolCallId, String toolInput, boolean repeatName) {
        List<String> lines = new ArrayList<>();
        // The role arrives on its own chunk, before any tool_call fragment — as OpenAI sends it.
        lines.add("data: {\"id\":\"chatcmpl-1\",\"model\":\"" + model
                + "\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}");
        boolean first = true;
        for (String fragment : fragments(toolInput, 12)) {
            String name = first || repeatName ? toolName : "";
            lines.add("data: {\"id\":\"chatcmpl-1\",\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"delta\":"
                    + toolCallFragment(name, first ? toolCallId : null, fragment) + ",\"finish_reason\":null}]}");
            first = false;
        }
        lines.add("data: {\"id\":\"chatcmpl-1\",\"model\":\"" + model
                + "\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        lines.add("data: [DONE]");
        lines.add("");
        return lines;
    }

    /** The name (and the call id) ride on the first fragment, the way OpenAI sends them. */
    private static String toolCallFragment(String toolName, String toolCallId, String arguments) {
        StringBuilder b = new StringBuilder("{\"tool_calls\":[{\"index\":0");
        if (toolCallId != null) {
            b.append(",\"id\":\"").append(toolCallId).append('"');
        }
        b.append(",\"type\":\"function\",\"function\":{");
        if (toolName != null && !toolName.isEmpty()) {
            b.append("\"name\":\"").append(toolName).append("\",");
        }
        b.append("\"arguments\":").append(quote(arguments)).append("}}]}");
        return b.toString();
    }

    /** Chop {@code s} into pieces of ~{@code size} chars — the shape a provider's deltas arrive in. */
    private static List<String> fragments(String s, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += size) {
            out.add(s.substring(i, Math.min(s.length(), i + size)));
        }
        return out;
    }

    /** JSON-escape a raw fragment so it can sit inside the fixture's data payload. */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static String pump(AbstractNlAskProvider provider, List<String> lines, NlAskStreamListener listener)
            throws IOException {
        return AbstractNlAskProvider.consumeStream(
                lines.iterator(), provider.streamDecoder(), "m", listener, new FixedClock(), 0L);
    }

    // ---------- Anthropic ----------

    @Test
    public void anthropicInsightStreamsItsProseAndStillParses() throws Exception {
        String markdown = "Store Sales trended up 12% week-on-week.\nThe Northeast led the growth.";
        String toolInput = "{\"markdown\":" + quote(markdown) + ",\"headline\":\"Sales up\"}";
        RecordingListener listener = new RecordingListener();

        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        String assembled =
                pump(provider, anthropicStream("claude-sonnet-4-6", "emit_insight", "toolu_1", toolInput), listener);

        assertEquals("model announced once: " + listener.models, List.of("claude-sonnet-4-6"), listener.models);
        assertEquals(List.of("emit_insight"), listener.tools);
        assertEquals("every character of prose is delivered, once", markdown, listener.prose());
        assertTrue("deltas arrive in pieces, not one blob: " + listener.deltas, listener.deltas.size() > 3);

        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(assembled, "claude-sonnet-4-6");
        assertFalse(resp.degraded());
        assertEquals(NlAskResponse.Kind.INSIGHT, resp.kind());
        assertEquals(120, resp.inputTokens());
        assertEquals(77, resp.outputTokens());
        assertTrue(resp.payloadJson().contains("Sales up"));
    }

    @Test
    public void anthropicQueryStreamsNoProseButKeepsTheWholePayload() throws Exception {
        String toolInput = "{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[\"Store Sales\"],\"limit\":10}";
        RecordingListener listener = new RecordingListener();

        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        String assembled =
                pump(provider, anthropicStream("claude-sonnet-4-6", "emit_query", "toolu_9", toolInput), listener);

        assertEquals("a half-built query is never shown to a user", List.of(), listener.deltas);
        assertEquals(List.of("emit_query"), listener.tools);

        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(assembled, "claude-sonnet-4-6");
        assertEquals(NlAskResponse.Kind.QUERY, resp.kind());
        assertEquals("tool_use id survives the round trip", "toolu_9", resp.toolCallId());
        assertEquals(toolInput, resp.payloadJson());
    }

    @Test
    public void anthropicEmailDraftStreamsItsSummary() throws Exception {
        String summary = "Q4 revenue closed at 4.1M, up 9% on Q3.";
        String toolInput = "{\"summary\":" + quote(summary) + "}";
        RecordingListener listener = new RecordingListener();

        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        String assembled = pump(
                provider, anthropicStream("claude-sonnet-4-6", "emit_email_draft", "toolu_2", toolInput), listener);

        assertEquals(summary, listener.prose());
        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(assembled, "claude-sonnet-4-6");
        assertEquals(NlAskResponse.Kind.EMAIL_DRAFT, resp.kind());
    }

    @Test
    public void refusalToolStreamsNoProse() throws Exception {
        RecordingListener listener = new RecordingListener();
        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        String assembled = pump(
                provider,
                anthropicStream(
                        "claude-sonnet-4-6", "refuse_off_topic", "toolu_3", "{\"reason\":\"Not about the cube.\"}"),
                listener);

        assertEquals(List.of(), listener.deltas);
        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(assembled, "claude-sonnet-4-6");
        assertTrue("refusal degrades: " + resp.reason(), resp.degraded());
        assertTrue(resp.reason().startsWith("OFF_TOPIC: "));
    }

    // ---------- OpenAI ----------

    @Test
    public void openAiInsightStreamsItsProseAndStillParses() throws Exception {
        String markdown = "Store Sales trended up 12% week-on-week.";
        String toolInput = "{\"markdown\":" + quote(markdown) + "}";
        RecordingListener listener = new RecordingListener();

        AbstractNlAskProvider provider = new OpenAINlAskProvider(OpenAINlAskProvider.Config.of("k"));
        String assembled = pump(provider, openAiStream("gpt-4o-mini", "emit_insight", "call_1", toolInput), listener);

        assertEquals(List.of("gpt-4o-mini"), listener.models);
        assertEquals(List.of("emit_insight"), listener.tools);
        assertEquals(markdown, listener.prose());

        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(assembled, "gpt-4o-mini");
        assertFalse(resp.degraded());
        assertEquals(NlAskResponse.Kind.INSIGHT, resp.kind());
        assertEquals(toolInput, resp.payloadJson());
    }

    @Test
    public void openAiQueryStreamsNoProseButKeepsTheWholePayload() throws Exception {
        String toolInput = "{\"cube\":{\"cubeName\":\"Sales\"},\"rows\":[\"Region\"]}";
        RecordingListener listener = new RecordingListener();

        AbstractNlAskProvider provider = new OpenAINlAskProvider(OpenAINlAskProvider.Config.of("k"));
        String assembled = pump(provider, openAiStream("gpt-4o-mini", "emit_query", "call_2", toolInput), listener);

        assertEquals(List.of(), listener.deltas);
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(assembled, "gpt-4o-mini");
        assertEquals(NlAskResponse.Kind.QUERY, resp.kind());
        assertEquals(toolInput, resp.payloadJson());
    }

    @Test
    public void azureInheritsOpenAiStreaming() throws Exception {
        String toolInput = "{\"markdown\":\"Sales up.\"}";
        RecordingListener listener = new RecordingListener();
        OpenAINlAskProvider provider = new AzureOpenAiNlAskProvider(
                new OpenAINlAskProvider.Config("k", "gpt-4o", "https://example.invalid/chat", 0.0, 512, null));
        String assembled = pump(provider, openAiStream("gpt-4o", "emit_insight", "call_3", toolInput), listener);
        assertEquals("Sales up.", listener.prose());
        assertEquals(
                NlAskResponse.Kind.INSIGHT,
                OpenAINlAskProvider.parseToolResponse(assembled, "gpt-4o").kind());
    }

    // ---------- escapes, framing and failure modes ----------

    @Test
    public void proseEscapesAreDecodedEvenWhenSplitAcrossFragments() {
        ProseDeltaScanner scanner = new ProseDeltaScanner();
        // Split in the middle of a newline escape, a quote escape and a unicode escape.
        String json = "{\"markdown\":\"line1\\nline2 \\\"quoted\\\" caf\\u00e9\"}";
        StringBuilder prose = new StringBuilder();
        for (int i = 0; i < json.length(); i += 3) {
            prose.append(scanner.accept(json.substring(i, Math.min(json.length(), i + 3))));
        }
        assertEquals("line1\nline2 \"quoted\" café", prose.toString());
        assertEquals(json, scanner.raw());
    }

    @Test
    public void scannerIgnoresStructuredFieldsAndIncompleteEscapes() {
        ProseDeltaScanner scanner = new ProseDeltaScanner();
        assertEquals("", scanner.accept("{\"cube\":{\"cubeName\":\"Sa"));
        assertEquals("", scanner.accept("les\"},\"rows\":[\"Region\"]}"));
        // A trailing lone backslash isn't decoded until the rest of its escape arrives.
        assertEquals("a", scanner.accept("{\"markdown\":\"a"));
        assertEquals("", scanner.accept("\\"));
        assertEquals("\nb", scanner.accept("nb"));
    }

    @Test
    public void nonSseBodyIsPassedThroughUnchanged() throws Exception {
        // An OpenAI-compatible gateway that ignores stream:true answers with the buffered body.
        String buffered = "{\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"emit_insight\","
                + "\"arguments\":\"{\\\"markdown\\\":\\\"Sales up.\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        RecordingListener listener = new RecordingListener();
        AbstractNlAskProvider provider = new OpenAINlAskProvider(OpenAINlAskProvider.Config.of("k"));

        String assembled = pump(provider, List.of(buffered), listener);

        assertEquals(buffered, assembled.trim());
        assertEquals(
                NlAskResponse.Kind.INSIGHT,
                OpenAINlAskProvider.parseToolResponse(assembled, "m").kind());
    }

    @Test
    public void midStreamProviderErrorDegrades() {
        List<String> lines = List.of(
                "event: error",
                "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
        RecordingListener listener = new RecordingListener();
        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        try {
            pump(provider, lines, listener);
            org.junit.Assert.fail("a mid-stream error event must abort the stream");
        } catch (AbstractNlAskProvider.StreamAbort abort) {
            assertTrue(abort.response().degraded());
            assertTrue(abort.response().reason().contains("Overloaded"));
        } catch (IOException e) {
            org.junit.Assert.fail("unexpected IOException: " + e);
        }
    }

    @Test
    public void consumerFailureDegradesRatherThanPropagating() {
        List<String> lines =
                anthropicStream("claude-sonnet-4-6", "emit_insight", "toolu_1", "{\"markdown\":\"Sales up.\"}");
        RecordingListener listener = new RecordingListener();
        listener.failOnDelta = true;
        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        try {
            pump(provider, lines, listener);
            org.junit.Assert.fail("a dead consumer must abort the stream");
        } catch (AbstractNlAskProvider.StreamAbort abort) {
            assertTrue(abort.response().degraded());
            assertEquals("stream consumer failed", abort.response().reason());
        } catch (IOException e) {
            org.junit.Assert.fail("unexpected IOException: " + e);
        }
    }

    @Test
    public void budgetExhaustionDegradesAsTimeout() {
        List<String> lines =
                anthropicStream("claude-sonnet-4-6", "emit_insight", "toolu_1", "{\"markdown\":\"Sales up.\"}");
        RecordingListener listener = new RecordingListener();
        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        try {
            // The clock advances a millisecond per read, so this budget is gone by the second event.
            AbstractNlAskProvider.consumeStream(
                    lines.iterator(), provider.streamDecoder(), "m", listener, new FixedClock(), 500_000L);
            org.junit.Assert.fail("an exhausted budget must abort the stream");
        } catch (AbstractNlAskProvider.StreamAbort abort) {
            assertEquals("provider stream timed out", abort.response().reason());
        } catch (IOException e) {
            org.junit.Assert.fail("unexpected IOException: " + e);
        }
    }

    @Test
    public void gatewayRepeatingTheFunctionNameStillResolvesTheTool() throws Exception {
        String toolInput = "{\"markdown\":\"Sales up.\"}";
        RecordingListener listener = new RecordingListener();
        AbstractNlAskProvider provider = new OpenAINlAskProvider(OpenAINlAskProvider.Config.of("k"));
        String assembled =
                pump(provider, openAiStream("gpt-4o-mini", "emit_insight", "call_9", toolInput, true), listener);

        assertEquals(List.of("emit_insight"), listener.tools);
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(assembled, "gpt-4o-mini");
        assertEquals(NlAskResponse.Kind.INSIGHT, resp.kind());
        assertEquals("Sales up.", listener.prose());
    }

    @Test
    public void commentsAndEventLinesAreIgnored() throws Exception {
        String toolInput = "{\"markdown\":\"Sales up.\"}";
        List<String> lines = new ArrayList<>();
        lines.add(": keep-alive");
        lines.add("event: content_block_start");
        lines.add(
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"emit_insight\",\"input\":{}}}");
        lines.add(
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":"
                        + quote(toolInput) + "}}");
        lines.add("data: [DONE]");
        lines.add("");
        RecordingListener listener = new RecordingListener();
        AbstractNlAskProvider provider = new AnthropicNlAskProvider(AnthropicNlAskProvider.Config.of("k"));
        String assembled = pump(provider, lines, listener);
        assertEquals("Sales up.", listener.prose());
        assertEquals(
                NlAskResponse.Kind.INSIGHT,
                AnthropicNlAskProvider.parseToolResponse(assembled, "m").kind());
    }

    // ---------- the default (non-streaming) provider path ----------

    @Test
    public void defaultProviderReplaysFinishedProseWordByWord() {
        NlAskResponse insight = NlAskResponse.okInsight("{\"markdown\":\"Store sales up 12%.\"}", "m", 10, 5);
        NlAskProvider provider = new StubProvider(insight);
        RecordingListener listener = new RecordingListener();

        NlAskResponse returned = provider.askStreaming(new NlAskRequest(CUBE, "q", "{}", "{}", List.of()), listener);

        assertEquals(insight, returned);
        assertEquals(List.of("m"), listener.models);
        assertEquals(List.of("emit_insight"), listener.tools);
        assertEquals("Store ", listener.deltas.get(0));
        assertEquals("concatenating the deltas rebuilds the prose", "Store sales up 12%.", listener.prose());
    }

    @Test
    public void defaultProviderEmitsNothingForAStructuredTool() {
        NlAskProvider provider = new StubProvider(NlAskResponse.okQuery("{\"cube\":{}}", "m", 1, 1));
        RecordingListener listener = new RecordingListener();
        provider.askStreaming(new NlAskRequest(CUBE, "q", "{}", "{}", List.of()), listener);
        assertEquals(List.of("emit_query"), listener.tools);
        assertEquals(List.of(), listener.deltas);
    }

    @Test
    public void defaultProviderEmitsNothingForADegradedResponse() {
        NlAskProvider provider = new StubProvider(NlAskResponse.degraded("HTTP 503: nope", "m"));
        RecordingListener listener = new RecordingListener();
        provider.askStreaming(new NlAskRequest(CUBE, "q", "{}", "{}", List.of()), listener);
        assertEquals(List.of(), listener.models);
        assertEquals(List.of(), listener.deltas);
    }

    @Test
    public void kindForToolMapsOnlyTheToolsClientsKnow() {
        assertEquals(NlAskResponse.Kind.QUERY, NlAskStreamListener.kindForTool("emit_query"));
        assertEquals(NlAskResponse.Kind.INSIGHT, NlAskStreamListener.kindForTool("emit_insight"));
        assertEquals(NlAskResponse.Kind.EMAIL_DRAFT, NlAskStreamListener.kindForTool("emit_email_draft"));
        assertEquals(NlAskResponse.Kind.VIEW_CHANGE, NlAskStreamListener.kindForTool("emit_view_change"));
        assertEquals(NlAskResponse.Kind.DASHBOARD, NlAskStreamListener.kindForTool("emit_dashboard"));
        assertEquals(null, NlAskStreamListener.kindForTool("refuse_off_topic"));
        assertEquals(null, NlAskStreamListener.kindForTool("something_new"));
        assertEquals(null, NlAskStreamListener.kindForTool(null));
    }

    /** A provider that only implements the buffered call — the {@code askStreaming} default. */
    private static final class StubProvider implements NlAskProvider {
        private final NlAskResponse response;

        StubProvider(NlAskResponse response) {
            this.response = response;
        }

        @Override
        public NlAskResponse ask(NlAskRequest request) {
            return response;
        }
    }
}
