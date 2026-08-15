/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.saiku.web.rest.util.MondrianLocation;

/**
 * Property-based tests for {@link MondrianLocation#parse}, which splits a data source's
 * {@code jdbc:mondrian:Jdbc=…;Catalog=…;JdbcDrivers=…} location into its three parts.
 *
 * <p>This parser is load-bearing and subtle for one reason: the {@code Jdbc=} value is ITSELF a JDBC
 * URL containing {@code ;}-separated parameters ({@code jdbc:h2:/db;MODE=MySQL;AUTO_SERVER=TRUE}).
 * A naive split on {@code ;} truncates it, and the damage is silent — the connection is attempted
 * with a mangled URL, or the catalog is read from the wrong place. saiku#1844 was a bug in exactly
 * this area of the stack, so the round trip is worth asserting over generated input rather than the
 * three hand-written examples that happen not to contain a semicolon.
 *
 * <p>The core property: build a location from known parts, in any key order, and every part must
 * come back byte-identical.
 */
class MondrianLocationPropertyTest {

    private static final String PREFIX = "jdbc:mondrian:";

    /** Realistic inner JDBC URLs — deliberately including several with embedded {@code ;} params. */
    private static final List<String> JDBC_URLS = List.of(
            "jdbc:h2:/data/foodmart",
            "jdbc:h2:/data/foodmart;MODE=MySQL",
            "jdbc:h2:/data/foodmart;MODE=MySQL;AUTO_SERVER=TRUE",
            "jdbc:postgresql://localhost:5432/warehouse",
            "jdbc:postgresql://localhost:5432/warehouse;ssl=true;sslmode=require",
            "jdbc:mysql://host:3306/db?useSSL=false",
            "jdbc:sqlserver://host;databaseName=dw;encrypt=true");

    /** Every catalog spelling Saiku writes — the repository scheme included (saiku#1844). */
    private static final List<String> CATALOGS = List.of(
            "file:/Users/x/saiku-home/data/FoodMart4.xml",
            "file:///data/FoodMart4.xml",
            "res:foodmart.xml",
            "mondrian:///datasources/ScratchSales.xml",
            "mondrian://FoodMart",
            "/datasources/Bank.xml");

    private static final List<String> DRIVERS = List.of(
            "org.h2.Driver",
            "org.postgresql.Driver",
            "com.mysql.cj.jdbc.Driver",
            "org.h2.Driver,org.hsqldb.jdbcDriver");

