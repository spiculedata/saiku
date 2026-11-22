/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.olap.ai.cubedesigner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.Test;

/**
 * Unit tests for the cube-designer DimSum agent-turn request shaping + response
 * parsing — the pure parts, no network. The actual Anthropic round-trip needs a
 * live API key and is out of scope here.
 */
public class CubeDesignerAiServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CubeDesignerAiService svc() {
        // A non-blank key makes isConfigured() true without touching the env.
        return new CubeDesignerAiService("test-key", "claude-test", "https://example.invalid", 60);
    }

    @Test
    public void isConfigured_trueWhenKeyPresent() {
        assertTrue(svc().isConfigured());
    }

    @Test
    public void buildRequestBody_carriesPromptTools_canvasState_andMessagesVerbatim() throws Exception {
        JsonNode messages = MAPPER.readTree("[{\"role\":\"user\",\"content\":\"draft me a cube\"}]");
        String body = svc().buildRequestBody(messages, "TABLES: sales, customer");
        JsonNode root = MAPPER.readTree(body);

        assertEquals("claude-test", root.path("model").asText());
        // System = the DimSum prompt with the per-turn canvas state appended.
        String system = root.path("system").asText();
        assertTrue("prompt", system.contains("DimSum"));
        assertTrue("canvas state appended", system.contains("TABLES: sales, customer"));
        // Tools passed through verbatim — the 16-tool Cloud contract.
        assertTrue("tools array", root.path("tools").isArray());
        assertEquals(16, root.path("tools").size());
        assertEquals("auto", root.path("tool_choice").path("type").asText());
        // Messages verbatim.
        assertEquals(messages, root.path("messages"));
    }

    @Test
    public void buildRequestBody_omitsCanvasStateWhenBlank() throws Exception {
        JsonNode messages = MAPPER.createArrayNode();
        String body = svc().buildRequestBody(messages, "   ");
        JsonNode root = MAPPER.readTree(body);
        assertTrue(root.path("system").asText().length() > 0);
        // Blank canvas summary must not append the CURRENT CANVAS STATE header.
        assertTrue(!root.path("system").asText().contains("CURRENT CANVAS STATE"));
    }

    @Test
    public void parseContent_returnsTheContentBlockArrayVerbatim() throws Exception {
        String anthropic = "{\"id\":\"msg_1\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"On it.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"add_table_to_canvas\",\"input\":{\"qualifiedName\":\"public.sales\"}}"
                + "]}";
        JsonNode content = svc().parseContent(anthropic);
        assertTrue(content.isArray());
        assertEquals(2, content.size());
        assertEquals("tool_use", content.get(1).path("type").asText());
        assertEquals("add_table_to_canvas", content.get(1).path("name").asText());
    }

    @Test
    public void parseContent_emptyArrayWhenNoContent() throws Exception {
        JsonNode content = svc().parseContent("{\"id\":\"x\"}");
        assertTrue(content.isArray());
        assertEquals(0, content.size());
    }

    /* --------------------- saiku#2489 — openai / ollama path --------------------- */

    private static CubeDesignerAiService openAiSvc(ScriptedHttpClient http) {
        return new CubeDesignerAiService(
                "openai", "sk-test", "gpt-test", "https://example.invalid/v1/chat/completions", 60, name -> null, http);
    }

    private static CubeDesignerAiService ollamaSvc(ScriptedHttpClient http) {
        return new CubeDesignerAiService("ollama", null, null, null, 60, name -> null, http);
    }

    /** Drain a request's string body publisher into a JSON tree. */
    private static JsonNode sentBody(ScriptedHttpClient http, int i) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        http.requests
                .get(i)
                .bodyPublisher()
                .orElseThrow()
                .subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    public void onNext(java.nio.ByteBuffer b) {
                        byte[] chunk = new byte[b.remaining()];
                        b.get(chunk);
                        out.writeBytes(chunk);
                    }

                    public void onError(Throwable t) {}

                    public void onComplete() {}
                });
        return MAPPER.readTree(out.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final String OPENAI_TOOL_CALL_RESPONSE =
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                    + "\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":\"On it.\","
                    + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":"
                    + "\"add_table_to_canvas\",\"arguments\":\"{\\\"qualifiedName\\\":\\\"public.sales\\\"}\"}}]}}]}";

    @Test
    public void ollama_isConfiguredWithoutAnyKey() {
        ScriptedHttpClient http = ScriptedHttpClient.of();
        CubeDesignerAiService svc = ollamaSvc(http);
        assertTrue(svc.isConfigured());
        // Keyless defaults: localhost Ollama + the broadly-available model tag.
        CubeDesignerAiService.Descriptor d = svc.describe();
        assertEquals("ollama", d.provider());
        assertEquals(CubeDesignerAiService.DEFAULT_OLLAMA_ENDPOINT, d.endpoint());
        assertEquals(CubeDesignerAiService.DEFAULT_OLLAMA_MODEL, d.model());
    }

    @Test
    public void openai_notConfiguredWithoutKey() {
        CubeDesignerAiService svc = new CubeDesignerAiService("openai", null, null, null, 60, name -> null);
        assertTrue(!svc.isConfigured());
    }

    @Test
    public void noop_isNeverConfigured_evenWithAKey() {
        CubeDesignerAiService svc = new CubeDesignerAiService("noop", "sk-test", null, null, 60, name -> null);
        assertTrue(!svc.isConfigured());
    }

    @Test
    public void anthropic_stillTheFallbackWhenProviderBlank() {
        // Blank provider + explicit key ⇒ anthropic, the pre-#2489 behaviour.
        CubeDesignerAiService svc = new CubeDesignerAiService("   ", "sk-test", null, null, 60, name -> null);
        assertTrue(svc.isConfigured());
        assertEquals("anthropic", svc.describe().provider());
    }

    @Test
    public void openaiTurn_mapsToolCallsBackToAnthropicBlocks() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.of(ScriptedHttpClient.Resp.ok(OPENAI_TOOL_CALL_RESPONSE));
        CubeDesignerAiService svc = openAiSvc(http);

        JsonNode messages = MAPPER.readTree("[{\"role\":\"user\",\"content\":\"add the sales table\"}]");
        JsonNode content = svc.turn(messages, "TABLES: sales");

        // Same shape the Anthropic path returns — text + tool_use with id/name/input.
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("On it.", content.get(0).path("text").asText());
        assertEquals("tool_use", content.get(1).path("type").asText());
        assertEquals("call_1", content.get(1).path("id").asText());
        assertEquals("add_table_to_canvas", content.get(1).path("name").asText());
        assertEquals(
                "public.sales",
                content.get(1).path("input").path("qualifiedName").asText());

        // Bearer auth + the resolved endpoint.
        assertEquals(
                "https://example.invalid/v1/chat/completions",
                http.requests.get(0).uri().toString());
        assertTrue(http.requests
                .get(0)
                .headers()
                .firstValue("authorization")
                .orElse("")
                .contains("sk-test"));
    }

    @Test
    public void openaiRequestBody_reshapesTools_systemPrompt_andCanvasState() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.of(ScriptedHttpClient.Resp.ok(OPENAI_TOOL_CALL_RESPONSE));
        CubeDesignerAiService svc = openAiSvc(http);
        JsonNode messages = MAPPER.readTree("[{\"role\":\"user\",\"content\":\"add the sales table\"}]");
        svc.turn(messages, "TABLES: sales");

        JsonNode body = sentBody(http, 0);
        assertEquals("gpt-test", body.path("model").asText());
        assertEquals("auto", body.path("tool_choice").asText());
        // System prompt (with canvas state) leads as a system message.
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertTrue(body.path("messages").get(0).path("content").asText().contains("DimSum"));
        assertTrue(body.path("messages").get(0).path("content").asText().contains("TABLES: sales"));
        // Anthropic input_schema → OpenAI parameters, one function per designer tool.
        assertEquals(16, body.path("tools").size());
        JsonNode first = body.path("tools").get(0);
        assertEquals("function", first.path("type").asText());
        assertEquals("list_tables", first.path("function").path("name").asText());
        assertTrue(first.path("function").path("parameters").isObject());
        // The caller's user message follows the system message.
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals(
                "add the sales table",
                body.path("messages").get(1).path("content").asText());
    }

    @Test
    public void toolResult_nextTurn_translatedToOpenAiToolMessage() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.of(
                ScriptedHttpClient.Resp.ok(OPENAI_TOOL_CALL_RESPONSE),
                ScriptedHttpClient.Resp.ok("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Done — the sales table is on the canvas.\"}}]}"));
        CubeDesignerAiService svc = openAiSvc(http);

        // Turn 1: the model calls a tool.
        JsonNode turn1 = MAPPER.readTree("[{\"role\":\"user\",\"content\":\"add the sales table\"}]");
        svc.turn(turn1, null);

        // Turn 2: the client sends the assistant's tool_use back with its tool_result,
        // in exactly the Anthropic shape the UI keeps in its chat history.
        JsonNode turn2 = MAPPER.readTree("[\n"
                + "  {\"role\":\"user\",\"content\":\"add the sales table\"},\n"
                + "  {\"role\":\"assistant\",\"content\":[\n"
                + "    {\"type\":\"text\",\"text\":\"On it.\"},\n"
                + "    {\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"add_table_to_canvas\",\n"
                + "     \"input\":{\"qualifiedName\":\"public.sales\"}}\n"
                + "  ]},\n"
                + "  {\"role\":\"user\",\"content\":[\n"
                + "    {\"type\":\"tool_result\",\"tool_use_id\":\"call_1\","
                + "\"content\":\"{\\\"added\\\":true,\\\"tableId\\\":\\\"t-sales\\\"}\"}\n"
                + "  ]}\n"
                + "]");
        svc.turn(turn2, null);

        JsonNode body = sentBody(http, 1);
        JsonNode msgs = body.path("messages");
        assertEquals(4, msgs.size());
        assertEquals("system", msgs.get(0).path("role").asText());
        assertEquals("user", msgs.get(1).path("role").asText());
        // The assistant's text + tool_use turn maps to content + tool_calls.
        assertEquals("assistant", msgs.get(2).path("role").asText());
        assertEquals("On it.", msgs.get(2).path("content").asText());
        assertEquals("call_1", msgs.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals(
                "add_table_to_canvas",
                msgs.get(2)
                        .path("tool_calls")
                        .get(0)
                        .path("function")
                        .path("name")
                        .asText());
        JsonNode args = MAPPER.readTree(msgs.get(2)
                .path("tool_calls")
                .get(0)
                .path("function")
                .path("arguments")
                .asText());
        assertEquals("public.sales", args.path("qualifiedName").asText());
        // The tool_result maps to a role:"tool" message carrying the same id.
        assertEquals("tool", msgs.get(3).path("role").asText());
        assertEquals("call_1", msgs.get(3).path("tool_call_id").asText());
        assertTrue(msgs.get(3).path("content").asText().contains("added"));
    }

    @Test
    public void malformedToolCall_missingIdOrName_throwsClearError() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.of(ScriptedHttpClient.Resp.ok(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
                        + "[{\"type\":\"function\",\"function\":{\"name\":\"add_table_to_canvas\","
                        + "\"arguments\":\"{}\"}}]}}]}"));
        CubeDesignerAiService svc = openAiSvc(http);
        try {
            svc.turn(MAPPER.createArrayNode(), null);
            throw new AssertionError("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("tool call"));
        }
    }

    @Test
    public void badJsonInArguments_throwsClearError() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.of(ScriptedHttpClient.Resp.ok(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"add_table_to_canvas\","
                        + "\"arguments\":\"{not json\"}}]}}]}"));
        CubeDesignerAiService svc = openAiSvc(http);
        try {
            svc.turn(MAPPER.createArrayNode(), null);
            throw new AssertionError("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("malformed arguments"));
        }
    }

    @Test
    public void noChoicesAndNoToolCalls_throwsClearError() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.of(ScriptedHttpClient.Resp.ok("{\"choices\":[]}"));
        CubeDesignerAiService svc = openAiSvc(http);
        try {
            svc.turn(MAPPER.createArrayNode(), null);
            throw new AssertionError("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("no choices"));
        }

        ScriptedHttpClient empty = ScriptedHttpClient.of(
                ScriptedHttpClient.Resp.ok("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}"));
        CubeDesignerAiService svc2 = openAiSvc(empty);
        try {
            svc2.turn(MAPPER.createArrayNode(), null);
            throw new AssertionError("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("no content or tool calls"));
        }
    }

    @Test
    public void httpError_throwsClearIoException() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.of(ScriptedHttpClient.Resp.status(500, "boom"));
        CubeDesignerAiService svc = openAiSvc(http);
        try {
            svc.turn(MAPPER.createArrayNode(), null);
            throw new AssertionError("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("HTTP 500"));
        }
    }

    @Test
    public void timeout_throwsClearIoException() throws Exception {
        ScriptedHttpClient http = ScriptedHttpClient.throwing(new java.net.http.HttpTimeoutException("timed out"));
        CubeDesignerAiService svc = openAiSvc(http);
        try {
            svc.turn(MAPPER.createArrayNode(), null);
            throw new AssertionError("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("timed out"));
        }
    }

    @Test
    public void notConfigured_turnThrowsIllegalState() {
        CubeDesignerAiService svc = new CubeDesignerAiService("openai", null, null, null, 60, name -> null);
        try {
            svc.turn(MAPPER.createArrayNode(), null);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not configured"));
        } catch (IOException e) {
            throw new AssertionError("unexpected IOException", e);
        }
    }
}
