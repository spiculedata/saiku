/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.ask.AgentSpace;
import org.saiku.service.olap.ai.ask.AgentSpaceParser;

/**
 * Parsing invariants for {@link AgentSpaceParser#parse(String, String)}, the JSON reader that builds
 * an {@link AgentSpace} persona from disk. A round-trip (canonical JSON parses back to its fields)
 * plus a totality guard (any input yields an {@code AgentSpace} or a structured
 * {@code ParseException}, never a leaked runtime exception).
 */
class AgentSpaceParserPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Generator<String> ID = fromRegex("[a-z][a-z0-9-]{0,20}");
    /** Non-blank display text; the leading alnum guarantees {@code !isBlank()}. */
    private static final Generator<String> WORD = fromRegex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,15}");

    /** Canonical JSON with one cube ref round-trips to the exact id, name, and cube coordinates. */
    @HegelTest
    void validJsonRoundTrips(TestCase tc) throws Exception {
        String id = tc.draw(ID, "id");
        String name = tc.draw(WORD, "name");
        String connection = tc.draw(WORD, "connection");
        String catalog = tc.draw(WORD, "catalog");
        String schema = tc.draw(WORD, "schema");
        String cubeName = tc.draw(WORD, "cubeName");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", id);
        root.put("name", name);
        ArrayNode allow = root.putArray("cubeAllowlist");
        ObjectNode cube = allow.addObject();
        cube.put("connectionName", connection);
        cube.put("catalog", catalog);
        cube.put("schema", schema);
        cube.put("cubeName", cubeName);
        String json = MAPPER.writeValueAsString(root);

        AgentSpace space;
        try {
            space = AgentSpaceParser.parse("test", json);
        } catch (AgentSpaceParser.ParseException e) {
            fail("canonical JSON must parse, got " + e.code() + ": " + e.getMessage());
            return;
        }

        assertEquals(id, space.id(), "id must round-trip");
        assertEquals(name, space.name(), "name must round-trip");
        assertEquals(1, space.cubeAllowlist().size(), "single cube ref must survive");
        AiCubeRef ref = space.cubeAllowlist().get(0);
        assertEquals(connection, ref.getConnectionName(), "connection must round-trip");
        assertEquals(catalog, ref.getCatalog(), "catalog must round-trip");
        assertEquals(schema, ref.getSchema(), "schema must round-trip");
        assertEquals(cubeName, ref.getCubeName(), "cubeName must round-trip");
    }

    /** For ANY input, parse returns a non-null space or throws {@code ParseException} — nothing else. */
    @HegelTest
    void parseIsTotal(TestCase tc) {
        String s = tc.draw(text(), "s");

        try {
            AgentSpace space = AgentSpaceParser.parse("test", s);
            if (space == null) {
                fail("parse returned null instead of throwing ParseException");
            }
        } catch (AgentSpaceParser.ParseException e) {
            // Expected structured failure — fine.
        } catch (Throwable t) {
            fail("non-ParseException thrown for input: " + t);
        }
    }
}
