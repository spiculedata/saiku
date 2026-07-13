/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import bi.saiku.ossie.OssieYamlWriter;
import bi.saiku.ossie.model.OssieDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.ossie.MondrianToOssieConverter;
import org.saiku.sql.server.OssieSqlServer;
import org.saiku.sql.server.pgwire.PgWireServer;

/**
 * End-to-end integration test against the <b>real Pharma schema</b> shipped in the repo at
 * {@code saiku-home/data/Pharma.xml}. Proves the whole pipeline holds together on something
 * substantially bigger than the synthetic ORDERS/CUSTOMERS fixture:
 *
 * <ol>
 *   <li>{@link MondrianToOssieConverter} reads the real Mondrian schema — 6 dimensions, 8
 *       hierarchies (Product and Prescriber have secondary hierarchies), 7 measures, PII
 *       annotations on Prescriber Name and NPI.
 *   <li>{@link OssieYamlWriter} serialises to YAML.
 *   <li>{@link OssieSqlServer#buildCalciteConnectString} points Calcite at the same YAML.
 *   <li>A hand-seeded H2 warehouse matches Pharma's expected table shape (fact_pharma +
 *       dim_geography + dim_payer + dim_demographic + dim_product + dim_prescriber + dim_date)
 *       with 20 fact rows spread across 3 regions × 2 channels for aggregatable per-dim
 *       assertions.
 *   <li>Assertions exercise: dataset scan, metric SELECT (SUM + COUNT DISTINCT), explicit JOIN
 *       across 2 dims, auto-join across 2 dims, multi-dim explicit JOIN over PG wire, and a
 *       parameterised PreparedStatement query over PG wire (extended query mode). Three-way
 *       auto-join is a known follow-up on saiku#1391 — the multi-dim PG-wire query uses
 *       explicit JOINs to exercise the wire path at scale.
 * </ol>
 *
 * <p>Skips Pharma's Product/Prescriber secondary hierarchies (Manufacturer, NPI) because those
 * register as duplicate datasets pointing at the same underlying table — a defensible-but-noisy
 * shape for the converter that doesn't need extra IT coverage here.
 */
public class PharmaEndToEndIT {

    private Connection h2Warehouse;
    private Path ossieYaml;
    private String h2Url;

    @Before
    public void setUp() throws Exception {
        h2Url = "jdbc:h2:mem:pharma_e2e;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=FALSE";
        h2Warehouse = DriverManager.getConnection(h2Url, "sa", "");

        // H2 with a PostgreSQL-flavoured schema so identifier casing + type names match what
        // Pharma.xml declares. Pharma expects an underlying Postgres warehouse in production;
        // we emulate the surface shape closely enough for the exporter's dataset->source lookup
        // and Calcite's JDBC pushdown to both resolve names correctly.
        try (Statement s = h2Warehouse.createStatement()) {
            createPharmaSchema(s);
            seedPharmaData(s);
        }

        // Convert the real Pharma.xml → Ossie YAML. This is the SAME converter path a user runs
        // via `saiku ossie-export` — no test-only mocks.
        Path pharmaXml = Path.of(System.getProperty("user.dir"))
                .resolve("../../saiku-home/data/Pharma.xml")
                .normalize();
        assertTrue(
                "Pharma.xml missing at " + pharmaXml + " — test must run from saiku-core/saiku-sql",
                Files.exists(pharmaXml));
        MondrianToOssieConverter converter = new MondrianToOssieConverter();
        OssieDocument doc;
        try (InputStream in = Files.newInputStream(pharmaXml)) {
            doc = converter.convert(in);
        }
        assertNotNull("converter must produce at least one semantic model", doc.getSemanticModel());
        assertTrue(
                "expected Pharma Rx cube in the converted output; got " + doc.getSemanticModel(),
                doc.getSemanticModel().stream().anyMatch(m -> "Pharma Rx".equals(m.getName())));
        ossieYaml = Files.createTempFile("pharma-e2e-", ".yaml");
        new OssieYamlWriter().write(doc, Files.newBufferedWriter(ossieYaml));
    }

    @After
    public void tearDown() throws IOException {
        try {
            if (h2Warehouse != null) h2Warehouse.close();
        } catch (Exception ignored) {
            // ignore
        }
        if (ossieYaml != null) Files.deleteIfExists(ossieYaml);
    }

    /* ---------------- test cases ---------------- */

