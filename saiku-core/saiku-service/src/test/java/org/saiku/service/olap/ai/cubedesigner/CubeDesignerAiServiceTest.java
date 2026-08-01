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
}
