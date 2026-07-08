/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql;

import static org.junit.Assert.assertEquals;
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
import org.saiku.sql.server.OssieSqlServer;

/**
 * End-to-end: start the {@link OssieSqlServer} bound to an ephemeral port, connect via the
 * Avatica remote JDBC driver, and run the same aggregate + JOIN queries {@link
 * SelectPushdownIT} uses. Proves the wire path works — network endpoint parses SQL, dispatches
 * to Calcite, executes against the H2 warehouse, returns results back over Avatica's protobuf.
 */
public class OssieSqlServerIT {

    private Connection h2Warehouse;
    private Path ossieYaml;
    private OssieSqlServer server;

    @Before
    public void setUp() throws Exception {
        // Same fixture as SelectPushdownIT — H2 warehouse + Ossie YAML matching its shape.
        h2Warehouse =
                DriverManager.getConnection("jdbc:h2:mem:sqlserverit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        try (Statement s = h2Warehouse.createStatement()) {
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("DROP TABLE IF EXISTS customers");
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, region VARCHAR(32))");
            s.execute("INSERT INTO customers VALUES (1,'North'),(2,'North'),(3,'South'),(4,'West')");
            s.execute("CREATE TABLE orders (order_id INT PRIMARY KEY, customer_id INT, amount DECIMAL(10,2))");
            s.execute("INSERT INTO orders VALUES (1,1,100.00),(2,1,50.00),(3,2,75.00),(4,3,200.00),(5,4,25.00)");
        }
        ossieYaml = Files.createTempFile("ossie-sqlserver-", ".yaml");
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
        // Bind to port 0 — OS assigns an ephemeral port; getPort() reports what we got.
        server = new OssieSqlServer(
                0, ossieYaml, "SALES", "jdbc:h2:mem:sqlserverit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.close();
        if (h2Warehouse != null) h2Warehouse.close();
        if (ossieYaml != null) Files.deleteIfExists(ossieYaml);
    }

    @Test
    public void remoteAvaticaClientQueriesOssieSchema() throws Exception {
        try (Connection remote = openAvaticaClient();
                Statement s = remote.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT REGION, COUNT(*) AS N FROM SALES.CUSTOMERS GROUP BY REGION ORDER BY REGION")) {
            assertTrue(rs.next());
            assertEquals("North", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("South", rs.getString(1));
            assertEquals(1, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("West", rs.getString(1));
            assertEquals(1, rs.getInt(2));
        }
    }

    @Test
    public void remoteAvaticaClientAutoJoins() throws Exception {
        // Cartesian query — auto-join rule should fire over the wire just as it does locally.
        try (Connection remote = openAvaticaClient();
                Statement s = remote.createStatement();
                ResultSet rs = s.executeQuery("SELECT c.REGION, SUM(o.AMOUNT) AS TOTAL "
                        + "FROM SALES.ORDERS o, SALES.CUSTOMERS c "
                        + "GROUP BY c.REGION ORDER BY c.REGION")) {
            assertTrue(rs.next());
            assertEquals("North", rs.getString(1));
            assertEquals(225.00, rs.getBigDecimal(2).doubleValue(), 0.001);
            assertTrue(rs.next());
            assertEquals("South", rs.getString(1));
            assertEquals(200.00, rs.getBigDecimal(2).doubleValue(), 0.001);
            assertTrue(rs.next());
            assertEquals("West", rs.getString(1));
            assertEquals(25.00, rs.getBigDecimal(2).doubleValue(), 0.001);
        }
    }

    private Connection openAvaticaClient() throws Exception {
        // Force-load the Avatica remote driver so DriverManager finds it.
        Class.forName("org.apache.calcite.avatica.remote.Driver");
        Properties p = new Properties();
        p.setProperty("serialization", "protobuf");
        return DriverManager.getConnection("jdbc:avatica:remote:url=" + server.getUrl(), p);
    }
}
