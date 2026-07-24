/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.saiku.service.util.export.CsvExporter;

/**
 * CSV formula-injection safety ({@link CsvExporter#neutralizeCsvFormula}). A cell whose text starts
 * with {@code = + - @} (or tab/CR) is executed as a formula by Excel/Sheets, so a malicious cell
 * value is a real attack. These properties assert every dangerous value is neutralised while the
 * exporter never otherwise mutates content.
 */
class CsvExporterPropertyTest {

    private static final List<String> TRIGGERS = List.of("=", "+", "-", "@", "\t", "\r");

    /** A value starting with a formula trigger and containing letters (so clearly not a number) is
     *  prefixed with a single quote, defusing the formula. */
    @HegelTest
    void dangerousFormulaValuesAreQuoted(TestCase tc) {
        String trigger = tc.draw(sampledFrom(TRIGGERS), "trigger");
        String tail = tc.draw(fromRegex("[A-Za-z][A-Za-z0-9 ()._-]{0,20}"), "tail"); // letter-led => not numeric
        String value = trigger + tail;

        assertEquals("'" + value, CsvExporter.neutralizeCsvFormula(value));
    }

    /** Whatever the input, the output is only ever the input itself or a single leading quote plus the
     *  input — the exporter never mangles content beyond that one defensive prefix. */
    @HegelTest
    void outputIsInputOrSingleQuotedInput(TestCase tc) {
        String value = tc.draw(text(), "value");

        String out = CsvExporter.neutralizeCsvFormula(value);

        assertTrue(out.equals(value) || out.equals("'" + value), "output must be input or \"'\" + input");
    }

    /** A value that does not start with a formula trigger is returned untouched (no false positives). */
    @HegelTest
    void safeLeadingCharValuesAreUnchanged(TestCase tc) {
        String value = tc.draw(fromRegex("[A-Za-z0-9][A-Za-z0-9 ().,_-]{0,20}"), "value"); // letter/digit-led

        assertEquals(value, CsvExporter.neutralizeCsvFormula(value));
    }
}
