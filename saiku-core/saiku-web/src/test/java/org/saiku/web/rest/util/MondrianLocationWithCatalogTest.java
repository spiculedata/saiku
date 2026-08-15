/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * saiku#1872 — the query preview runs an UNSAVED schema against the datasource being designed
 * against, by reusing its stored location and swapping only the catalog.
 *
 * <p>The security property is that the JDBC URL comes from the stored datasource and never from the
 * request: a preview endpoint that accepted a URL would be a way to make the server connect
 * anywhere.
 */
public class MondrianLocationWithCatalogTest {

    private static final String REAL =
            "jdbc:mondrian:Jdbc=jdbc:h2:/data/foodmart;MODE=MySQL;Catalog=mondrian:///datasources/FoodMart.xml;JdbcDrivers=org.h2.Driver";

    @Test
    public void theJdbcUrlAndDriverAreCarriedThroughUnchanged() {
        String swapped = MondrianLocation.parse(REAL).withCatalog("mondrian:///__preview__/abc.xml");

        MondrianLocation reparsed = MondrianLocation.parse(swapped);
        assertEquals("jdbc:h2:/data/foodmart;MODE=MySQL", reparsed.jdbc());
        assertEquals("org.h2.Driver", reparsed.jdbcDrivers());
    }

    @Test
    public void onlyTheCatalogChanges() {
        String swapped = MondrianLocation.parse(REAL).withCatalog("mondrian:///__preview__/abc.xml");

        assertEquals(
                "mondrian:///__preview__/abc.xml",
                MondrianLocation.parse(swapped).catalog());
    }

    /** The result must be a location Mondrian actually accepts, i.e. round-trippable. */
    @Test
    public void theResultRoundTripsThroughTheParser() {
        String swapped = MondrianLocation.parse(REAL).withCatalog("res:Bank.xml");

        assertTrue(swapped.startsWith(MondrianLocation.MONDRIAN_PREFIX));
        assertEquals("res:Bank.xml", MondrianLocation.parse(swapped).catalog());
        assertEquals(
                "jdbc:h2:/data/foodmart;MODE=MySQL",
                MondrianLocation.parse(swapped).jdbc());
    }

    /** A JDBC URL carrying its own semicolons must survive — that is why the parser is key-based. */
    @Test
    public void aJdbcUrlContainingSemicolonsSurvives() {
        String withParams =
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:x;MODE=MySQL;DB_CLOSE_DELAY=-1;Catalog=res:A.xml;JdbcDrivers=org.h2.Driver";

        String swapped = MondrianLocation.parse(withParams).withCatalog("res:B.xml");

        assertEquals(
                "jdbc:h2:mem:x;MODE=MySQL;DB_CLOSE_DELAY=-1",
                MondrianLocation.parse(swapped).jdbc());
        assertEquals("res:B.xml", MondrianLocation.parse(swapped).catalog());
    }

    @Test
    public void aLocationWithNoJdbcComponentIsRefused() {
        assertThrows(IllegalStateException.class, () -> MondrianLocation.parse("jdbc:mondrian:Catalog=res:A.xml")
                .withCatalog("res:B.xml"));
        // A plain JDBC (non-Mondrian) datasource parses to all-nulls and must also refuse.
        assertThrows(IllegalStateException.class, () -> MondrianLocation.parse("jdbc:h2:mem:x")
                .withCatalog("res:B.xml"));
    }

    @Test
    public void aMissingDriverIsSimplyOmitted() {
        String swapped = MondrianLocation.parse("jdbc:mondrian:Jdbc=jdbc:h2:mem:x;Catalog=res:A.xml")
                .withCatalog("res:B.xml");

        assertEquals("jdbc:mondrian:Jdbc=jdbc:h2:mem:x;Catalog=res:B.xml", swapped);
    }
}
