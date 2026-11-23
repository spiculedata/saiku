/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.JdbcUrlPolicy;

/**
 * saiku#2003 — "datasource connection - error after creating schema - no cube loaded".
 *
 * <p>The reporter pointed a Saiku OLAP datasource at a plain Postgres JDBC URL. Schema creation
 * worked, because {@code DatasourceJdbcConnectionProvider} passes such a location to
 * {@code DriverManager} verbatim. Opening a cube did not: this class appended the Mondrian
 * {@code ';'} property terminator to a URL that is not a property list, so the database name
 * reached the driver as {@code dmt;} and it answered {@code FATAL: database "dmt;" does not exist}.
 * Even past that, a vendor driver's {@link Connection} is not an {@link org.olap4j.OlapWrapper},
 * so the blind cast below failed with an unexplained {@link ClassCastException}.
 */
public class SaikuOlapConnectionLocationTest {

    private static final String TEST_SCHEME = "saikuloc";

    /** Sub-scheme owned by {@link PlainJdbcDriver} — the recording olap driver claims the rest. */
    private static final String PLAIN_SCHEME = "saikuplain";

    private RecordingJdbcDriver olapDriver;
    private PlainJdbcDriver plainDriver;
    /** Real olap4j drivers (XMLA, Mondrian) shadowed for the test, restored afterwards. */
    private final List<Driver> shadowedDrivers = new ArrayList<>();

