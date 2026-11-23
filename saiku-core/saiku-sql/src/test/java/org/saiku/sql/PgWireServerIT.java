/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.sql.server.OssieSqlServer;
import org.saiku.sql.server.SqlServerCredentials;
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

    @Test
    public void extendedQueryModeParameterisedSelect() throws Exception {
        // pgjdbc's default is extended query mode — Parse/Bind/Execute rather than simple 'Q'.
        // Uses PreparedStatement so pgjdbc actually sends parameters via Bind (not text-inlined).
        try (Connection remote = openPgClientExtended();
                PreparedStatement ps =
                        remote.prepareStatement("SELECT COUNT(*) FROM SALES.CUSTOMERS WHERE REGION = ?")) {
            ps.setString(1, "North");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
            ps.setString(1, "West");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    private Connection openPgClient() throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties p = new Properties();
        p.setProperty("user", "saiku");
        p.setProperty("password", "");
        p.setProperty("sslmode", "disable");
        // preferQueryMode=simple opts out of extended-mode Parse/Bind/Execute for the two tests
        // that don't need to exercise parameterised queries. simple mode maps stmt.executeQuery
        // to a single 'Q' message.
        p.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + server.getPort() + "/saiku", p);
    }

    @Test
    public void scramAuthAcceptsCorrectPassword() throws Exception {
        try (PgWireServer secured = securedServer();
                Connection remote = openPgClient(secured.getPort(), "bi", "s3cret, with comma");
                Statement s = remote.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM SALES.CUSTOMERS")) {
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1));
        }
    }

    @Test
    public void scramAuthRejectsWrongPassword() throws Exception {
        try (PgWireServer secured = securedServer()) {
            openPgClient(secured.getPort(), "bi", "wrong").close();
            fail("wrong password must not authenticate");
        } catch (SQLException e) {
            assertEquals("28P01", e.getSQLState());
        }
    }

    @Test
    public void scramAuthRejectsWrongUser() throws Exception {
        try (PgWireServer secured = securedServer()) {
            openPgClient(secured.getPort(), "someone-else", "s3cret, with comma")
                    .close();
            fail("unknown user must not authenticate");
        } catch (SQLException e) {
            assertEquals("28P01", e.getSQLState());
        }
    }

    @Test
    public void defaultConstructorBindsToLoopbackOnly() throws Exception {
        // saiku#1910: the trust-mode constructor must never listen on the wildcard address.
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 2000);
        }
        InetAddress external = firstNonLoopbackAddress();
        if (external == null) return; // no other interface on this host to probe
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(external, server.getPort()), 2000);
            fail("PG-wire server reachable on " + external);
        } catch (IOException expected) {
            // connection refused — bound to loopback only
        }
    }

    private PgWireServer securedServer() throws IOException {
        return new PgWireServer(
                "127.0.0.1", 0, calciteConnectString, new SqlServerCredentials("bi", "s3cret, with comma"));
    }

    private static InetAddress firstNonLoopbackAddress() throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
            }
        }
        return null;
    }

    private Connection openPgClient(int port, String user, String password) throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("sslmode", "disable");
        p.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + port + "/saiku", p);
    }

    private Connection openPgClientExtended() throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties p = new Properties();
        p.setProperty("user", "saiku");
        p.setProperty("password", "");
        p.setProperty("sslmode", "disable");
        // No preferQueryMode override — pgjdbc uses its default. PreparedStatement.executeQuery
        // maps to the extended Parse/Bind/Execute/Sync sequence.
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + server.getPort() + "/saiku", p);
    }
}
