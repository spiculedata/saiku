/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Pure unit test of {@link OpenAiCompatProvider#parseToolResponse(String)} — no network, no API
 * key. Exercises the JSON-shape contract between an OpenAI-compatible Chat Completions response
 * (OpenAI itself, or Ollama's OpenAI-compat shim, saiku#904) and our {@link SuggestionSet} model.
 *
 * <p>The {@code arguments} field of a tool call is, per the OpenAI wire contract, a JSON-encoded
 * STRING — not a nested object. Bodies here are assembled with Jackson so that nesting is escaped
 * correctly rather than hand-written, which is error-prone.
 */
public class OpenAiCompatProviderParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void parsesToolCallIntoSuggestionSet() throws Exception {
        String argumentsJson = "{"
                + "\"ops\":["
                + "  {\"op\":\"rename\",\"targetPath\":\"cubes/sales\",\"oldCaption\":\"sales\","
                + "   \"newCaption\":\"Sales\",\"description\":null,\"confidence\":0.9,"
                + "   \"rationale\":\"Title case\"},"
                + "  {\"op\":\"aggregator\",\"targetPath\":\"cubes/sales/measures/amount\","
                + "   \"oldAggregator\":\"SUM\",\"newAggregator\":\"AVG\","
                + "   \"confidence\":0.8,\"rationale\":\"rate suffix\"}"
                + "],"
                + "\"degraded\":false"
                + "}";
        String body = chatCompletionBody("gpt-4o-mini", 123, 45, "return_suggestions", argumentsJson);

        EnrichResponse resp = OpenAiCompatProvider.parseToolResponse(body);
        assertNotNull(resp);
        SuggestionSet set = resp.suggestions();
        assertNotNull(set);
        assertNotNull(set.ops());
        assertEquals(2, set.ops().size());
        assertFalse(set.degraded());

        SuggestionOp first = set.ops().get(0);
        assertTrue(first instanceof RenameOp);
        RenameOp rn = (RenameOp) first;
        assertEquals("cubes/sales", rn.targetPath());
        assertEquals("Sales", rn.newCaption());

        SuggestionOp second = set.ops().get(1);
        assertTrue(second instanceof AggregatorOp);
        AggregatorOp ag = (AggregatorOp) second;
        assertEquals(DraftMeasure.Aggregator.SUM, ag.oldAggregator());
        assertEquals(DraftMeasure.Aggregator.AVG, ag.newAggregator());

        assertEquals("gpt-4o-mini", resp.metadata().get("model"));
        assertEquals(123, resp.metadata().get("usage_input_tokens"));
        assertEquals(45, resp.metadata().get("usage_output_tokens"));
    }

    @Test
    public void missingToolCallReturnsDegradedEmptySet() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-2");
        root.put("model", "gpt-4o-mini");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 10);
        usage.put("completion_tokens", 5);
        ObjectNode message = root.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.put("content", "No suggestions.");

        EnrichResponse resp = OpenAiCompatProvider.parseToolResponse(MAPPER.writeValueAsString(root));
        assertNotNull(resp);
        assertNotNull(resp.suggestions());
        assertTrue(resp.suggestions().ops().isEmpty());
        assertTrue(resp.suggestions().degraded());
    }

    @Test
    public void emptyOpsListParses() throws Exception {
        String body = chatCompletionBody("llama3.1", 5, 3, "return_suggestions", "{\"ops\":[],\"degraded\":false}");
        EnrichResponse resp = OpenAiCompatProvider.parseToolResponse(body);
        assertTrue(resp.suggestions().ops().isEmpty());
        assertFalse(resp.suggestions().degraded());
    }

    /** Assembles a minimal OpenAI-compatible Chat Completions response with one tool call. */
    private static String chatCompletionBody(
            String model, int promptTokens, int completionTokens, String toolName, String argumentsJson)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-test");
        root.put("model", model);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);

        ObjectNode message = root.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.putNull("content");
        ObjectNode call = message.putArray("tool_calls").addObject();
        call.put("id", "call_1");
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", toolName);
        // The OpenAI contract encodes `arguments` as a JSON string, not a nested object — Jackson's
        // `put(String, String)` handles the escaping for us.
        function.put("arguments", argumentsJson);

        return MAPPER.writeValueAsString(root);
    }
}
