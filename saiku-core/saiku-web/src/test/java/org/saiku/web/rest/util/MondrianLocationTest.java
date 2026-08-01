/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * saiku#1634 — key-based parsing of the Mondrian {@code location} connect string. The regression
 * this guards: an inner {@code Jdbc=} URL carrying its own {@code ;}-separated params must not shift
 * the {@code Catalog} / {@code JdbcDrivers} slots.
 */
public class MondrianLocationTest {

    @Test
    public void innerJdbcParamsDoNotShiftTheOtherKeys() {
        // The launcher's FoodMart datasource: H2 ;MODE=MySQL rides inside the Jdbc value.
        MondrianLocation p = MondrianLocation.parse("jdbc:mondrian:Jdbc=jdbc:h2:/data/foodmart;MODE=MySQL;"
                + "Catalog=file:/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver");
        assertEquals("jdbc:h2:/data/foodmart;MODE=MySQL", p.jdbc());
        assertEquals("file:/data/FoodMart4.xml", p.catalog());
        assertEquals("org.h2.Driver", p.jdbcDrivers());
    }

    @Test
    public void plainThreeKeyLocation() {
        // The Bank datasource: no embedded params — parses cleanly (and did under the old code).
        MondrianLocation p = MondrianLocation.parse(
                "jdbc:mondrian:Jdbc=jdbc:h2:/data/foodmart;" + "Catalog=file:/data/Bank.xml;JdbcDrivers=org.h2.Driver");
        assertEquals("jdbc:h2:/data/foodmart", p.jdbc());
        assertEquals("file:/data/Bank.xml", p.catalog());
        assertEquals("org.h2.Driver", p.jdbcDrivers());
    }

    @Test
    public void keyOrderDoesNotMatter() {
        MondrianLocation p = MondrianLocation.parse(
                "jdbc:mondrian:Catalog=res:FoodMart4.xml;JdbcDrivers=org.h2.Driver;Jdbc=jdbc:h2:mem:x");
        assertEquals("jdbc:h2:mem:x", p.jdbc());
        assertEquals("res:FoodMart4.xml", p.catalog());
        assertEquals("org.h2.Driver", p.jdbcDrivers());
    }

    @Test
    public void mondrianRepositoryCatalog() {
        // The UI write path emits Catalog=mondrian://<schema>; must round-trip verbatim.
        MondrianLocation p = MondrianLocation.parse("jdbc:mondrian:Jdbc=jdbc:postgresql://db/foodmart;"
                + "Catalog=mondrian://FoodMart;JdbcDrivers=org.postgresql.Driver");
        assertEquals("jdbc:postgresql://db/foodmart", p.jdbc());
        assertEquals("mondrian://FoodMart", p.catalog());
        assertEquals("org.postgresql.Driver", p.jdbcDrivers());
    }

    @Test
    public void missingKeysAreNull() {
        MondrianLocation p = MondrianLocation.parse("jdbc:mondrian:Jdbc=jdbc:h2:mem:x");
        assertEquals("jdbc:h2:mem:x", p.jdbc());
        assertNull(p.catalog());
        assertNull(p.jdbcDrivers());
    }

    @Test
    public void nonMondrianOrNullYieldsAllNull() {
        MondrianLocation xmla = MondrianLocation.parse("jdbc:xmla:Server=http://host/xmla");
        assertNull(xmla.jdbc());
        assertNull(xmla.catalog());
        assertNull(xmla.jdbcDrivers());

        MondrianLocation nul = MondrianLocation.parse(null);
        assertNull(nul.jdbc());
        assertNull(nul.catalog());
        assertNull(nul.jdbcDrivers());
    }
}
