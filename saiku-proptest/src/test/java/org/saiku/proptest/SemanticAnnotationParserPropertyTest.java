/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.maps;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.saiku.service.olap.ai.SemanticAnnotationParser;

/**
 * Property-based tests for {@link SemanticAnnotationParser}, which reads the
 * {@code saiku.semantic.*} annotations a schema author writes into a Mondrian schema.
 *
 * <p>One of those annotations is a security control: {@code saiku.semantic.pii=true} strips values
 * from AI responses and blocks drillthrough {@code returns=} referencing the column (saiku#902).
 * The rest are metadata. The parser is fed schema XML that an admin hand-edits, so it must be total
 * — and, for the PII flag, its failure direction matters more than its correctness.
 */
class SemanticAnnotationParserPropertyTest {

    private static final String NS = SemanticAnnotationParser.NAMESPACE;

    private static Map<String, String> ann(String key, String value) {
        Map<String, String> m = new HashMap<>();
        m.put(NS + key, value);
        return m;
    }

    // --- the PII flag ----------------------------------------------------------

    /** The documented spelling works in any casing, with any surrounding whitespace. */
    @HegelTest
    void theDocumentedPiiSpellingIsHonouredInAnyCasing(TestCase tc) {
        String pad = tc.draw(sampledFrom(List.of("", " ", "  ", "\t", "\n")), "pad");
        String cased = tc.draw(sampledFrom(List.of("true", "TRUE", "True", "tRuE")), "cased");

        String raw = pad + cased + pad;

        assertTrue(SemanticAnnotationParser.parseMeasure(ann("pii", raw)).pii, "measure pii lost for: [" + raw + "]");
        assertTrue(SemanticAnnotationParser.parseLevel(ann("pii", raw)).pii, "level pii lost for: [" + raw + "]");
    }

    /**
     * saiku#1849: every unambiguous way of saying yes flags the column. These were never typos, but
     * the flag used to match ONLY the literal {@code "true"}, so {@code pii=yes} silently read as
     * false and the column was not masked — no redaction in {@code /ai/schema}, no drillthrough
     * {@code returns=} block, and nothing logged.
     */
    @HegelTest
    void everyUnambiguousAffirmativeFlagsTheColumn(TestCase tc) {
        String affirmative = tc.draw(
                sampledFrom(List.of("yes", "Yes", "YES", "1", "on", "ON", "y", "Y", "true", "TRUE")), "affirmative");
        String pad = tc.draw(sampledFrom(List.of("", " ", "  ", "\t")), "pad");

        assertTrue(
                SemanticAnnotationParser.parseMeasure(ann("pii", pad + affirmative + pad)).pii,
                "PII flag ignored for: [" + affirmative + "]");
    }

    /**
     * A genuinely unrecognised value still resolves to NOT-PII, deliberately. The trade-off was
     * argued when the flag was introduced and still holds: for an analytics product, masking working
     * data on a slip is a worse everyday failure than an author correcting a value. What changed is
     * that the slip is now logged at WARN instead of passing in silence.
     */
    @HegelTest
    void anUnrecognisedValueStillMeansNotPii(TestCase tc) {
        String junk = tc.draw(fromRegex("[a-zA-Z_]{2,15}"), "junk");
        String norm = junk.trim().toLowerCase(Locale.ROOT);
        tc.assume(!List.of("true", "yes", "y", "on", "1", "false", "no", "n", "off", "0")
                .contains(norm));

        assertFalse(
                SemanticAnnotationParser.parseMeasure(ann("pii", junk)).pii,
                "an unrecognised value masked the column: " + junk);
    }

    /** Absent, blank and explicitly-false annotations all leave the flag off. */
    @HegelTest
    void anAbsentOrFalsePiiFlagIsOff(TestCase tc) {
        String raw = tc.draw(sampledFrom(List.of("", " ", "false", "FALSE", "no", "0")), "raw");

        assertFalse(SemanticAnnotationParser.parseMeasure(ann("pii", raw)).pii, "pii on for: [" + raw + "]");
        assertFalse(SemanticAnnotationParser.parseMeasure(new HashMap<>()).pii, "pii on with no annotation");
    }

    // --- controlled vocabularies -----------------------------------------------

    /** A value inside the vocabulary survives verbatim; anything else is dropped to null. */
    @HegelTest
    void onlyVocabularyValuesSurvive(TestCase tc) {
        String kind = tc.draw(sampledFrom(SemanticAnnotationParser.AGGREGATION_KINDS), "kind");
        String junk = tc.draw(fromRegex("[a-z-]{1,15}"), "junk");
        tc.assume(!SemanticAnnotationParser.AGGREGATION_KINDS.contains(junk));

        assertEquals(kind, SemanticAnnotationParser.parseMeasure(ann("aggregation_kind", kind)).aggregationKind);
        assertNull(
                SemanticAnnotationParser.parseMeasure(ann("aggregation_kind", junk)).aggregationKind,
                "unknown aggregation_kind survived: " + junk);
    }