    /** Assemble the three key/value pairs in the given order. */
    private static String assemble(List<String> keys, String jdbc, String catalog, String drivers) {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            String k = keys.get(i);
            sb.append(k).append('=');
            switch (k) {
                case "Jdbc" -> sb.append(jdbc);
                case "Catalog" -> sb.append(catalog);
                default -> sb.append(drivers);
            }
        }
        return sb.toString();
    }

    private static List<String> permutation(TestCase tc) {
        List<String> keys = new ArrayList<>(List.of("Jdbc", "Catalog", "JdbcDrivers"));
        // Draw a permutation index rather than shuffling, so Hegel can shrink it.
        int idx = tc.draw(sampledFrom(List.of(0, 1, 2, 3, 4, 5)), "keyOrder");
        List<List<String>> perms = List.of(
                List.of("Jdbc", "Catalog", "JdbcDrivers"),
                List.of("Jdbc", "JdbcDrivers", "Catalog"),
                List.of("Catalog", "Jdbc", "JdbcDrivers"),
                List.of("Catalog", "JdbcDrivers", "Jdbc"),
                List.of("JdbcDrivers", "Jdbc", "Catalog"),
                List.of("JdbcDrivers", "Catalog", "Jdbc"));
        keys = perms.get(idx);
        return keys;
    }

    /**
     * THE round trip. Whatever the key order, and however many {@code ;} params the inner JDBC URL
     * carries, all three components survive parsing unchanged.
     */
    @HegelTest
    void everyComponentSurvivesTheRoundTripInAnyKeyOrder(TestCase tc) {
        String jdbc = tc.draw(sampledFrom(JDBC_URLS), "jdbc");
        String catalog = tc.draw(sampledFrom(CATALOGS), "catalog");
        String drivers = tc.draw(sampledFrom(DRIVERS), "drivers");
        List<String> order = permutation(tc);

        String location = assemble(order, jdbc, catalog, drivers);
        tc.note("location=" + location);
        MondrianLocation parsed = MondrianLocation.parse(location);

        assertEquals(jdbc, parsed.jdbc(), "Jdbc mangled in: " + location);
        assertEquals(catalog, parsed.catalog(), "Catalog mangled in: " + location);
        assertEquals(drivers, parsed.jdbcDrivers(), "JdbcDrivers mangled in: " + location);
    }

    /**
     * The specific trap: an inner JDBC URL with its own {@code ;} parameters must not be truncated
     * at the first semicolon. Stated separately from the round trip because this is the failure the
     * hand-written examples can't see.
     */
    @HegelTest
    void innerJdbcParametersAreNotTruncatedAtTheFirstSemicolon(TestCase tc) {
        String base = tc.draw(sampledFrom(List.of("jdbc:h2:/data/db", "jdbc:postgresql://h:5432/db")), "base");
        List<String> params = tc.draw(
                lists(fromRegex("[A-Za-z][A-Za-z0-9_]{0,8}=[A-Za-z0-9_]{1,8}"))
                        .minSize(1)
                        .maxSize(4),
                "params");

        String jdbc = base + ";" + String.join(";", params);
        String location = PREFIX + "Jdbc=" + jdbc + ";Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver";

        MondrianLocation parsed = MondrianLocation.parse(location);

        assertEquals(jdbc, parsed.jdbc(), "inner JDBC params were truncated");
        assertEquals("file:/x.xml", parsed.catalog());
    }

    /** A catalog is returned verbatim — no unescaping, no scheme rewriting, no trimming. */
    @HegelTest
    void theCatalogIsReturnedVerbatim(TestCase tc) {
        String catalog = tc.draw(sampledFrom(CATALOGS), "catalog");

        MondrianLocation parsed = MondrianLocation.parse(PREFIX + "Jdbc=jdbc:h2:/db;Catalog=" + catalog);

        assertEquals(catalog, parsed.catalog());
    }

    /** Absent keys are null rather than empty or defaulted — callers distinguish the two. */
    @HegelTest
    void missingKeysAreNull(TestCase tc) {
        String jdbc = tc.draw(sampledFrom(JDBC_URLS), "jdbc");

        MondrianLocation parsed = MondrianLocation.parse(PREFIX + "Jdbc=" + jdbc);

        assertEquals(jdbc, parsed.jdbc());
        assertNull(parsed.catalog(), "absent Catalog should be null");
        assertNull(parsed.jdbcDrivers(), "absent JdbcDrivers should be null");
    }

    /** Anything without the mondrian prefix yields all-nulls — never a partial or bogus parse. */
    @HegelTest
    void nonMondrianLocationsYieldAllNulls(TestCase tc) {
        String other =
                tc.draw(fromRegex("(jdbc:(h2|postgresql|mysql):|xmla:|https?://)[a-zA-Z0-9:/._;=-]{0,40}"), "other");
        tc.assume(!other.startsWith(PREFIX));

        MondrianLocation parsed = MondrianLocation.parse(other);

        assertNull(parsed.jdbc());
        assertNull(parsed.catalog());
        assertNull(parsed.jdbcDrivers());
    }

    /**
     * Total: parsing never throws, whatever arrives. Locations come from a repository file an
     * operator may have hand-edited, so a malformed one must degrade to nulls rather than take the
     * data source list down with an exception.
     */
    @HegelTest
    void parseIsTotal(TestCase tc) {
        String junk = tc.draw(fromRegex("[a-zA-Z0-9:;=/._%$~ -]{0,60}"), "junk");

        assertDoesNotThrow(() -> MondrianLocation.parse(junk));
        assertDoesNotThrow(() -> MondrianLocation.parse(PREFIX + junk));
        assertDoesNotThrow(() -> MondrianLocation.parse(null));
    }
}
