/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * saiku#1692 — the demo-datasource Mondrian locations must never carry backslashes in the
 * {@code Jdbc=} URL: Mondrian's Calcite backend embeds that URL in its model JSON, where
 * {@code \U} in {@code C:\Users} is an invalid string escape and every connect dies with
 * {@code JsonParseException: Unrecognized character escape 'U'} (observed at boot-time
 * GraphQL cube-generator init on a fresh Windows home). These tests pin the shared
 * {@link Database#h2MondrianLocation} builder used by the foodmart, bank, and earthquakes
 * registrations.
 */
public class DatabaseLocationTest {

    @Test
    public void windowsBackslashDirIsNormalisedToForwardSlashes() {
        String loc = Database.h2MondrianLocation(
                "C:\\Users\\demo\\saiku-home\\data", "foodmart", null, "file:/C:/Users/demo/FoodMart4.xml");
        assertFalse("no backslashes may reach the Calcite model JSON — got: " + loc, loc.contains("\\"));
        assertEquals(
                "jdbc:mondrian:Jdbc=jdbc:h2:C:/Users/demo/saiku-home/data/foodmart;"
                        + "Catalog=file:/C:/Users/demo/FoodMart4.xml;JdbcDrivers=org.h2.Driver",
                loc);
    }

    @Test
    public void posixDirPassesThroughByteIdenticalToLegacyConcatenation() {
        // The legacy string was "jdbc:mondrian:Jdbc=jdbc:h2:" + dir + "/foodmart;Catalog=" + uri
        // + ";JdbcDrivers=org.h2.Driver" — the builder must not change existing POSIX homes' keys.
        String dir = "/app/saiku-home/data";
        String uri = "file:/app/saiku-home/data/FoodMart4.xml";
        String legacy =
                "jdbc:mondrian:Jdbc=jdbc:h2:" + dir + "/foodmart;" + "Catalog=" + uri + ";JdbcDrivers=org.h2.Driver";
        assertEquals(legacy, Database.h2MondrianLocation(dir, "foodmart", null, uri));
    }

    @Test
    public void modeSegmentIsInsertedForEarthquakes() {
        String loc = Database.h2MondrianLocation("C:\\data", "earthquakes", "MySQL", "file:/C:/data/earthquakes.xml");
        assertEquals(
                "jdbc:mondrian:Jdbc=jdbc:h2:C:/data/earthquakes;MODE=MySQL;"
                        + "Catalog=file:/C:/data/earthquakes.xml;JdbcDrivers=org.h2.Driver",
                loc);
    }

    @Test
    public void nullDirIsTolerated() {
        String loc = Database.h2MondrianLocation(null, "foodmart", null, "file:/x.xml");
        assertEquals("jdbc:mondrian:Jdbc=jdbc:h2:/foodmart;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver", loc);
    }
}
