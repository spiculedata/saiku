/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
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
import org.saiku.sql.server.pgwire.PgWireServer;

/**
 * End-to-end: start the {@link PgWireServer} bound to an ephemeral port, connect via the
 * <b>real</b> Postgres JDBC driver ({@code org.postgresql:postgresql}), and run the same
 * queries the Avatica IT uses. Proves our wire codec is compatible with a client we didn't
 * write — {@code pgjdbc} is what Tableau/DBeaver/psql all speak internally.
 *
 * <p>Uses {@code sslmode=disable} because this slice replies {@code 'N'} to SSL requests and
 * some pgjdbc versions default to {@code sslmode=prefer} which retries in plaintext (works),
 * but explicit disable avoids the extra round-trip.
 */
public class PgWireServerIT {

    private Connection h2Warehouse;
    private Path ossieYaml;
    private PgWireServer server;
    private String calciteConnectString;

    @Before
    public void setUp() throws Exception {
        h2Warehouse = DriverManager.getConnection("jdbc:h2:mem:pgwireit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        try (Statement s = h2Warehouse.createStatement()) {
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("DROP TABLE IF EXISTS customers");
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, region VARCHAR(32))");
            s.execute("INSERT INTO customers VALUES (1,'North'),(2,'North'),(3,'South'),(4,'West')");
            s.execute("CREATE TABLE orders (order_id INT PRIMARY KEY, customer_id INT, amount DECIMAL(10,2))");
            s.execute("INSERT INTO orders VALUES (1,1,100.00),(2,1,50.00),(3,2,75.00),(4,3,200.00),(5,4,25.00)");
        }
        ossieYaml = Files.createTempFile("ossie-pgwire-", ".yaml");
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
        calciteConnectString = OssieSqlServer.buildCalciteConnectString(
                ossieYaml, "SALES", "jdbc:h2:mem:pgwireit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        server = new PgWireServer(0, calciteConnectString);
    }

    @After
    public void tearDown() throws IOException {
        if (server != null) server.close();
        try {
            if (h2Warehouse != null) h2Warehouse.close();
        } catch (Exception ignored) {
            // Ignore — H2 in-memory DB is being torn down.
        }
        if (ossieYaml != null) Files.deleteIfExists(ossieYaml);
    }

    @Test
    public void pgJdbcClientQueriesOssieSchema() throws Exception {
        try (Connection remote = openPgClient();
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
    public void pgJdbcClientAutoJoins() throws Exception {
        // Full round-trip through PG wire: parser → planner → OssieAutoJoinRule → JDBC pushdown
        // to H2 → results back over PG wire.
        try (Connection remote = openPgClient();
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

    private Connection openPgClient() throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties p = new Properties();
        p.setProperty("user", "saiku");
        p.setProperty("password", "");
        p.setProperty("sslmode", "disable");
        // preferQueryMode=simple keeps pgjdbc from trying extended-mode Parse/Bind/Execute
        // (which we don't yet support). Simple mode is enough for BI tools that don't use
        // parameterised queries.
        p.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + server.getPort() + "/saiku", p);
    }
}
