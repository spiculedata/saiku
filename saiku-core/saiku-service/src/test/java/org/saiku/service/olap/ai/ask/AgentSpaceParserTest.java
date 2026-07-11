/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Random;
import org.junit.Test;

/**
 * Golden-path parses succeed, every documented error code has a hand-authored trigger, and the
 * fuzz test asserts no random input ever leaks a RuntimeException — same discipline as the skill
 * parser (issue #1440 acceptance criterion: malformed persona JSON must fail with a structured
 * error, not a stack trace on the REST layer).
 */
public class AgentSpaceParserTest {

    @Test
    public void parsesGoldenPath() throws Exception {
        String json = "{\"id\":\"sales-analyst\",\"name\":\"Sales Analyst\",\"description\":\"weekly rollups\","
                + "\"systemPrompt\":\"You are the Sales Analyst.\","
                + "\"cubeAllowlist\":["
                + "{\"connectionName\":\"c\",\"catalog\":\"cat\",\"schema\":\"s\",\"cubeName\":\"Sales\"}"
                + "],"
                + "\"skillAllowlist\":[\"weekly-rollup\"],"
                + "\"suggestedPrompts\":[\"Q?\",\"Q2?\"]}";
        AgentSpace space = AgentSpaceParser.parse("s.json", json);
        assertEquals("sales-analyst", space.id());
        assertEquals("Sales Analyst", space.name());
        assertEquals("weekly rollups", space.description());
        assertEquals("You are the Sales Analyst.", space.systemPrompt());
        assertEquals(1, space.cubeAllowlist().size());
        assertEquals("Sales", space.cubeAllowlist().get(0).getCubeName());
        assertEquals(1, space.skillAllowlist().size());
        assertEquals(2, space.suggestedPrompts().size());
    }

    @Test
    public void optionalFieldsAreOptional() throws Exception {
        String json = "{\"id\":\"minimal\",\"name\":\"Minimal\","
                + "\"cubeAllowlist\":["
                + "{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}";
        AgentSpace space = AgentSpaceParser.parse("s.json", json);
        assertNull(space.description());
        assertNull(space.systemPrompt());
        assertTrue(space.skillAllowlist().isEmpty());
        assertTrue(space.suggestedPrompts().isEmpty());
    }

    @Test
    public void rejectsEmpty() {
        assertParseCode("", "EMPTY_SPACE");
        assertParseCode("   ", "EMPTY_SPACE");
    }

    @Test
    public void rejectsMalformedJson() {
        assertParseCode("not json", "MALFORMED_JSON");
        assertParseCode("[\"not\", \"object\"]", "MALFORMED_JSON");
    }

    @Test
    public void rejectsMissingId() {
        assertParseCode(
                "{\"name\":\"X\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "MISSING_FIELD");
    }

    @Test
    public void rejectsMissingName() {
        assertParseCode(
                "{\"id\":\"x\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "MISSING_FIELD");
    }

    @Test
    public void rejectsInvalidId() {
        assertParseCode(
                "{\"id\":\"Has Space\",\"name\":\"X\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "INVALID_ID");
        assertParseCode(
                "{\"id\":\"UPPER\",\"name\":\"X\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "INVALID_ID");
        assertParseCode(
                "{\"id\":\"9-starts-digit\",\"name\":\"X\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "INVALID_ID");
    }

    @Test
    public void rejectsBlankName() {
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"   \",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "BLANK_FIELD");
    }

    @Test
    public void rejectsTypeMismatch() {
        // Wrong types on the string fields
        assertParseCode(
                "{\"id\":42,\"name\":\"X\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "TYPE_MISMATCH");
    }

    @Test
    public void rejectsUnknownField() {
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"sytemPrompt\":\"typo\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "UNKNOWN_FIELD");
    }

    @Test
    public void rejectsEmptyAllowlist() {
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"cubeAllowlist\":[]}", "EMPTY_ALLOWLIST");
    }

    @Test
    public void rejectsInvalidCubeRef() {
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"cubeAllowlist\":[{\"connectionName\":\"\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}",
                "INVALID_CUBE_REF");
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\"}]}",
                "INVALID_CUBE_REF");
    }

    @Test
    public void rejectsCubeAllowlistWrongType() {
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"cubeAllowlist\":\"not-an-array\"}", "TYPE_MISMATCH");
    }

    /**
     * Fuzz: 500 random inputs must all either parse or fail with ParseException. Nothing may
     * throw a RuntimeException — that would surface as HTTP 500 from /ai/spaces.
     */
    @Test
    public void fuzzParseNeverPanics() {
        Random rng = new Random(0xABCDEF01L);
        int iterations = 500;
        int parsed = 0;
        int rejected = 0;
        for (int i = 0; i < iterations; i++) {
            String text = randomInput(rng, i);
            try {
                AgentSpace space = AgentSpaceParser.parse("fuzz-" + i + ".json", text);
                assertNotNull(space);
                parsed++;
            } catch (AgentSpaceParser.ParseException e) {
                assertNotNull(e.code());
                assertNotNull(e.source());
                assertNotNull(e.getMessage());
                rejected++;
            } catch (RuntimeException e) {
                fail("iteration " + i + " leaked " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nInput was:\n" + text);
            }
        }
        assertTrue("fuzz parsed " + parsed + " / rejected " + rejected, rejected > 0);
    }

    private static String randomInput(Random rng, int i) {
        switch (i % 6) {
            case 0:
                return "";
            case 1:
                return randomBytes(rng, rng.nextInt(200));
            case 2:
                return "{" + randomBytes(rng, rng.nextInt(200)) + "}";
            case 3:
                // Valid outer, garbage inner values.
                return "{\"id\":\"" + randomBytes(rng, 4 + rng.nextInt(20)) + "\",\"name\":\"X\",\"cubeAllowlist\":[]}";
            case 4:
                // Valid.
                return "{\"id\":\"space-"
                        + i
                        + "\",\"name\":\"S "
                        + i
                        + "\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}";
            default:
                // Extra fields to trip UNKNOWN_FIELD.
                return "{\"id\":\"space-"
                        + i
                        + "\",\"name\":\"S "
                        + i
                        + "\",\"extra"
                        + rng.nextInt(1000)
                        + "\":\"v\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}";
        }
    }

    private static String randomBytes(Random rng, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int c = 32 + rng.nextInt(95);
            // Skip double-quote and backslash so we don't accidentally produce syntactically valid
            // JSON that just happens to contain garbage — makes the fuzz signal cleaner.
            if (c == '"' || c == '\\') c = ' ';
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static void assertParseCode(String text, String expected) {
        try {
            AgentSpaceParser.parse("src.json", text);
            fail("expected ParseException(" + expected + ")");
        } catch (AgentSpaceParser.ParseException e) {
            assertEquals("code (message was: " + e.getMessage() + ")", expected, e.code());
        }
    }
}
