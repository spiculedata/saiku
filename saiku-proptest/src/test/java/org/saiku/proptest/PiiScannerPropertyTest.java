/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.Locale;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.PiiScanner;

/**
 * Property-based tests for {@link PiiScanner} — the safety net BENEATH the operator's
 * {@code saiku.semantic.pii} declarations (saiku#902 phase 3). It pattern-matches measure and level
 * names at schema-build time and SUGGESTS ones that look personal.
 *
 * <p>Its stated posture is false-positive-leaning: over-flagging costs an operator a moment's
 * review, under-flagging means personal data goes unnoticed. So the properties are asymmetric on
 * purpose — misses are asserted hard, and the "don't over-flag" cases only cover names the
 * implementation explicitly set out to exclude (the {@code \b} word boundaries exist to keep
 * {@code email_count} from firing).
 */
class PiiScannerPropertyTest {

    /** Names that MUST be flagged — the scanner exists for exactly these. */
    private static final List<String> OBVIOUS_PII = List.of(
            "email",
            "e_mail",
            "ssn",
            "social_security",
            "nin",
            "phone",
            "mobile",
            "first_name",
            "last_name",
            "surname",
            "full_name",
            "dob",
            "date_of_birth",
            "birth_date",
            "address",
            "street",
            "postcode",
            "zip",
            "nhs",
            "medical_record",
            "mrn",
            "iban",
            "swift",
            "credit_card",
            "account_number");

    /** Ordinary analytics column names that carry no personal data. */
    private static final List<String> BENIGN =
            List.of("unit sales", "store cost", "revenue", "quantity", "margin", "discount", "profit", "units shipped");

    private static AiSchema schemaWithMeasure(String measureName, boolean alreadyFlagged) {
        AiSchema s = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
        AiSchema.Measure m = new AiSchema.Measure(measureName, "[Measures].[" + measureName + "]");
        m.pii = alreadyFlagged;
        s.measures.put(AiSchema.key(m.name), m);
        return s;
    }

    private static AiSchema schemaWithLevel(String levelName, boolean alreadyFlagged) {
        AiSchema s = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
        AiSchema.Dimension d = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        AiSchema.Level l = new AiSchema.Level(levelName, "[Customer].[Customer].[" + levelName + "]");
        l.pii = alreadyFlagged;
        h.levels.put(AiSchema.key(l.name), l);
        d.hierarchies.put(AiSchema.key(h.name), h);
        s.dimensions.put(AiSchema.key(d.name), d);
        return s;
    }

    /** THE property: an obviously personal column name is never missed, on a measure. */
    @HegelTest
    void obviousPiiMeasureNamesAreAlwaysFlagged(TestCase tc) {
        String name = tc.draw(sampledFrom(OBVIOUS_PII), "name");
        boolean upper = tc.draw(dev.hegel.Generators.booleans(), "upper");

        String cased = upper ? name.toUpperCase(Locale.ROOT) : name;

        assertFalse(
                PiiScanner.scan(schemaWithMeasure(cased, false)).isEmpty(),
                "scanner missed an obviously personal measure name: " + cased);
    }

    /** ...and on a level, which is where most personal columns actually live. */
    @HegelTest
    void obviousPiiLevelNamesAreAlwaysFlagged(TestCase tc) {
        String name = tc.draw(sampledFrom(OBVIOUS_PII), "name");

        assertFalse(
                PiiScanner.scan(schemaWithLevel(name, false)).isEmpty(),
                "scanner missed an obviously personal level name: " + name);
    }

    /** Detection survives the separators and casing schema authors actually use. */
    @HegelTest
    void detectionSurvivesRealisticNameFormatting(TestCase tc) {
        String base = tc.draw(sampledFrom(List.of("email", "phone", "surname", "postcode", "iban")), "base");
        String decorated = tc.draw(sampledFrom(List.of("%s", "Customer %s", "%s ", " %s", "%s Address")), "decorated");

        String name = String.format(decorated, base);

        assertFalse(
                PiiScanner.scan(schemaWithLevel(name, false)).isEmpty(),
                "scanner missed a decorated personal name: [" + name + "]");
    }

    /**
     * A column the operator ALREADY flagged is not re-suggested. The scanner is a safety net beneath
     * their declaration, so re-reporting what they have already handled is noise that trains people
     * to ignore it.
     */
    @HegelTest
    void alreadyFlaggedColumnsAreNotReSuggested(TestCase tc) {
        String name = tc.draw(sampledFrom(OBVIOUS_PII), "name");

        assertTrue(
                PiiScanner.scan(schemaWithMeasure(name, true)).isEmpty(),
                "re-suggested a column the operator already flagged: " + name);
    }

    /**
     * The word boundaries do their job: an aggregate OVER a personal column is not itself personal.
     * This is the one over-flagging case the implementation explicitly set out to avoid.
     */
    @HegelTest
    void aggregatesOverPiiColumnsAreNotFlagged(TestCase tc) {
        String base = tc.draw(sampledFrom(List.of("email", "phone", "address")), "base");
        String suffix = tc.draw(sampledFrom(List.of("count", "shipped", "sent")), "suffix");

        String name = base + suffix; // "emailcount" — no boundary between them

        assertTrue(PiiScanner.scan(schemaWithMeasure(name, false)).isEmpty(), "flagged an aggregate as PII: " + name);
    }

    /** Ordinary analytics measures are left alone. */
    @HegelTest
    void benignAnalyticsNamesAreNotFlagged(TestCase tc) {
        String name = tc.draw(sampledFrom(BENIGN), "name");

        assertTrue(PiiScanner.scan(schemaWithMeasure(name, false)).isEmpty(), "flagged a benign measure: " + name);
    }

    /** Every match points at a real column and names the rule that fired. */
    @HegelTest
    void everyMatchIsAttributable(TestCase tc) {
        String name = tc.draw(sampledFrom(OBVIOUS_PII), "name");

        for (PiiScanner.Match m : PiiScanner.scan(schemaWithLevel(name, false))) {
            assertFalse(m.name == null || m.name.isBlank(), "a match named no rule");
            assertFalse(m.path == null || m.path.isBlank(), "a match pointed at no column");
        }
    }

    /** Scanning is deterministic — the same schema yields the same suggestions. */
    @HegelTest
    void scanningIsDeterministic(TestCase tc) {
        String name = tc.draw(sampledFrom(OBVIOUS_PII), "name");
        AiSchema s = schemaWithLevel(name, false);

        assertEquals(PiiScanner.scan(s).size(), PiiScanner.scan(s).size());
    }

    /** Total over arbitrary names — schema names come from hand-edited XML. */
    @HegelTest
    void scanningIsTotal(TestCase tc) {
        String name = tc.draw(fromRegex("[a-zA-Z0-9 _.\\[\\]-]{0,30}"), "name");

        assertDoesNotThrow(() -> PiiScanner.scan(schemaWithMeasure(name, false)));
        assertDoesNotThrow(() -> PiiScanner.scan(schemaWithLevel(name, false)));
    }

    /** An empty schema yields no suggestions rather than throwing. */
    @HegelTest
    void anEmptySchemaYieldsNoMatches(TestCase tc) {
        AiSchema empty = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");

        assertTrue(PiiScanner.scan(empty).isEmpty());
    }
}
