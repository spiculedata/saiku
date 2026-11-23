/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.Random;
import org.junit.Test;

/**
 * saiku#1430 — golden-path parses, one hand-authored trigger per documented error code, and a fuzz
 * pass asserting no random input ever leaks a RuntimeException. Same discipline as the space
 * parser: a mistyped certified entry must fail with a structured code, never a stack trace on the
 * REST layer, and never a half-parsed query that quietly means something else.
 */
public class CertifiedQueryParserTest {

    private static final String QUERY_BODY = "{\"name\":\"q\",\"cube\":{\"name\":\"Sales\",\"connection\":\"conn\"},"
            + "\"type\":\"MDX\",\"mdx\":\"SELECT {} ON COLUMNS FROM [Sales]\"}";

    @Test
    public void parsesGoldenPath() throws Exception {
        String json = "{\"id\":\"monthly-net-revenue\","
                + "\"description\":\"Official monthly net revenue.\","
                + "\"matchIntent\":[\"monthly revenue\",\"revenue by month\"],"
                + "\"query\":" + QUERY_BODY + "}";
        CertifiedQuery q = CertifiedQueryParser.parse("c.json", json);
        assertEquals("monthly-net-revenue", q.id());
        assertEquals("Official monthly net revenue.", q.description());
        assertEquals(2, q.matchIntent().size());
        assertEquals("monthly revenue", q.matchIntent().get(0));
        assertEquals("Sales", q.cubeName());
        assertEquals("SELECT {} ON COLUMNS FROM [Sales]", q.query().getMdx());
        assertEquals("c.json", q.sourcePath());
    }

    @Test
    public void descriptionIsOptional() throws Exception {
        String json = "{\"id\":\"minimal\",\"matchIntent\":[\"revenue\"],\"query\":" + QUERY_BODY + "}";
        CertifiedQuery q = CertifiedQueryParser.parse("c.json", json);
        assertNull(q.description());
        assertEquals(1, q.matchIntent().size());
    }

    @Test
    public void matchIntentIsNormalised() throws Exception {
        String json = "{\"id\":\"m\",\"matchIntent\":[\"  Monthly   REVENUE, by region  \",\"monthly revenue\"],"
                + "\"query\":" + QUERY_BODY + "}";
        CertifiedQuery q = CertifiedQueryParser.parse("c.json", json);
        // Trimmed, lower-cased, punctuation collapsed — so two spellings of the SAME phrasing
        // collapse to one, and a different phrasing survives normalised.
        assertEquals(2, q.matchIntent().size());
        assertEquals("monthly revenue by region", q.matchIntent().get(0));
        assertEquals("monthly revenue", q.matchIntent().get(1));

        String dupes = "{\"id\":\"m\",\"matchIntent\":[\"Monthly Revenue\",\"monthly  revenue\"]," + "\"query\":"
                + QUERY_BODY + "}";
        assertEquals(
                1, CertifiedQueryParser.parse("c.json", dupes).matchIntent().size());
    }

    @Test
    public void acceptsQueryModelBodiedQuery() throws Exception {
        String json = "{\"id\":\"m\",\"matchIntent\":[\"revenue\"],\"query\":{\"name\":\"q\","
                + "\"cube\":{\"name\":\"Sales\",\"connection\":\"conn\"},"
                + "\"type\":\"QUERYMODEL\",\"queryModel\":{}}}";
        assertNotNull(CertifiedQueryParser.parse("c.json", json).query().getQueryModel());
    }

    @Test
    public void rejectsEmpty() {
        assertParseCode("", "EMPTY_CERTIFIED_QUERY");
        assertParseCode("   ", "EMPTY_CERTIFIED_QUERY");
    }

    @Test
    public void rejectsMalformedJson() {
        assertParseCode("not json", "MALFORMED_JSON");
        assertParseCode("[1,2,3]", "MALFORMED_JSON");
        assertParseCode("\"a string\"", "MALFORMED_JSON");
    }

    @Test
    public void rejectsMissingOrBadId() {
        assertParseCode("{\"matchIntent\":[\"a\"],\"query\":" + QUERY_BODY + "}", "MISSING_FIELD");
        assertParseCode("{\"id\":\"\",\"matchIntent\":[\"a\"],\"query\":" + QUERY_BODY + "}", "BLANK_FIELD");
        assertParseCode("{\"id\":7,\"matchIntent\":[\"a\"],\"query\":" + QUERY_BODY + "}", "TYPE_MISMATCH");
        assertParseCode(
                "{\"id\":\"Monthly Revenue\",\"matchIntent\":[\"a\"],\"query\":" + QUERY_BODY + "}", "INVALID_ID");
        assertParseCode(
                "{\"id\":\"../etc/passwd\",\"matchIntent\":[\"a\"],\"query\":" + QUERY_BODY + "}", "INVALID_ID");
    }

