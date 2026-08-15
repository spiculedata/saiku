/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * saiku#1843 — creating a data source with no password must not blow up.
 *
 * <p>{@code Properties.setProperty} throws NPE on a null value, and the admin form sends null for
 * an empty field. So {@code toSaikuDataSource()} threw a bare {@link NullPointerException} for any
 * password-less source — H2 with {@code sa}, Postgres on trust auth — and the REST layer turned
 * that into an opaque HTTP 500 with no indication of the cause.
 *
 * <p>The Ossie branch of the same method already null-guarded these two fields; the Mondrian /
 * XMLA branch never did.
 */
public class DataSourceMapperNullCredentialsTest {

    private static DataSourceMapper mondrianMapper(String username, String password) {
        DataSourceMapper m = new DataSourceMapper();
        m.setConnectionname("scratch");
        m.setConnectiontype("MONDRIAN");
        m.setJdbcurl("jdbc:h2:/data/foodmart");
        m.setDriver("org.h2.Driver");
        m.setSchema("file:/data/FoodMart4.xml");
        m.setUsername(username);
        m.setPassword(password);
        return m;
    }

    @Test
    public void mondrianSourceWithNoPasswordMapsInsteadOfThrowing() {
        SaikuDatasource ds = mondrianMapper("sa", null).toSaikuDataSource();
        Properties p = ds.getProperties();
        assertEquals("sa", p.getProperty("username"));
        // Absent rather than empty — consumers read via getProperty, which is null-safe,
        // and this matches how the Ossie branch has always handled it.
        assertNull(p.getProperty("password"));
    }

    @Test
    public void mondrianSourceWithNeitherCredentialMapsInsteadOfThrowing() {
        SaikuDatasource ds = mondrianMapper(null, null).toSaikuDataSource();
        Properties p = ds.getProperties();
        assertNull(p.getProperty("username"));
        assertNull(p.getProperty("password"));
        // The parts that matter are still assembled.
        assertEquals("mondrian.olap4j.MondrianOlap4jDriver", p.getProperty("driver"));
    }

    @Test
    public void credentialsAreStillCarriedWhenSupplied() {
        SaikuDatasource ds = mondrianMapper("sa", "secret").toSaikuDataSource();
        Properties p = ds.getProperties();
        assertEquals("sa", p.getProperty("username"));
        assertEquals("secret", p.getProperty("password"));
    }
}
