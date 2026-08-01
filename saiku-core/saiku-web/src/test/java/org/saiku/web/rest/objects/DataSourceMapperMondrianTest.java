/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.objects;

import static org.junit.Assert.assertEquals;

import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * saiku#1634 — a Mondrian datasource whose inner {@code Jdbc=} URL carries its own {@code ;}params
 * (H2 {@code ;MODE=MySQL}) must still map to the right driver / schema / jdbcurl display fields.
 * Before the key-based parse, FoodMart showed driver = the Catalog file and schema = "MySQL".
 */
public class DataSourceMapperMondrianTest {

    private static SaikuDatasource mondrianDs(String name, String location) {
        Properties props = new Properties();
        props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
        props.setProperty("location", location);
        props.setProperty("username", "sa");
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, props);
    }

    @Test
    public void foodmartWithEmbeddedH2ModeParamMapsCorrectly() {
        DataSourceMapper m = new DataSourceMapper(mondrianDs(
                "foodmart",
                "jdbc:mondrian:Jdbc=jdbc:h2:/data/foodmart;MODE=MySQL;"
                        + "Catalog=file:/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver"));
        assertEquals("MONDRIAN", m.getConnectiontype());
        assertEquals("jdbc:h2:/data/foodmart;MODE=MySQL", m.getJdbcurl());
        assertEquals("file:/data/FoodMart4.xml", m.getSchema());
        assertEquals("org.h2.Driver", m.getDriver());
    }

    @Test
    public void bankWithoutEmbeddedParamMapsCorrectly() {
        DataSourceMapper m = new DataSourceMapper(mondrianDs(
                "bank",
                "jdbc:mondrian:Jdbc=jdbc:h2:/data/foodmart;"
                        + "Catalog=file:/data/Bank.xml;JdbcDrivers=org.h2.Driver"));
        assertEquals("MONDRIAN", m.getConnectiontype());
        assertEquals("jdbc:h2:/data/foodmart", m.getJdbcurl());
        assertEquals("file:/data/Bank.xml", m.getSchema());
        assertEquals("org.h2.Driver", m.getDriver());
    }
}