    @Test
    public void rejectsMalformedMatchIntent() {
        // Fuzz-adjacent hand-authored cases: every shape the intent field can arrive in.
        assertParseCode("{\"id\":\"m\",\"query\":" + QUERY_BODY + "}", "INVALID_MATCH_INTENT");
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":\"monthly revenue\",\"query\":" + QUERY_BODY + "}",
                "INVALID_MATCH_INTENT");
        assertParseCode("{\"id\":\"m\",\"matchIntent\":[],\"query\":" + QUERY_BODY + "}", "INVALID_MATCH_INTENT");
        assertParseCode("{\"id\":\"m\",\"matchIntent\":[\"\"],\"query\":" + QUERY_BODY + "}", "INVALID_MATCH_INTENT");
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[\"   \"],\"query\":" + QUERY_BODY + "}", "INVALID_MATCH_INTENT");
        assertParseCode("{\"id\":\"m\",\"matchIntent\":[7],\"query\":" + QUERY_BODY + "}", "INVALID_MATCH_INTENT");
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[{\"phrase\":\"x\"}],\"query\":" + QUERY_BODY + "}",
                "INVALID_MATCH_INTENT");
        assertParseCode("{\"id\":\"m\",\"matchIntent\":[null],\"query\":" + QUERY_BODY + "}", "INVALID_MATCH_INTENT");
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[\"" + "x".repeat(201) + "\"],\"query\":" + QUERY_BODY + "}",
                "INVALID_MATCH_INTENT");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            many.append(i == 0 ? "" : ",").append("\"phrase ").append(i).append("\"");
        }
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[" + many + "],\"query\":" + QUERY_BODY + "}", "INVALID_MATCH_INTENT");
    }

    @Test
    public void rejectsMissingOrUnrunnableQuery() {
        assertParseCode("{\"id\":\"m\",\"matchIntent\":[\"a\"]}", "MISSING_QUERY");
        assertParseCode("{\"id\":\"m\",\"matchIntent\":[\"a\"],\"query\":\"SELECT 1\"}", "TYPE_MISMATCH");
        assertParseCode("{\"id\":\"m\",\"matchIntent\":[\"a\"],\"query\":{}}", "INVALID_QUERY");
        // A cube with no connection can't select a datasource — unrunnable, so rejected.
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[\"a\"],\"query\":{\"cube\":{\"name\":\"Sales\"},\"mdx\":\"SELECT {}\"}}",
                "INVALID_QUERY");
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[\"a\"],\"query\":{\"cube\":{\"connection\":\"c\"},\"mdx\":\"SELECT {}\"}}",
                "INVALID_QUERY");
        // Cube present and wired, but nothing to run.
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[\"a\"],\"query\":{\"cube\":{\"name\":\"Sales\","
                        + "\"connection\":\"c\"}}}",
                "INVALID_QUERY");
    }

    @Test
    public void rejectsUnknownFields() {
        // The typo case that matters most: "matchInents" must not silently parse as an entry that
        // can never fire.
        assertParseCode(
                "{\"id\":\"m\",\"matchInents\":[\"a\"],\"matchIntent\":[\"a\"],\"query\":" + QUERY_BODY + "}",
                "UNKNOWN_FIELD");
        assertParseCode(
                "{\"id\":\"m\",\"matchIntent\":[\"a\"],\"query\":" + QUERY_BODY + ",\"owner\":\"cfo\"}",
                "UNKNOWN_FIELD");
    }

    @Test
    public void toJsonRoundTrips() throws Exception {
        String json = "{\"id\":\"m\",\"description\":\"d\",\"matchIntent\":[\"monthly revenue\"]," + "\"query\":"
                + QUERY_BODY + "}";
        CertifiedQuery first = CertifiedQueryParser.parse("c.json", json);
        CertifiedQuery second = CertifiedQueryParser.parse(
                "c.json", CertifiedQueryParser.toJson(first).toString());
        assertEquals(first.id(), second.id());
        assertEquals(first.description(), second.description());
        assertEquals(first.matchIntent(), second.matchIntent());
        assertEquals(first.query().getMdx(), second.query().getMdx());
        assertEquals(first.cubeName(), second.cubeName());
    }

    /**
     * Fuzz: for 2000 random byte strings the parser must either return a query or throw a structured
     * {@code ParseException} — never a RuntimeException that would surface as a 500 to an operator
     * editing a certified file.
     */
    @Test
    public void fuzzNeverLeaksRuntimeException() {
        Random rnd = new Random(20260731L);
        for (int i = 0; i < 2000; i++) {
            int len = rnd.nextInt(64);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append((char) (32 + rnd.nextInt(95)));
            }
            String s = sb.toString();
            try {
                assertNotNull(CertifiedQueryParser.parse("fuzz.json", s));
            } catch (CertifiedQueryParser.ParseException expected) {
                assertNotNull(expected.code());
            } catch (Throwable t) {
                fail("non-ParseException thrown for fuzz input: " + t);
            }
        }
    }

    private static void assertParseCode(String json, String expectedCode) {
        try {
            CertifiedQueryParser.parse("c.json", json);
            fail("expected ParseException " + expectedCode + " for: " + json);
        } catch (CertifiedQueryParser.ParseException e) {
            assertEquals("wrong code for: " + json, expectedCode, e.code());
        }
    }
}
