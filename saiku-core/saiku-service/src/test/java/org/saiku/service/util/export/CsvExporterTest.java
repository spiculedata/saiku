/*
 *   Copyright 2026 Saiku
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.service.util.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the CSV / formula-injection neutralisation added to {@link CsvExporter}
 * (OWASP CWE-1236). A future refactor that drops the {@code '} prefixing must
 * turn these RED.
 */
public class CsvExporterTest {

    /* ---- dangerous leading chars get the single-quote prefix ---- */

    @Test
    public void neutralizes_equalsFormula() {
        assertEquals("'=cmd|'/C calc'!A0", CsvExporter.neutralizeCsvFormula("=cmd|'/C calc'!A0"));
    }

    @Test
    public void neutralizes_plusFormula() {
        assertEquals("'+SUM(1+1)", CsvExporter.neutralizeCsvFormula("+SUM(1+1)"));
    }

    @Test
    public void neutralizes_atFormula() {
        assertEquals("'@SUM(1)", CsvExporter.neutralizeCsvFormula("@SUM(1)"));
    }

    @Test
    public void neutralizes_minusFormula_notANumber() {
        // The classic "-2+3+cmd…" attack starts with '-' but is NOT a number.
        assertEquals("'-2+3+cmd|'/C calc'!A0", CsvExporter.neutralizeCsvFormula("-2+3+cmd|'/C calc'!A0"));
    }

    @Test
    public void neutralizes_leadingTabAndCr() {
        assertEquals("'\t=1+1", CsvExporter.neutralizeCsvFormula("\t=1+1"));
        assertEquals("'\r=1+1", CsvExporter.neutralizeCsvFormula("\r=1+1"));
    }

    /* ---- plain numbers (incl. negatives) are left intact ---- */

    @Test
    public void leaves_negativeNumber_intact() {
        assertEquals("-5", CsvExporter.neutralizeCsvFormula("-5"));
        assertEquals("-1234.50", CsvExporter.neutralizeCsvFormula("-1234.50"));
    }

    @Test
    public void leaves_negativeThousands_andPercent_andCurrency_intact() {
        assertEquals("-1,234.50", CsvExporter.neutralizeCsvFormula("-1,234.50"));
        assertEquals("-12.5%", CsvExporter.neutralizeCsvFormula("-12.5%"));
        assertEquals("-$1,234.50", CsvExporter.neutralizeCsvFormula("-$1,234.50"));
        assertEquals("+5", CsvExporter.neutralizeCsvFormula("+5"));
    }

    /* ---- ordinary text + edge cases ---- */

    @Test
    public void leaves_ordinaryText_intact() {
        assertEquals("Beer", CsvExporter.neutralizeCsvFormula("Beer"));
        assertEquals("Beer and Wine", CsvExporter.neutralizeCsvFormula("Beer and Wine"));
        assertEquals("$1,234.50", CsvExporter.neutralizeCsvFormula("$1,234.50"));
        assertEquals("(5.00)", CsvExporter.neutralizeCsvFormula("(5.00)"));
    }

    @Test
    public void handles_nullAndEmpty() {
        assertEquals(null, CsvExporter.neutralizeCsvFormula(null));
        assertEquals("", CsvExporter.neutralizeCsvFormula(""));
    }

    @Test
    public void isPlainNumber_classification() {
        assertTrue(CsvExporter.isPlainNumber("-5"));
        assertTrue(CsvExporter.isPlainNumber("1,234.5"));
        assertTrue(CsvExporter.isPlainNumber("-$1,234.50"));
        assertFalse(CsvExporter.isPlainNumber("-2+3+cmd"));
        assertFalse(CsvExporter.isPlainNumber("=1"));
        assertFalse(CsvExporter.isPlainNumber("Beer"));
    }
}
