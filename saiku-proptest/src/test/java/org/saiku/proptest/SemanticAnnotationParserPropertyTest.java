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
     * HAZARD, pinned rather than hidden. The flag matches ONLY the literal "true". Every other
     * affirmative spelling a schema author might reasonably reach for — {@code yes}, {@code 1},
     * {@code on}, {@code y} — silently reads as false, which means the column is NOT masked.
     *
     * <p>This fails OPEN. For a metadata field that would be a shrug; for a PII marker it means a
     * plausible typo leaves personal data flowing to the AI layer and through drillthrough, with no
     * warning logged. Contrast {@code validateEnum}, which logs when it drops an unrecognised value.
     *
     * <p>Asserted here so the behaviour is deliberate and discoverable rather than a surprise during
     * an incident.
     */
    @HegelTest
    void otherAffirmativeSpellingsSilentlyFailOpen(TestCase tc) {
        String affirmative =
                tc.draw(sampledFrom(List.of("yes", "Yes", "YES", "1", "on", "y", "Y", "t")), "affirmative");

        assertFalse(
                SemanticAnnotationParser.parseMeasure(ann("pii", affirmative)).pii,
                "behaviour changed — update this test AND the note in saiku#902: " + affirmative);
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
