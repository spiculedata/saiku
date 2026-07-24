/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.saiku.olap.util.TimeCalcParser;

/**
 * {@link TimeCalcParser#extractCatalogUrl(String)} pulls the {@code Catalog=} value out of a
 * Mondrian connection URL. Properties cover the happy path (a catalog embedded between other
 * {@code ;}-delimited segments is recovered, case-insensitively on the key) and the absent path
 * (no {@code Catalog=} segment, or null input, yields null).
 */
class TimeCalcParserPropertyTest {

    /** A segment value with no {@code ;} (the URL delimiter) and no whitespace (the parser trims). */
    private static final Generator<String> SEG = fromRegex("[A-Za-z0-9:/._-]{1,25}");

    /** A catalog embedded mid-URL is recovered exactly; the {@code Catalog=} key match is case-insensitive. */
    @HegelTest
    void catalogRoundTrips(TestCase tc) {
        String jdbc = tc.draw(SEG, "jdbc");
        String catalog = tc.draw(SEG, "catalog");
        String drivers = tc.draw(SEG, "drivers");
        String key = tc.draw(sampledFrom("Catalog=", "catalog=", "CATALOG=", "CaTaLoG="), "key");

        String url = "Jdbc=" + jdbc + ";" + key + catalog + ";JdbcDrivers=" + drivers;

        assertEquals(catalog, TimeCalcParser.extractCatalogUrl(url), "Catalog= value must be recovered verbatim");
    }

    /** A URL with no {@code Catalog=} segment yields null. */
    @HegelTest
    void missingCatalogYieldsNull(TestCase tc) {
        String jdbc = tc.draw(SEG, "jdbc");
        String drivers = tc.draw(SEG, "drivers");

        // Neither segment begins with "Catalog="; JdbcDrivers deliberately does not match the key.
        String url = "Jdbc=" + jdbc + ";JdbcDrivers=" + drivers;

        assertNull(TimeCalcParser.extractCatalogUrl(url), "no Catalog= segment must yield null");
    }

    /** Null input yields null (never throws). */
    @HegelTest
    void nullInputYieldsNull(TestCase tc) {
        assertNull(TimeCalcParser.extractCatalogUrl(null), "null input must yield null");
    }
}
