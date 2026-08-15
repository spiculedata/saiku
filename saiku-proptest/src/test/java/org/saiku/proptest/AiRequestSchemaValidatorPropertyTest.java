/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.saiku.service.olap.ai.AiRequestSchemaValidator;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Property-based tests for {@link AiRequestSchemaValidator} — the JSON-Schema gate on
 * {@code POST /ai/query} bodies.
 *
 * <p>Every request here arrives from an autonomous agent, which makes two things matter more than
 * usual. It must be <b>total</b>: an agent will eventually send something shaped in a way nobody
 * anticipated, and that has to become a typed 400 rather than a 500 with a stack trace. And its
 * errors must be <b>self-correctable</b>: the agent's whole recovery strategy is reading
 * {@code field} and retrying, so an error naming no field is a loop that never terminates.
 */
class AiRequestSchemaValidatorPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiRequestSchemaValidator VALIDATOR = new AiRequestSchemaValidator();

    /**
     * A minimal body the schema accepts. {@code cube} is an OBJECT of four required coordinates and
     * each measure is an object with a {@code name} — not the bare strings the wire-level
     * AiQueryRequest also tolerates.
     */
    private static ObjectNode validBody(String cubeName, List<String> measures) {
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode cube = body.putObject("cube");
        cube.put("connectionName", "conn");
        cube.put("catalog", "cat");
        cube.put("schema", "sch");
        cube.put("cubeName", cubeName);
        ArrayNode ms = body.putArray("measures");
        for (String m : measures) {
            ms.addObject().put("name", m);
        }
        return body;
    }

    /** A conforming request is accepted, whatever the (string) names inside it. */
    @HegelTest
    void conformingRequestsAreAccepted(TestCase tc) {
        String cube = tc.draw(fromRegex("[A-Za-z][A-Za-z0-9 /_-]{0,25}"), "cube");
        int measureCount = tc.draw(integers().min(1).max(4), "measureCount");

        List<String> measures = new java.util.ArrayList<>();
        for (int i = 0; i < measureCount; i++) {
            measures.add(tc.draw(fromRegex("[A-Za-z][A-Za-z0-9 ]{0,15}"), "measure" + i));
        }

        assertDoesNotThrow(() -> VALIDATOR.assertValid(validBody(cube, measures)));
    }

    /** A null body is a typed rejection naming the body, not an NPE. */
    @HegelTest
    void aNullBodyIsTypedNotAnNpe(TestCase tc) {
        AiValidationException e = assertThrows(AiValidationException.class, () -> VALIDATOR.assertValid(null));

        assertFalse(e.getField() == null || e.getField().isBlank(), "the null-body rejection named no field");
    }

    /**
     * THE property. Every rejection names a field, because that is the agent's entire
     * self-correction mechanism — an error without one is an infinite retry loop.
     */
    @HegelTest
    void everyRejectionNamesAFieldTheAgentCanFix(TestCase tc) {
        // Bodies that are structurally wrong in assorted ways.
        int shape = tc.draw(integers().min(0).max(5), "shape");
        ObjectNode body = MAPPER.createObjectNode();
        switch (shape) {
            case 0 -> {
                /* empty object — cube missing */
            }
            case 1 -> body.put("cube", 42); // wrong type (must be an object)
            case 2 -> {
                body.set("cube", validBody("C", List.of("M")).get("cube"));
                body.put("measures", "not-an-array");
            }
            case 3 -> {
                body.set("cube", validBody("C", List.of("M")).get("cube"));
                body.putArray("measures").add(7); // wrong element type
            }
            case 4 -> {
                body.set("cube", validBody("C", List.of("M")).get("cube"));
                body.set("measures", validBody("C", List.of("M")).get("measures"));
                body.put("limit", "not-a-number");
            }
            default -> body.putNull("cube");
        }
        tc.note(body.toString());

        try {
            VALIDATOR.assertValid(body);
        } catch (AiValidationException e) {
            assertFalse(
                    e.getField() == null || e.getField().isBlank(),
                    "a rejection named no field, so the agent cannot self-correct: " + body);
        }
    }

    /**
     * Total over arbitrary JSON. An agent will eventually post something nobody anticipated; that
     * must be a typed 400, never an unchecked failure surfacing as a 500.
     */
    @HegelTest
    void validationIsTotalOverArbitraryJson(TestCase tc) {
        String json = tc.draw(
                sampledFrom(List.of(
                        "{}",
                        "[]",
                        "null",
                        "true",
                        "42",
                        "\"a string\"",
                        "{\"cube\":{}}",
                        "{\"cube\":[]}",
                        "{\"measures\":{}}",
                        "{\"cube\":{},\"measures\":[[]]}",
                        "{\"cube\":{},\"rows\":[{\"hierarchy\":42}]}",
                        "{\"unknown\":\"field\"}")),
                "json");
        tc.note(json);

        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception notJson) {
            return;
        }

        try {
            VALIDATOR.assertValid(node);
        } catch (AiValidationException expected) {
            // typed rejection is the contract
        } catch (RuntimeException unchecked) {
            fail("validator threw unchecked " + unchecked.getClass().getSimpleName() + " for: " + json);
        }
    }

    /** Deeply nested junk doesn't blow the stack or escape as an unchecked failure. */
    @HegelTest
    void deeplyNestedBodiesAreHandled(TestCase tc) {
        int depth = tc.draw(integers().min(1).max(40), "depth");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("{\"a\":");
        }
        sb.append("1");
        for (int i = 0; i < depth; i++) {
            sb.append("}");
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(sb.toString());
        } catch (Exception notJson) {
            return;
        }

        try {
            VALIDATOR.assertValid(node);
        } catch (AiValidationException expected) {
            // fine
        } catch (RuntimeException unchecked) {
            fail("unchecked " + unchecked.getClass().getSimpleName() + " at depth " + depth);
        }
    }

    /** Validation is deterministic and side-effect free — the body is never modified. */
    @HegelTest
    void validationNeverMutatesTheBody(TestCase tc) {
        String cube = tc.draw(fromRegex("[A-Za-z]{1,10}"), "cube");
        ObjectNode body = validBody(cube, List.of("M"));
        String before = body.toString();

        try {
            VALIDATOR.assertValid(body);
        } catch (AiValidationException ignored) {
            // irrelevant here
        }

        org.junit.jupiter.api.Assertions.assertEquals(before, body.toString(), "the validator mutated the body");
    }
}