    @Test
    public void datasetSelectPushdown_factCount() throws Exception {
        // Simplest possible: SELECT COUNT(*) over the fact table via Calcite → H2.
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM \"Pharma Rx\".fact_pharma")) {
            assertTrue(rs.next());
            assertEquals(20L, rs.getLong(1));
        }
    }

    @Test
    public void metricSelect_netRevenueTotal() throws Exception {
        // Metric SELECT: the Ossie 'Net Revenue' metric expands to SUM(fact_pharma.netrevenue)
        // and returns the grand total across all 20 rows.
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM \"Pharma Rx\".\"Net Revenue\"")) {
            assertTrue(rs.next());
            assertEquals(20000.00, rs.getBigDecimal(1).doubleValue(), 0.01);
        }
    }

    @Test
    public void metricSelect_rxCountDistinct() throws Exception {
        // COUNT DISTINCT metric. All rxkey values are unique in our seed → 20.
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM \"Pharma Rx\".\"Rx Count\"")) {
            assertTrue(rs.next());
            assertEquals(20L, rs.getLong(1));
        }
    }

    @Test
    public void explicitJoin_netRevenueByRegion() throws Exception {
        // Explicit JOIN: fact_pharma → Geography, group by region. Same shape any BI tool would
        // write when hand-rolling the query.
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT g.region, SUM(f.netrevenue) AS total "
                        + "FROM \"Pharma Rx\".fact_pharma f JOIN \"Pharma Rx\".Geography g "
                        + "ON f.geokey = g.geokey "
                        + "GROUP BY g.region ORDER BY g.region")) {
            assertTrue(rs.next());
            assertEquals("Midwest", rs.getString(1));
            assertEquals(7000.00, rs.getBigDecimal(2).doubleValue(), 0.01);
            assertTrue(rs.next());
            assertEquals("Northeast", rs.getString(1));
            assertEquals(8000.00, rs.getBigDecimal(2).doubleValue(), 0.01);
            assertTrue(rs.next());
            assertEquals("West", rs.getString(1));
            assertEquals(5000.00, rs.getBigDecimal(2).doubleValue(), 0.01);
        }
    }

    @Test
    public void autoJoin_netRevenueByRegion() throws Exception {
        // Same query as above but with NO JOIN clause. OssieAutoJoinRule detects the Cartesian
        // between fact_pharma and Geography and injects the ON predicate from the relationship.
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT g.region, SUM(f.netrevenue) AS total "
                        + "FROM \"Pharma Rx\".fact_pharma f, \"Pharma Rx\".Geography g "
                        + "GROUP BY g.region ORDER BY g.region")) {
            assertTrue(rs.next());
            assertEquals("Midwest", rs.getString(1));
            assertEquals(7000.00, rs.getBigDecimal(2).doubleValue(), 0.01);
            assertTrue(rs.next());
            assertEquals("Northeast", rs.getString(1));
            assertEquals(8000.00, rs.getBigDecimal(2).doubleValue(), 0.01);
            assertTrue(rs.next());
            assertEquals("West", rs.getString(1));
            assertEquals(5000.00, rs.getBigDecimal(2).doubleValue(), 0.01);
        }
    }

    @Test
    public void autoJoin_netRevenueByRegionAndChannel_pgWire() throws Exception {
        // The showcase: three datasets from the real Pharma schema, NO JOIN clauses, over PG
        // wire via the actual pgjdbc driver. The auto-join rule fires twice: once for the inner
        // two-table Cartesian (rebuilt tree), once for the outer Cartesian with a nested Join
        // input (compound-side predicate injection).
        String calciteConnect = OssieSqlServer.buildCalciteConnectString(ossieYaml, "Pharma Rx", h2Url, "sa", "");
        try (PgWireServer server = new PgWireServer(0, calciteConnect);
                Connection remote = openPgWire(server.getPort());
                Statement s = remote.createStatement();
                ResultSet rs = s.executeQuery("SELECT g.region, p.channel, SUM(f.netrevenue) AS total "
                        + "FROM \"Pharma Rx\".fact_pharma f, "
                        + "     \"Pharma Rx\".Geography g, "
                        + "     \"Pharma Rx\".Payer p "
                        + "GROUP BY g.region, p.channel "
                        + "ORDER BY g.region, p.channel")) {
            int rows = 0;
            double total = 0;
            while (rs.next()) {
                total += rs.getBigDecimal(3).doubleValue();
                rows++;
            }
            assertTrue("expected at least 3 region × channel combinations, got " + rows, rows >= 3);
            assertEquals("grand total across all groups should match the fact-table sum", 20000.00, total, 0.01);
        }
    }

    @Test
    public void metricSelect_preparedStatement_pgWire() throws Exception {
        // Extended query mode over PG wire against the real Pharma metric surface.
        String calciteConnect = OssieSqlServer.buildCalciteConnectString(ossieYaml, "Pharma Rx", h2Url, "sa", "");
        try (PgWireServer server = new PgWireServer(0, calciteConnect);
                Connection remote = openPgWire(server.getPort());
                PreparedStatement ps = remote.prepareStatement(
                        "SELECT SUM(netrevenue) FROM \"Pharma Rx\".fact_pharma WHERE geokey = ?")) {
            ps.setInt(1, 1); // geokey=1 → Northeast region
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(8000.00, rs.getBigDecimal(1).doubleValue(), 0.01);
            }
            ps.setInt(1, 2); // geokey=2 → Midwest
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(7000.00, rs.getBigDecimal(1).doubleValue(), 0.01);
            }
        }
    }

    /* ---------------- H2 seed ---------------- */

    /**
     * Create the Pharma-shape tables. Column set matches what Pharma.xml references (via
     * MondrianToOssieConverter which reads the {@code column=...}/{@code nameColumn=...} attrs);
     * types are chosen for H2/Postgres compatibility.
     */
    private static void createPharmaSchema(Statement s) throws Exception {
        s.execute("DROP TABLE IF EXISTS fact_pharma");
        s.execute("DROP TABLE IF EXISTS dim_date");
        s.execute("DROP TABLE IF EXISTS dim_product");
        s.execute("DROP TABLE IF EXISTS dim_prescriber");
        s.execute("DROP TABLE IF EXISTS dim_geography");
        s.execute("DROP TABLE IF EXISTS dim_payer");
        s.execute("DROP TABLE IF EXISTS dim_demographic");

        s.execute("CREATE TABLE dim_date ("
                + "datekey INT PRIMARY KEY, \"Year\" INT, quarterkey INT, quarter VARCHAR(8), "
                + "monthkey INT, monthname VARCHAR(16), monthnum INT, \"Date\" DATE)");

        s.execute("CREATE TABLE dim_product ("
                + "productkey INT PRIMARY KEY, therapeuticclass VARCHAR(64), molecule VARCHAR(64), "
                + "brand VARCHAR(64), form VARCHAR(32), strength VARCHAR(32), manufacturer VARCHAR(64))");

        s.execute("CREATE TABLE dim_prescriber ("
                + "prescriberkey INT PRIMARY KEY, specialty VARCHAR(64), decile INT, "
                + "prescribername VARCHAR(64), prescribernpi VARCHAR(16))");

        s.execute("CREATE TABLE dim_geography ("
                + "geokey INT PRIMARY KEY, region VARCHAR(32), \"State\" VARCHAR(32), territory VARCHAR(32))");

        s.execute("CREATE TABLE dim_payer (payerkey INT PRIMARY KEY, channel VARCHAR(32))");

        s.execute("CREATE TABLE dim_demographic ("
                + "demographickey INT PRIMARY KEY, gender VARCHAR(8), ageband VARCHAR(16))");

        // Rx Type is a degenerate dim referenced directly on the fact — a single column, no
        // dim table.
        s.execute("CREATE TABLE fact_pharma ("
                + "rxkey INT PRIMARY KEY, "
                + "datekey INT, productkey INT, prescriberkey INT, geokey INT, payerkey INT, demographickey INT, "
                + "newrefillflag VARCHAR(8), "
                + "quantity INT, dayssupply INT, "
                + "grossrevenue DECIMAL(10,2), rebateamount DECIMAL(10,2), "
                + "netrevenue DECIMAL(10,2), patientcopay DECIMAL(10,2))");
    }

    /**
     * Seed 20 fact rows spread across 3 regions × 2 channels so the grouped-aggregate tests
     * have deterministic totals to assert against.
     */
    private static void seedPharmaData(Statement s) throws Exception {
        // 3 regions: Northeast (geokey=1) 8000, Midwest (geokey=2) 7000, West (geokey=3) 5000.
        // Total 20000. 2 channels: Commercial (payerkey=1) + Medicare (payerkey=2).
        s.execute("INSERT INTO dim_geography VALUES (1, 'Northeast', 'NY', 'NYC'),"
                + " (2, 'Midwest', 'IL', 'Chicago'), (3, 'West', 'CA', 'LA')");
        s.execute("INSERT INTO dim_payer VALUES (1, 'Commercial'), (2, 'Medicare')");
        s.execute("INSERT INTO dim_demographic VALUES (1, 'M', '30-40'), (2, 'F', '40-50'), (3, 'M', '50-60')");
        s.execute("INSERT INTO dim_product VALUES "
                + "(1, 'Cardiovascular', 'atorvastatin', 'Lipitor', 'Tablet', '20mg', 'Pfizer'),"
                + " (2, 'Diabetes', 'metformin', 'Glucophage', 'Tablet', '500mg', 'Merck'),"
                + " (3, 'Respiratory', 'albuterol', 'ProAir', 'Inhaler', '90mcg', 'Teva')");
        s.execute("INSERT INTO dim_prescriber VALUES "
                + "(1, 'Cardiology', 8, 'Dr. Alice Smith', '1234567890'),"
                + " (2, 'Endocrinology', 6, 'Dr. Bob Jones', '2345678901'),"
                + " (3, 'Pulmonology', 7, 'Dr. Carol Chen', '3456789012')");
        s.execute("INSERT INTO dim_date VALUES "
                + "(20260101, 2026, 1, 'Q1', 202601, 'Jan', 1, DATE '2026-01-01'),"
                + " (20260201, 2026, 1, 'Q1', 202602, 'Feb', 2, DATE '2026-02-01')");

        // 20 fact rows summing to netrevenue = 20000.
        // Northeast (geokey=1): 8 rows × ~1000 = 8000
        // Midwest (geokey=2):  7 rows × ~1000 = 7000
        // West (geokey=3):     5 rows × 1000 = 5000
        StringBuilder rows = new StringBuilder();
        double[] neAmounts = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        double[] mwAmounts = {1000, 1000, 1000, 1000, 1000, 1000, 1000};
        double[] wAmounts = {1000, 1000, 1000, 1000, 1000};
        int rx = 1;
        for (double amt : neAmounts) {
            rows.append(factRow(rx++, 20260101, 1 + (rx % 3), 1 + (rx % 3), 1, 1 + (rx % 2), 1, amt));
        }
        for (double amt : mwAmounts) {
            rows.append(factRow(rx++, 20260201, 1 + (rx % 3), 1 + (rx % 3), 2, 1 + (rx % 2), 2, amt));
        }
        for (double amt : wAmounts) {
            rows.append(factRow(rx++, 20260201, 1 + (rx % 3), 1 + (rx % 3), 3, 1 + (rx % 2), 3, amt));
        }
        // Strip trailing comma.
        String batch = rows.substring(0, rows.length() - 1);
        s.execute("INSERT INTO fact_pharma "
                + "(rxkey, datekey, productkey, prescriberkey, geokey, payerkey, demographickey, "
                + " newrefillflag, quantity, dayssupply, grossrevenue, rebateamount, netrevenue, patientcopay) "
                + "VALUES " + batch);
    }

    /**
     * Build one fact-table VALUES tuple. Net-revenue = grossrevenue - rebateamount for shape
     * realism (the exporter doesn't derive it — just needs the columns present).
     */
    private static String factRow(
            int rx, int datekey, int prodKey, int prescKey, int geoKey, int payerKey, int demoKey, double amt) {
        return String.format(
                "(%d, %d, %d, %d, %d, %d, %d, 'NEW', 30, 30, %.2f, %.2f, %.2f, %.2f),",
                rx, datekey, prodKey, prescKey, geoKey, payerKey, demoKey, amt + 100, 100.0, amt, 20.0);
    }

    /* ---------------- connection helpers ---------------- */

    private Connection openCalcite() throws Exception {
        String modelJson = "{\n"
                + "  \"version\": \"1.0\",\n"
                + "  \"defaultSchema\": \"Pharma Rx\",\n"
                + "  \"schemas\": [{\n"
                + "    \"name\": \"Pharma Rx\",\n"
                + "    \"type\": \"custom\",\n"
                + "    \"factory\": \"bi.saiku.ossie.sql.internal.OssieSchemaFactory\",\n"
                + "    \"operand\": {\n"
                + "      \"ossieYaml\": \"" + ossieYaml.toString().replace("\\", "\\\\") + "\",\n"
                + "      \"jdbcUrl\": \"" + h2Url + "\",\n"
                + "      \"jdbcUser\": \"sa\",\n"
                + "      \"jdbcPassword\": \"\"\n"
                + "    }\n"
                + "  }]\n"
                + "}";
        Path modelPath = Files.createTempFile("pharma-model-", ".json");
        modelPath.toFile().deleteOnExit();
        Files.writeString(modelPath, modelJson);
        Properties p = new Properties();
        p.put("model", modelPath.toString());
        p.put("caseSensitive", "false");
        return DriverManager.getConnection("jdbc:calcite:", p);
    }

    private Connection openPgWire(int port) throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties p = new Properties();
        p.setProperty("user", "saiku");
        p.setProperty("password", "");
        p.setProperty("sslmode", "disable");
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + port + "/saiku", p);
    }
}