    /** Vocabulary matching is exact — case variants are dropped, not silently coerced. */
    @HegelTest
    void vocabularyMatchingIsExact(TestCase tc) {
        String cardinality = tc.draw(sampledFrom(SemanticAnnotationParser.CARDINALITIES), "cardinality");
        String upper = cardinality.toUpperCase(Locale.ROOT);
        tc.assume(!upper.equals(cardinality));

        assertNull(
                SemanticAnnotationParser.parseLevel(ann("cardinality", upper)).cardinality,
                "case variant was accepted: " + upper);
    }

    /** Surrounding whitespace never changes whether a vocabulary value is recognised. */
    @HegelTest
    void whitespaceDoesNotAffectVocabularyRecognition(TestCase tc) {
        String grain = tc.draw(sampledFrom(SemanticAnnotationParser.GRAINS), "grain");
        String pad = tc.draw(sampledFrom(List.of(" ", "  ", "\t", "\n", " \t ")), "pad");

        assertEquals(grain, SemanticAnnotationParser.parseLevel(ann("grain", pad + grain + pad)).grain);
    }

    // --- synonyms ---------------------------------------------------------------

    /** Synonyms are never null, never blank, and always trimmed — they feed an LLM prompt. */
    @HegelTest
    void synonymsAreAlwaysCleanAndNeverNull(TestCase tc) {
        List<String> tokens = tc.draw(
                lists(fromRegex("[ \\t]{0,2}[a-zA-Z ]{0,10}[ \\t]{0,2}"))
                        .minSize(0)
                        .maxSize(6),
                "tokens");

        String csv = String.join(",", tokens);
        List<String> parsed = SemanticAnnotationParser.parseMeasure(ann("synonyms", csv)).synonyms;

        assertNotNull(parsed, "synonyms were null for: [" + csv + "]");
        for (String s : parsed) {
            assertFalse(s.isBlank(), "blank synonym survived from: [" + csv + "]");
            assertEquals(s.trim(), s, "untrimmed synonym: [" + s + "]");
        }
    }

    /** Every non-blank token is preserved, in order — the list is filtered, never reordered. */
    @HegelTest
    void synonymOrderAndContentArePreserved(TestCase tc) {
        List<String> tokens = tc.draw(lists(fromRegex("[a-z]{1,8}")).minSize(1).maxSize(5), "tokens");

        List<String> parsed = SemanticAnnotationParser.parseMeasure(ann("synonyms", String.join(",", tokens))).synonyms;

        assertEquals(tokens, parsed, "synonyms were reordered or dropped");
    }

    // --- totality ---------------------------------------------------------------

    /** A null annotation map yields safe empty defaults rather than an NPE. */
    @HegelTest
    void aNullAnnotationMapYieldsSafeDefaults(TestCase tc) {
        int which = tc.draw(dev.hegel.Generators.integers().min(0).max(2), "which");

        assertDoesNotThrow(() -> {
            switch (which) {
                case 0 -> {
                    var d = SemanticAnnotationParser.parseDimension(null);
                    assertNotNull(d.synonyms);
                    assertNull(d.description);
                }
                case 1 -> {
                    var m = SemanticAnnotationParser.parseMeasure(null);
                    assertNotNull(m.synonyms);
                    assertFalse(m.pii, "null annotations defaulted pii to ON");
                }
                default -> {
                    var l = SemanticAnnotationParser.parseLevel(null);
                    assertNotNull(l.synonyms);
                    assertNotNull(l.requiredFilters);
                    assertFalse(l.pii, "null annotations defaulted pii to ON");
                }
            }
        });
    }

    /**
     * Total over arbitrary annotation maps. These come from hand-edited schema XML, so a junk key
     * or value must never take the schema load down.
     */
    @HegelTest
    void parsingIsTotalOverArbitraryAnnotations(TestCase tc) {
        Map<String, String> annotations = tc.draw(
                maps(fromRegex("(saiku\\.semantic\\.)?[a-z_]{1,15}"), fromRegex("[a-zA-Z0-9,/\\[\\] _-]{0,25}"))
                        .maxSize(6),
                "annotations");
        tc.note("annotations=" + annotations);

        assertDoesNotThrow(() -> SemanticAnnotationParser.parseDimension(annotations));
        assertDoesNotThrow(() -> SemanticAnnotationParser.parseMeasure(annotations));
        assertDoesNotThrow(() -> SemanticAnnotationParser.parseLevel(annotations));
    }
}
