package org.saiku.service.schema.generate.enrich.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Pure unit test of {@link AnthropicProvider#parseToolResponse(String)} — no network, no API key.
 * Exercises the JSON-shape contract between the Anthropic Messages API tool-use response and our
 * {@link SuggestionSet} model.
 */
public class AnthropicProviderParseTest {

    @Test
    public void parsesToolUseIntoSuggestionSet() throws Exception {
        String body = "{"
                + "\"id\":\"msg_01\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\","
                + "\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":123,\"output_tokens\":45},"
                + "\"content\":["
                + "  {\"type\":\"text\",\"text\":\"Here are my suggestions.\"},"
                + "  {\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"return_suggestions\",\"input\":{"
                + "    \"ops\":["
                + "      {\"op\":\"rename\",\"targetPath\":\"cubes/sales\",\"oldCaption\":\"sales\","
                + "       \"newCaption\":\"Sales\",\"description\":null,\"confidence\":0.9,"
                + "       \"rationale\":\"Title case\"},"
                + "      {\"op\":\"aggregator\",\"targetPath\":\"cubes/sales/measures/amount\","
                + "       \"oldAggregator\":\"SUM\",\"newAggregator\":\"AVG\","
                + "       \"confidence\":0.8,\"rationale\":\"rate suffix\"}"
                + "    ],"
                + "    \"degraded\":false"
                + "  }}"
                + "]}";
        EnrichResponse resp = AnthropicProvider.parseToolResponse(body);
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

        assertEquals("claude-sonnet-4-6", resp.metadata().get("model"));
        assertEquals(123, resp.metadata().get("usage_input_tokens"));
        assertEquals(45, resp.metadata().get("usage_output_tokens"));
    }

    @Test
    public void missingToolUseBlockReturnsDegradedEmptySet() throws Exception {
        String body = "{"
                + "\"id\":\"msg_02\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"No suggestions.\"}]"
                + "}";
        EnrichResponse resp = AnthropicProvider.parseToolResponse(body);
        assertNotNull(resp);
        assertNotNull(resp.suggestions());
        assertTrue(resp.suggestions().ops().isEmpty());
        assertTrue(resp.suggestions().degraded());
    }

    @Test
    public void emptyOpsListParses() throws Exception {
        String body = "{"
                + "\"id\":\"msg_03\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3},"
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_2\","
                + "  \"name\":\"return_suggestions\",\"input\":{\"ops\":[],\"degraded\":false}}]"
                + "}";
        EnrichResponse resp = AnthropicProvider.parseToolResponse(body);
        assertTrue(resp.suggestions().ops().isEmpty());
        assertFalse(resp.suggestions().degraded());
    }
}
