/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.io.TempDir;
import org.saiku.service.olap.ai.ask.CertifiedQuery;
import org.saiku.service.olap.ai.ask.CertifiedQueryParser;
import org.saiku.service.olap.ai.ask.CertifiedQueryRegistry;

/**
 * Fuzz invariants for the certified-query catalogue (saiku#1430).
 *
 * <p>Three properties, one per acceptance-criterion failure mode:
 *
 * <ul>
 *   <li><b>Malformed {@code matchIntent}</b> — whatever JSON shape arrives in the intent slot must
 *       fail with a structured {@code INVALID_MATCH_INTENT}, never a RuntimeException and never a
 *       silently accepted entry that can never fire.
 *   <li><b>Missing / unrunnable query body</b> — likewise total: an entry the registry keeps is
 *       always one it can actually run.
 *   <li><b>Id collision</b> — two files declaring the same id collapse to one catalogue entry plus
 *       one {@code DUPLICATE_ID} error. A collision must never yield two entries both claiming the
 *       same approval.
 * </ul>
 *
 * <p>Plus the round-trip and totality guards the space / skill parsers already carry.
 */
class CertifiedQueryParserPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Generator<String> ID = fromRegex("[a-z][a-z0-9-]{0,20}");
    private static final Generator<String> PHRASE = fromRegex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,15}");
    /** The parser only requires a non-blank statement, so the drawn text is free-form by design. */
    private static final Generator<String> MDX = fromRegex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,15}");

    /**
     * JSON shapes that are never a legal {@code matchIntent} value — a scalar where an array
     * belongs, an object, a nested array, an array of mixed types, and an array of blank strings
     * (the classic operator typo: {@code ""} left behind after deleting the real phrasing).
     */
    private static final Generator<JsonNode> MALFORMED_INTENT = sampledFrom(List.<JsonNode>of(
            MAPPER.getNodeFactory().numberNode(7),
            MAPPER.getNodeFactory().booleanNode(true),
            MAPPER.getNodeFactory().nullNode(),
            MAPPER.createObjectNode().put("phrase", "monthly revenue"),
            MAPPER.createArrayNode().add(1),
            MAPPER.createArrayNode().add("monthly revenue").add(42),
            MAPPER.createArrayNode().add("   ")));

    /** JSON shapes that are never a runnable ThinQuery. */
    private static final Generator<JsonNode> MALFORMED_QUERY = sampledFrom(List.<JsonNode>of(
            MAPPER.getNodeFactory().textNode("SELECT 1"),
            MAPPER.getNodeFactory().numberNode(3),
            MAPPER.getNodeFactory().nullNode(),
            MAPPER.getNodeFactory().arrayNode(),
            // A cube with nothing to run — parseable JSON, unrunnable query.
            MAPPER.createObjectNode()
                    .set("cube", MAPPER.createObjectNode().put("name", "Sales").put("connection", "conn"))));

    @TempDir
    Path tempDir;

    /** Canonical JSON round-trips to the exact id, intents and MDX. */
    @HegelTest
    void validJsonRoundTrips(TestCase tc) throws Exception {
        String id = tc.draw(ID, "id");
        String mdx = tc.draw(MDX, "mdx");
        String intentA = tc.draw(PHRASE, "intentA");
        String intentB = tc.draw(PHRASE, "intentB");

        ObjectNode root = baseNode(id, mdx);
        ArrayNode intents = root.putArray("matchIntent");
        intents.add(intentA);
        intents.add(intentB);

        CertifiedQuery q;
        try {
            q = CertifiedQueryParser.parse("test", MAPPER.writeValueAsString(root));
        } catch (CertifiedQueryParser.ParseException e) {
            fail("canonical JSON must parse, got " + e.code() + ": " + e.getMessage());
            return;
        }
        assertEquals(id, q.id(), "id must round-trip");
        assertEquals(mdx, q.query().getMdx(), "mdx must round-trip");
        assertEquals("Sales", q.cubeName());
        // Phrasings are normalised on the way in; two identical draws collapse to one, but the
        // entry always keeps at least one usable phrasing.
        assertTrue(q.matchIntent().size() >= 1, "at least one intent survives");
        for (String intent : q.matchIntent()) {
            assertFalse(intent.isBlank());
            assertEquals(intent, intent.toLowerCase(Locale.ROOT), "intents are stored normalised");
        }
    }

    /** Any input at all: a query or a structured ParseException — never a leaked RuntimeException. */
    @HegelTest
    void parseIsTotal(TestCase tc) {
        String s = tc.draw(text(), "s");
        try {
            assertNotNull(CertifiedQueryParser.parse("test", s));
        } catch (CertifiedQueryParser.ParseException e) {
            assertNotNull(e.code());
        } catch (Throwable t) {
            fail("non-ParseException thrown for input: " + t);
        }
    }

    /**
     * Whatever JSON value lands in {@code matchIntent}, the parse is total and the entry is
     * rejected. A malformed intent list must never reach the matcher as a half-understood list.
     */
    @HegelTest
    void malformedMatchIntentIsAlwaysRejected(TestCase tc) throws Exception {
        String id = tc.draw(ID, "id");
        String mdx = tc.draw(MDX, "mdx");
        JsonNode malformed = tc.draw(MALFORMED_INTENT, "matchIntent");

        ObjectNode root = baseNode(id, mdx);
        root.set("matchIntent", malformed);
        String json = MAPPER.writeValueAsString(root);
        try {
            CertifiedQuery q = CertifiedQueryParser.parse("test", json);
            // The one legal shape is a well-formed array of non-blank strings; the generator only
            // ever produces invalid ones, so reaching here is a bug.
            fail("a malformed matchIntent must never parse: " + json + " -> " + q.matchIntent());
        } catch (CertifiedQueryParser.ParseException e) {
            assertEquals("INVALID_MATCH_INTENT", e.code());
        }
    }

    /** A {@code query} body that isn't a runnable ThinQuery is always rejected. */
    @HegelTest
    void unrunnableQueryBodyIsAlwaysRejected(TestCase tc) throws Exception {
        String id = tc.draw(ID, "id");
        JsonNode notAQuery = tc.draw(MALFORMED_QUERY, "query");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", id);
        root.putArray("matchIntent").add("revenue");
        root.set("query", notAQuery);
        String json = MAPPER.writeValueAsString(root);
        try {
            CertifiedQueryParser.parse("test", json);
            fail("an unrunnable query body must never parse: " + json);
        } catch (CertifiedQueryParser.ParseException e) {
            assertNotNull(e.code());
            assertFalse(
                    "INVALID_MATCH_INTENT".equals(e.code()),
                    "an unrunnable body must not be reported as an intent problem");
        }
    }

    /** Two files, one id: the catalogue keeps exactly one entry and reports the collision. */
    @HegelTest
    void idCollisionCollapsesToOneEntry(TestCase tc) throws Exception {
        String id = tc.draw(ID, "id");
        String mdxA = tc.draw(MDX, "mdxA");
        String mdxB = tc.draw(MDX, "mdxB");
        String intent = tc.draw(PHRASE, "intent");

        // A fresh directory per case: the temp root may be reused across Hegel's runs, and a
        // leftover entry from another case would make the "exactly one" assertion lie.
        Path dir = Files.createDirectories(tempDir.resolve("case-" + mdxA.hashCode() + "-" + mdxB.hashCode()));
        ObjectNode a = baseNode(id, mdxA);
        a.putArray("matchIntent").add(intent);
        ObjectNode b = baseNode(id, mdxB);
        b.putArray("matchIntent").add(intent);
        Files.write(dir.resolve("a.json"), MAPPER.writeValueAsString(a).getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("b.json"), MAPPER.writeValueAsString(b).getBytes(StandardCharsets.UTF_8));

        CertifiedQueryRegistry reg = new CertifiedQueryRegistry(dir);
        List<CertifiedQuery> entries = reg.list();
        assertEquals(1, entries.size(), "a duplicate id must not yield two approvals");
        assertEquals(1, reg.errors().size());
        assertEquals("DUPLICATE_ID", reg.errors().get(0).code());
    }

    /**
     * A valid entry in its on-disk envelope: {@code id} + {@code matchIntent} at the top level,
     * a runnable ThinQuery under {@code query}.
     */
    private static ObjectNode baseNode(String id, String mdx) {
        ObjectNode query = MAPPER.createObjectNode();
        query.put("name", id);
        ObjectNode cube = query.putObject("cube");
        cube.put("name", "Sales");
        cube.put("connection", "conn");
        query.put("type", "MDX");
        query.put("mdx", mdx);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", id);
        root.set("query", query);
        return root;
    }
}
