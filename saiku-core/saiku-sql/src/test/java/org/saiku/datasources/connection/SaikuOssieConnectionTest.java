/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end round-trip for the Ossie datasource path: build a Properties bag matching what an
 * OSSIE {@code .sds} would produce → open a {@link SaikuOssieConnection} → run a SQL query
 * against a small H2 warehouse. Proves the connect-string builder wires
 * {@code jdbc:calcite:model=...} correctly and the saiku-sql {@code OssieSchemaFactory} loads
 * via reflection.
 *
 * <p>Kept in the test tree of saiku-service so the module's own datasource wiring is exercised
 * — the parallel {@code PharmaEndToEndIT} in saiku-sql exercises the SchemaFactory itself.
 */
public class SaikuOssieConnectionTest {

    private static final String H2_URL = "jdbc:h2:mem:saiku_ossie_conn_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Connection h2;
    private Path ossieYaml;

    @Before
    public void setUp() throws Exception {
        h2 = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement s = h2.createStatement()) {
            s.execute("DROP TABLE IF EXISTS ORDERS");
            s.execute("DROP TABLE IF EXISTS CUSTOMERS");
            s.execute("CREATE TABLE CUSTOMERS (ID INT PRIMARY KEY, REGION VARCHAR(32))");
            s.execute("INSERT INTO CUSTOMERS VALUES (1,'North'),(2,'North'),(3,'South')");
            s.execute("CREATE TABLE ORDERS (ORDER_ID INT PRIMARY KEY, CUSTOMER_ID INT, AMOUNT DECIMAL(10,2))");
            s.execute("INSERT INTO ORDERS VALUES (1,1,100.00),(2,2,50.00),(3,3,25.00)");
        }
        ossieYaml = Files.createTempFile("saiku-ossie-conn-", ".yaml");
        Files.writeString(
                ossieYaml,
                "version: 0.2.0.dev0\n"
                        + "semantic_model:\n"
                        + "- name: SALES\n"
                        + "  datasets:\n"
                        + "  - name: CUSTOMERS\n"
                        + "    source: CUSTOMERS\n"
                        + "  - name: ORDERS\n"
                        + "    source: ORDERS\n"
                        + "  relationships:\n"
                        + "  - name: orders_to_customers\n"
                        + "    from: ORDERS\n"
                        + "    to: CUSTOMERS\n"
                        + "    from_columns: [CUSTOMER_ID]\n"
                        + "    to_columns: [ID]\n");
    }

    @After
    public void tearDown() throws Exception {
        if (h2 != null) h2.close();
        if (ossieYaml != null) Files.deleteIfExists(ossieYaml);
    }

    @Test
    public void connectAndQueryOssieModel() throws Exception {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.OSSIE_YAML_KEY, ossieYaml.toString());
        props.setProperty(ISaikuConnection.URL_KEY, H2_URL);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        props.setProperty("schema", "SALES");

        SaikuOssieConnection conn = new SaikuOssieConnection("SALES", props);
        assertTrue("connect() should return true against a live H2", conn.connect());
        assertTrue(conn.initialized());
        assertEquals("OSSIE", conn.getDatasourceType());

        Connection calcite = conn.getConnection();
        assertNotNull(calcite);
        try (Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT REGION, COUNT(*) FROM SALES.CUSTOMERS GROUP BY REGION ORDER BY REGION")) {
            assertTrue(rs.next());
            assertEquals("North", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("South", rs.getString(1));
            assertEquals(1, rs.getInt(2));
            assertFalse(rs.next());
        }
    }

    @Test
    public void safemodeShortCircuitsConnect() throws Exception {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.OSSIE_YAML_KEY, ossieYaml.toString());
        props.setProperty(ISaikuConnection.URL_KEY, H2_URL);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        props.setProperty("schema", "SALES");
        String previous = System.getProperty("saiku.safemode");
        try {
            System.setProperty("saiku.safemode", "true");
            SaikuOssieConnection conn = new SaikuOssieConnection("SALES", props);
            assertFalse("safemode must refuse the connect", conn.connect());
            assertFalse(conn.initialized());
        } finally {
            if (previous == null) {
                System.clearProperty("saiku.safemode");
            } else {
                System.setProperty("saiku.safemode", previous);
            }
        }
    }
}
