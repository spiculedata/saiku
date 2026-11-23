/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class QuickstartNamesTest {

    @Test
    public void stripsCsvExtension() {
        assertEquals("sales", QuickstartNames.sanitize("sales.csv", "fallback"));
    }

    @Test
    public void replacesSpacesAndPunctuationWithUnderscores() {
        assertEquals("q3_sales_2026", QuickstartNames.sanitize("q3 sales (2026)", "fallback"));
    }

    @Test
    public void trimsLeadingAndTrailingUnderscoresFromEdgePunctuation() {
        assertEquals("sales", QuickstartNames.sanitize(".sales.csv", "fallback"));
    }

    @Test
    public void blankInputFallsBackToTheFallback() {
        assertEquals("fallback_name", QuickstartNames.sanitize("   ", "fallback name"));
    }

    @Test
    public void leadingDigitGetsAPrefix() {
        assertEquals("t_2026_sales", QuickstartNames.sanitize("2026-sales", "fallback"));
    }

    @Test
    public void everythingBlankFallsBackToAGenericName() {
        assertEquals("t", QuickstartNames.sanitize("!!!", "###"));
    }

    @Test
    public void sanitizeHeaderFillsBlankCellsAndDedupesCollisions() {
        List<String> result = QuickstartNames.sanitizeHeader(Arrays.asList("Amount", "amount", "", "Amount"));
        assertEquals("Amount", result.get(0));
        assertEquals("amount_2", result.get(1));
        assertEquals("column_3", result.get(2));
        // "Amount" collides with index 0, then "Amount_2" collides (case-insensitively) with
        // index 1's "amount_2" — the loop keeps incrementing until it finds a free slot.
        assertEquals("Amount_3", result.get(3));
        // No two columns collide, even case-insensitively.
        assertEquals(4, result.stream().map(String::toUpperCase).distinct().count());
    }

    @Test
    public void headerColumnNamedIdIsRenamedToAvoidTheSurrogatePrimaryKey() {
        // CsvTableLoader always adds its own "ID" primary key column — a source column that is
        // ALSO (case-insensitively) named "id" must not collide with it in the CREATE TABLE.
        List<String> result = QuickstartNames.sanitizeHeader(Arrays.asList("id", "amount"));
        assertEquals("id_2", result.get(0));
        assertEquals("amount", result.get(1));
    }

    @Test
    public void longNamesAreTruncated() {
        String raw = "a".repeat(200);
        String sanitized = QuickstartNames.sanitize(raw, "fallback");
        assertTrue(sanitized.length() <= 64);
    }

    @Test
    public void resultNeverStartsWithADigitAndOnlyContainsSafeCharacters() {
        String sanitized = QuickstartNames.sanitize("9 to 5", "fallback");
        assertFalse(Character.isDigit(sanitized.charAt(0)));
        assertTrue(sanitized.matches("[A-Za-z0-9_]+"));
    }
}