    @Before
    public void registerDrivers() throws Exception {
        olapDriver = new RecordingJdbcDriver();
        plainDriver = new PlainJdbcDriver();
        // Order matters: DriverManager asks in registration order, and the shared recording driver
        // claims every jdbc: URL. The plain driver answers only its own sub-scheme, so registering
        // it first routes jdbc:saikuplain:… to it and everything else to the recording driver.
        // The real XMLA and Mondrian olap4j drivers register themselves when their jars are on the
        // test classpath, and they sit ahead of the recording driver, so a jdbc:xmla: or
        // jdbc:mondrian: URL would be connected for real (UnknownHostException: host). Take them
        // out of DriverManager for the duration of each test.
        for (Driver real : Collections.list(DriverManager.getDrivers())) {
            if (acceptsAny(real, "jdbc:xmla:Server=http://host/xmla", "jdbc:mondrian:Jdbc=x;Catalog=mondrian://x")) {
                shadowedDrivers.add(real);
                DriverManager.deregisterDriver(real);
            }
        }
        DriverManager.registerDriver(plainDriver);
        DriverManager.registerDriver(olapDriver);
        System.setProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY, TEST_SCHEME + "," + PLAIN_SCHEME);
    }

    private static boolean acceptsAny(Driver driver, String... urls) {
        if (driver instanceof RecordingJdbcDriver || driver instanceof PlainJdbcDriver) {
            return false;
        }
        for (String url : urls) {
            try {
                if (driver.acceptsURL(url)) {
                    return true;
                }
            } catch (java.sql.SQLException ignored) {
                // a driver that cannot answer is not one we need to move aside
            }
        }
        return false;
    }

    @After
    public void deregisterDrivers() throws Exception {
        DriverManager.deregisterDriver(olapDriver);
        DriverManager.deregisterDriver(plainDriver);
        for (Driver real : shadowedDrivers) {
            DriverManager.registerDriver(real);
        }
        shadowedDrivers.clear();
        System.clearProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY);
    }

    /* ------------------------------------------------------------------ terminator decision */

    @Test
    public void plainJdbcUrlIsHandedToTheDriverVerbatim() throws Exception {
        SaikuOlapConnection con = new SaikuOlapConnection("dmt", props("jdbc:" + TEST_SCHEME + "://host:5432/dmt"));
        assertTrue(con.connect());
        assertEquals(
                "a plain driver URL must not gain a ';'",
                "jdbc:" + TEST_SCHEME + "://host:5432/dmt",
                olapDriver.lastUrl);
    }

    @Test
    public void plainJdbcUrlAlreadyCarryingItsOwnParamsIsNotTruncated() throws Exception {
        // The failing shape from the report: the trailing ';' used to be appended AFTER the
        // database name, so the database literally became "dmt;".
        SaikuOlapConnection con =
                new SaikuOlapConnection("dmt", props("jdbc:" + TEST_SCHEME + "://host:5432/dmt?ssl=true"));
        assertTrue(con.connect());
        assertEquals("jdbc:" + TEST_SCHEME + "://host:5432/dmt?ssl=true", olapDriver.lastUrl);
    }

    @Test
    public void mondrianLocationStillGetsThePropertyTerminator() throws Exception {
        SaikuOlapConnection con = new SaikuOlapConnection(
                "mondrian", props("jdbc:mondrian:Jdbc=jdbc:" + TEST_SCHEME + "://host:5432/dmt;Catalog=mondrian://x"));
        assertTrue(con.connect());
        assertEquals(
                "jdbc:mondrian:Jdbc=jdbc:" + TEST_SCHEME + "://host:5432/dmt;Catalog=mondrian://x;",
                olapDriver.lastUrl);
    }

    @Test
    public void xmlaLocationStillGetsThePropertyTerminator() throws Exception {
        SaikuOlapConnection con = new SaikuOlapConnection("xmla", props("jdbc:xmla:Server=http://host/xmla"));
        assertTrue(con.connect());
        assertEquals("jdbc:xmla:Server=http://host/xmla;", olapDriver.lastUrl);
    }

    @Test
    public void mondrianDriverAlwaysGetsTheTerminator_soJdbcUserCanBeAppended() throws Exception {
        Properties p = props("jdbc:" + TEST_SCHEME + "://host:5432/dmt");
        p.setProperty(ISaikuConnection.DRIVER_KEY, "mondrian.olap4j.MondrianOlap4jDriver");
        assertTrue(
                "the Mondrian driver appends JdbcUser=/JdbcPassword=, which needs the ';'",
                SaikuOlapConnection.needsPropertyTerminator(
                        "jdbc:" + TEST_SCHEME + "://host:5432/dmt", p.getProperty(ISaikuConnection.DRIVER_KEY)));
    }

    @Test
    public void terminatorDecision() {
        assertFalse(
                SaikuOlapConnection.needsPropertyTerminator("jdbc:postgresql://h:5432/dmt", "org.postgresql.Driver"));
        assertFalse(SaikuOlapConnection.needsPropertyTerminator(
                "jdbc:oracle:thin:@h:1521/svc", "oracle.jdbc.OracleDriver"));
        assertFalse(SaikuOlapConnection.needsPropertyTerminator("jdbc:h2:mem:foodmart", "org.h2.Driver"));
        assertFalse(SaikuOlapConnection.needsPropertyTerminator(null, "org.h2.Driver"));
        assertTrue(SaikuOlapConnection.needsPropertyTerminator("jdbc:mondrian:Jdbc=jdbc:h2:mem:x", "x.Driver"));
        assertTrue(SaikuOlapConnection.needsPropertyTerminator("mondrian://datasources/f.json;Catalog=c", "x.Driver"));
        // a ';' inside a plain URL's own params is a property list, exactly as it always was
        assertTrue(SaikuOlapConnection.needsPropertyTerminator("jdbc:h2:mem:x;MODE=MySQL", "org.h2.Driver"));
    }

    /* ------------------------------------------------------------------ no OLAP schema behind a vendor driver */

    @Test
    public void plainVendorConnectionExplainsThatNoCubeCanBeLoaded() throws Exception {
        SaikuOlapConnection con = new SaikuOlapConnection(
                "dmt", props("jdbc:" + PLAIN_SCHEME + "://host:5432/dmt", PlainJdbcDriver.class.getName()));
        try {
            con.connect();
            fail("a plain JDBC connection cannot serve cubes");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("dmt"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("no cube can be loaded"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("Schema Generator"));
        }
        assertFalse(con.initialized());
        assertTrue("the rejected connection must not be leaked", plainDriver.closed);
    }

    @Test
    public void missingDriverClassIsNoLongerAnNpe() throws Exception {
        Properties p = new Properties();
        p.setProperty(ISaikuConnection.URL_KEY, "jdbc:" + TEST_SCHEME + "://host:5432/dmt");
        SaikuOlapConnection con = new SaikuOlapConnection("dmt", p);
        try {
            con.connect();
            fail("a driver-less descriptor cannot connect");
        } catch (NullPointerException npe) {
            fail("a missing driver property must not blow up with an NPE");
        } catch (Exception expected) {
            // any other diagnostic is acceptable — the point is that it is not an NPE
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private static Properties props(String location) {
        return props(location, RecordingJdbcDriver.class.getName());
    }

    private static Properties props(String location, String driverClass) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.DRIVER_KEY, driverClass);
        props.setProperty(ISaikuConnection.URL_KEY, location);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        return props;
    }

    /** A driver whose {@link Connection} is a plain JDBC one — no {@code OlapWrapper}. */
    public static final class PlainJdbcDriver implements Driver {

        volatile boolean closed;

        @Override
        public Connection connect(String url, Properties info) {
            return acceptsURL(url) ? plainConnection() : null;
        }

        @Override
        public boolean acceptsURL(String url) {
            // Own one sub-scheme only, so the shared recording driver still answers everything else.
            return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:" + PLAIN_SCHEME + ":");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        private Connection plainConnection() {
            return (Connection) Proxy.newProxyInstance(
                    PlainJdbcDriver.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            closed = true;
                            return null;
                        }
                        if (method.getName().equals("isClosed")) {
                            return closed;
                        }
                        if (method.getName().equals("toString")) {
                            return "plain-jdbc-connection";
                        }
                        return defaultValue(method);
                    });
        }

        private Object defaultValue(Method method) {
            Class<?> type = method.getReturnType();
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class || type == short.class || type == byte.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            if (type == char.class) {
                return (char) 0;
            }
            return null;
        }
    }
}
