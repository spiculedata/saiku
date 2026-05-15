/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

/**
 * Shape-validation contract tests for {@link AiRequestSchemaValidator}.
 * Verifies that:
 * <ul>
 *   <li>well-formed bodies pass</li>
 *   <li>missing required top-level fields (cube, measures) trip a 400 with
 *       the correct field pointer</li>
 *   <li>wrong-typed values trip a 400 (e.g. limit as string)</li>
 *   <li>JSON Pointer → dotted-array conversion produces the same field
 *       shape that the rest of the AI surface uses</li>
 * </ul>
 *
 * <p>The validator runs *before* domain-resolution (missing-measure-name,
 * unknown-dim, etc.) so these tests only assert the shape layer. Semantic
 * errors are covered by {@code AiSchemaConverterTest}.
 */
public class AiRequestSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AiRequestSchemaValidator validator;

    @Before
    public void setUp() {
        validator = new AiRequestSchemaValidator();
    }

    private JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    public void wellFormedRequestPasses() throws Exception {
        JsonNode body = parse("{"
                + "\"cube\": {"
                + "  \"connectionName\": \"foodmart\","
                + "  \"catalog\": \"FoodMart\","
                + "  \"schema\": \"FoodMart\","
                + "  \"cubeName\": \"Sales\""
                + "},"
                + "\"measures\": [{\"name\": \"Unit Sales\"}]"
                + "}");
        validator.assertValid(body);
    }

    @Test
    public void nullBodyTripsBodyField() {
        try {
            validator.assertValid(null);
            fail("expected AiValidationException");
        } catch (AiValidationException e) {
            assertEquals("body", e.getField());
        }
    }

    @Test
    public void missingCubeTripsRequiredField() throws Exception {
        JsonNode body = parse("{\"measures\": [{\"name\": \"Unit Sales\"}]}");
        try {
            validator.assertValid(body);
            fail("expected AiValidationException for missing cube");
        } catch (AiValidationException e) {
            assertNotNull(e.getField());
            assertTrue(
                    "error mentions cube as the missing field — got: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("cube"));
        }
    }

    @Test
    public void missingMeasuresTripsRequiredField() throws Exception {
        JsonNode body = parse("{"
                + "\"cube\": {"
                + "  \"connectionName\": \"foodmart\","
                + "  \"catalog\": \"FoodMart\","
                + "  \"schema\": \"FoodMart\","
                + "  \"cubeName\": \"Sales\""
                + "}"
                + "}");
        try {
            validator.assertValid(body);
            fail("expected AiValidationException for missing measures");
        } catch (AiValidationException e) {
            assertTrue(
                    "error mentions measures — got: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("measures"));
        }
    }

    @Test
    public void wrongTypeOnLimitTripsTypeError() throws Exception {
        JsonNode body = parse("{"
                + "\"cube\": {"
                + "  \"connectionName\": \"foodmart\","
                + "  \"catalog\": \"FoodMart\","
                + "  \"schema\": \"FoodMart\","
                + "  \"cubeName\": \"Sales\""
                + "},"
                + "\"measures\": [{\"name\": \"Unit Sales\"}],"
                + "\"limit\": \"five\""
                + "}");
        try {
            validator.assertValid(body);
            fail("expected AiValidationException for wrong-typed limit");
        } catch (AiValidationException e) {
            assertEquals(
                    "field points at limit (no array indices since limit is scalar) — got: " + e.getField(),
                    "limit",
                    e.getField());
        }
    }

    /**
     * Verifies the JSON Pointer → dotted-array transformation. The AI
     * surface's other error envelopes use {@code measures[].name},
     * {@code filters[0].op}, {@code rows[].members[]} style — schema
     * validator errors must use the same convention so agents have one
     * recovery shape.
     */
    @Test
    public void jsonPointerCollapsesArrayIndices() {
        assertEquals("body", AiRequestSchemaValidator.jsonPointerToField(null));
        assertEquals("body", AiRequestSchemaValidator.jsonPointerToField(""));
        assertEquals("body", AiRequestSchemaValidator.jsonPointerToField("$"));
        assertEquals("cube", AiRequestSchemaValidator.jsonPointerToField("$.cube"));
        assertEquals("measures[].name", AiRequestSchemaValidator.jsonPointerToField("$.measures[0].name"));
        assertEquals("filters[].members[]", AiRequestSchemaValidator.jsonPointerToField("$.filters[2].members[7]"));
        assertEquals("limit", AiRequestSchemaValidator.jsonPointerToField("$.limit"));
    }
}
