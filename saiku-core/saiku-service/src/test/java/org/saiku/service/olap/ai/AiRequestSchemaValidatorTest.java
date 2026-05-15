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

    /** Enum violations must populate {@code available[]} so agents can
     *  self-correct without parsing the error string. Pins iter 314b /
     *  iter 344 / order-direction live-fuzz cases. */
    @Test
    public void enumViolationPopulatesAvailableList() throws Exception {
        JsonNode body = parse("{"
                + "\"cube\": {"
                + "  \"connectionName\": \"foodmart\","
                + "  \"catalog\": \"FoodMart\","
                + "  \"schema\": \"FoodMart\","
                + "  \"cubeName\": \"Sales\""
                + "},"
                + "\"measures\": [{\"name\": \"Unit Sales\"}],"
                + "\"order\": [{\"by\": \"Unit Sales\", \"direction\": \"bogus\"}]"
                + "}");
        try {
            validator.assertValid(body);
            fail("expected AiValidationException for invalid order direction");
        } catch (AiValidationException e) {
            assertEquals("order[0].direction", e.getField());
            assertTrue(
                    "available[] must contain the enum values — got: " + e.getAvailable(),
                    e.getAvailable().contains("asc") && e.getAvailable().contains("desc"));
            assertEquals("only 2 legal directions", 2, e.getAvailable().size());
        }
    }

    /** Enum violations get a Saiku-voice message that quotes the bad
     *  value back. Pre-fix the message was networknt's default
     *  "does not have a value in the enumeration [...]" — useful for
     *  humans, harder for agents to grep on. */
    @Test
    public void enumViolationProducesFriendlyMessage() throws Exception {
        JsonNode body = parse("{"
                + "\"cube\": {"
                + "  \"connectionName\": \"foodmart\","
                + "  \"catalog\": \"FoodMart\","
                + "  \"schema\": \"FoodMart\","
                + "  \"cubeName\": \"Sales\""
                + "},"
                + "\"measures\": [{\"name\": \"Unit Sales\"}],"
                + "\"rows\": [{\"dimension\": \"Time\", \"hierarchy\": \"Time\", \"level\": \"Year\"}],"
                + "\"filters\": [{\"dimension\": \"Time\", \"hierarchy\": \"Time\", \"level\": \"Year\","
                + "  \"op\": \"bogus_op\", \"members\": [\"[Time].[Time].[1997]\"]}]"
                + "}");
        try {
            validator.assertValid(body);
            fail("expected AiValidationException for unknown filter op");
        } catch (AiValidationException e) {
            assertTrue(
                    "error names the role and the bad value — got: " + e.getMessage(),
                    e.getMessage().contains("Unknown filter op") && e.getMessage().contains("bogus_op"));
            assertTrue(
                    "error lists the legal ops — got: " + e.getMessage(), e.getMessage().contains("in"));
        }
    }

    /** Required-property violations must populate {@code available[]}
     *  with the parent's required-fields list so an agent that posted
     *  a partial body can see every missing field in one round, not
     *  one at a time. */
    @Test
    public void requiredViolationPopulatesAvailableList() throws Exception {
        JsonNode body = parse("{}");
        try {
            validator.assertValid(body);
            fail("expected AiValidationException for empty body");
        } catch (AiValidationException e) {
            // First missing field surfaces as the field; the available
            // list shows the whole required set so the agent can fix
            // everything before retrying.
            assertTrue(
                    "available[] must list required top-level fields — got: " + e.getAvailable(),
                    e.getAvailable().contains("cube") && e.getAvailable().contains("measures"));
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
     * Verifies the instance-location → field-pointer transformation.
     * Numeric indices are preserved (matches the AiSchemaConverter
     * convention: {@code filters[0].op}, {@code rows[1].members[3]}) so
     * agents can identify exactly which array element failed without a
     * follow-up round-trip.
     */
    @Test
    public void jsonPointerPreservesArrayIndices() {
        assertEquals("body", AiRequestSchemaValidator.jsonPointerToField(null));
        assertEquals("body", AiRequestSchemaValidator.jsonPointerToField(""));
        assertEquals("body", AiRequestSchemaValidator.jsonPointerToField("$"));
        assertEquals("cube", AiRequestSchemaValidator.jsonPointerToField("$.cube"));
        assertEquals("measures[0].name", AiRequestSchemaValidator.jsonPointerToField("$.measures[0].name"));
        assertEquals(
                "filters[2].members[7]", AiRequestSchemaValidator.jsonPointerToField("$.filters[2].members[7]"));
        assertEquals("limit", AiRequestSchemaValidator.jsonPointerToField("$.limit"));
    }
}
