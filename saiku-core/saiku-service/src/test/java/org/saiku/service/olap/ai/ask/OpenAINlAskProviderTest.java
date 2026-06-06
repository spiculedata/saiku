/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;

/** Unit tests for {@link OpenAINlAskProvider}. No network. */
public class OpenAINlAskProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiCubeRef CUBE = new AiCubeRef("conn", "FoodMart 2009", "FoodMart", "Sales");
    private static final String SCHEMA = "{\"cubeName\":\"Sales\"}";
    private static final String REQUEST_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"cube\":{\"type\":\"object\"}}}";

    @Test
    public void requestBodyBindsFunctionParametersAndForcesToolChoice() throws Exception {
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config("k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "show sales by country", SCHEMA, REQUEST_SCHEMA, List.of());

        JsonNode root = MAPPER.readTree(provider.buildRequestBody(req));

        assertEquals("gpt-x", root.get("model").asText());
        assertEquals(1024, root.get("max_tokens").asInt());
        assertEquals("function", root.get("tool_choice").get("type").asText());
        assertEquals(
                "emit_query",
                root.get("tool_choice").get("function").get("name").asText());

        JsonNode tools = root.get("tools");
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type").asText());
        assertEquals("emit_query", tools.get(0).get("function").get("name").asText());
        assertEquals(
                "object",
                tools.get(0).get("function").get("parameters").get("type").asText());

        // System message embeds the cube schema
        JsonNode messages = root.get("messages");
        assertEquals("system", messages.get(0).get("role").asText());
        assertTrue(messages.get(0).get("content").asText().contains("FoodMart 2009"));

        // Final message is the user question
        assertEquals("user", messages.get(messages.size() - 1).get("role").asText());
        assertEquals(
                "show sales by country",
                messages.get(messages.size() - 1).get("content").asText());
    }

    @Test
    public void requestBodyKeepsHistoryBetweenSystemAndQuestion() throws Exception {
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config("k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(
                CUBE,
                "now by region",
                SCHEMA,
                REQUEST_SCHEMA,
                List.of(NlAskMessage.user("sales by country"), NlAskMessage.assistant("{...}")));

        JsonNode messages = MAPPER.readTree(provider.buildRequestBody(req)).get("messages");

        // system + 2 history + 1 final user = 4
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("sales by country", messages.get(1).get("content").asText());
        assertEquals("assistant", messages.get(2).get("role").asText());
        assertEquals("user", messages.get(3).get("role").asText());
    }

    @Test
    public void parseToolResponseExtractsArgumentsAsJson() throws Exception {
        // OpenAI returns `arguments` as a JSON-encoded STRING (not nested JSON), so the inner
        // braces need to be escaped in the wire body.
        String body = "{"
                + "\"id\":\"x\",\"model\":\"gpt-x\","
                + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":17},"
                + "\"choices\":[{"
                + "  \"message\":{"
                + "    \"role\":\"assistant\","
                + "    \"tool_calls\":[{"
                + "      \"id\":\"c1\",\"type\":\"function\","
                + "      \"function\":{\"name\":\"emit_query\","
                + "        \"arguments\":\"{\\\"cube\\\":{\\\"cubeName\\\":\\\"Sales\\\"},\\\"measures\\\":[]}\"}"
                + "    }]"
                + "  }"
                + "}]}";

        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");

        assertFalse(resp.degraded());
        assertEquals("gpt-x", resp.model());
        assertEquals(42, resp.inputTokens());
        assertEquals(17, resp.outputTokens());

        JsonNode parsed = MAPPER.readTree(resp.aiQueryRequestJson());
        assertEquals("Sales", parsed.get("cube").get("cubeName").asText());
    }

    @Test
    public void parseToolResponseDegradesWhenNoChoices() throws Exception {
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse("{\"choices\":[]}", "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("no choices", resp.reason());
    }

    @Test
    public void parseToolResponseDegradesWhenNoToolCalls() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"sorry\"}}]}";
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("no tool_calls", resp.reason());
    }

    @Test
    public void parseToolResponseDegradesWhenArgumentsBlank() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"emit_query\","
                + "\"arguments\":\"\"}}]}}]}";
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("empty tool_call arguments", resp.reason());
    }
}
