/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
 * End-to-end integration test: an H2 in-JVM warehouse holds a synthetic {@code orders} + {@code
 * customers} pair; a Calcite JDBC connection reads them through {@code OssieSchemaFactory}
 * pointed at an Ossie YAML that mirrors the H2 shape; the assertions verify the SELECT lands
 * against real H2 rows.
 *
 * <p>Named {@code *IT} so surefire skips it during {@code mvn test} — pick it up explicitly with
 * {@code mvn verify -pl saiku-core/saiku-sql} once we wire the failsafe plugin, or run it
 * directly from the IDE. The rest of the module ships {@code mvn test} green.
 */
public class SelectPushdownIT {

    private Connection h2Warehouse;
    private Path ossieYaml;

    @Before
    public void setUp() throws Exception {
        // H2 warehouse — in-memory DB reused across all queries in this test method.
        h2Warehouse =
                DriverManager.getConnection("jdbc:h2:mem:selectpushdown;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        try (Statement s = h2Warehouse.createStatement()) {
            // `DB_CLOSE_DELAY=-1` keeps the H2 in-memory DB alive between test methods, so drop
            // tables up front to make each @Before idempotent (H2 otherwise 42101s when the
            // second test tries to recreate them).
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("DROP TABLE IF EXISTS customers");
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, region VARCHAR(32))");
            s.execute("INSERT INTO customers VALUES (1,'North'),(2,'North'),(3,'South'),(4,'West')");
            s.execute("CREATE TABLE orders (order_id INT PRIMARY KEY, customer_id INT, amount DECIMAL(10,2))");
            s.execute("INSERT INTO orders VALUES " + "(1,1,100.00),(2,1,50.00),(3,2,75.00),(4,3,200.00),(5,4,25.00)");
        }

        // Ossie YAML — hand-authored to match the H2 shape (dataset.source maps to the H2 table).
        ossieYaml = Files.createTempFile("ossie-select-pushdown-", ".yaml");
        String yaml = "version: 0.2.0.dev0\n"
                + "semantic_model:\n"
                + "- name: SALES\n"
                + "  datasets:\n"
                + "  - name: CUSTOMERS\n"
                + "    source: CUSTOMERS\n"
                + "    primary_key: [ID]\n"
                + "    fields:\n"
                + "    - name: ID\n"
                + "      expression:\n"
                + "        dialects:\n"
                + "        - dialect: ANSI_SQL\n"
                + "          expression: ID\n"
                + "    - name: REGION\n"
                + "      expression:\n"
                + "        dialects:\n"
                + "        - dialect: ANSI_SQL\n"
                + "          expression: REGION\n"
                + "  - name: ORDERS\n"
                + "    source: ORDERS\n"
                + "    primary_key: [ORDER_ID]\n"
                + "    fields:\n"
                + "    - name: ORDER_ID\n"
                + "      expression:\n"
                + "        dialects:\n"
                + "        - dialect: ANSI_SQL\n"
                + "          expression: ORDER_ID\n"
                + "    - name: CUSTOMER_ID\n"
                + "      expression:\n"
                + "        dialects:\n"
                + "        - dialect: ANSI_SQL\n"
                + "          expression: CUSTOMER_ID\n"
                + "    - name: AMOUNT\n"
                + "      expression:\n"
                + "        dialects:\n"
                + "        - dialect: ANSI_SQL\n"
                + "          expression: AMOUNT\n"
                + "  relationships:\n"
                + "  - name: orders_to_customers\n"
                + "    from: ORDERS\n"
                + "    to: CUSTOMERS\n"
                + "    from_columns: [CUSTOMER_ID]\n"
                + "    to_columns: [ID]\n"
                + "  metrics:\n"
                + "  - name: TOTAL_REVENUE\n"
                + "    expression:\n"
                + "      dialects:\n"
                + "      - dialect: ANSI_SQL\n"
                + "        expression: SUM(ORDERS.AMOUNT)\n"
                + "      - dialect: MDX\n"
                + "        expression: \"[Measures].[Total Revenue]\"\n"
                + "  - name: ORDER_COUNT\n"
                + "    expression:\n"
                + "      dialects:\n"
                + "      - dialect: ANSI_SQL\n"
                + "        expression: COUNT(ORDERS.ORDER_ID)\n"
                + "  - name: MDX_ONLY_METRIC\n"
                + "    expression:\n"
                + "      dialects:\n"
                + "      - dialect: MDX\n"
                + "        expression: \"[Measures].[X] / [Measures].[Y]\"\n";
        Files.writeString(ossieYaml, yaml);
    }

    @After
    public void tearDown() throws Exception {
        if (h2Warehouse != null) h2Warehouse.close();
        if (ossieYaml != null) Files.deleteIfExists(ossieYaml);
    }

    @Test
    public void selectFromDatasetPushesToWarehouse() throws Exception {
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
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
            assertTrue("expected exactly 3 regions", !rs.next());
        }
    }

    @Test
    public void joinAcrossDatasetsSumsRevenueByRegion() throws Exception {
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT c.REGION, SUM(o.AMOUNT) AS TOTAL "
                        + "FROM SALES.ORDERS o JOIN SALES.CUSTOMERS c ON o.CUSTOMER_ID = c.ID "
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
    public void metricSelectExpandsAnsiAggregate() throws Exception {
        // TOTAL_REVENUE expands to SUM(ORDERS.AMOUNT); Calcite treats the OssieMetricViewTable
        // as a scalar view, so SELECT * from it returns one row with the grand total (450.00).
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM SALES.TOTAL_REVENUE")) {
            assertTrue(rs.next());
            assertEquals(450.00, rs.getBigDecimal(1).doubleValue(), 0.001);
            assertTrue("expected exactly one row for a scalar metric", !rs.next());
        }
    }

    @Test
    public void countMetricReturnsBigInt() throws Exception {
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM SALES.ORDER_COUNT")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getLong(1));
        }
    }

    @Test
    public void mdxOnlyMetricIsSkipped() throws Exception {
        // MDX-only metrics (calculated members) don't get exposed on the SQL surface — trying to
        // SELECT from them should fail with a "table not found" style error.
        try (Connection calcite = openCalcite();
                Statement s = calcite.createStatement()) {
            try {
                s.executeQuery("SELECT * FROM SALES.MDX_ONLY_METRIC");
                fail("expected MDX-only metric to be invisible on the SQL surface");
            } catch (java.sql.SQLException expected) {
                // Message wording varies across Calcite versions; the important thing is that
                // the query didn't succeed.
            }
        }
    }

    @Test
    public void jdbcMetadataListsOssieDatasets() throws Exception {
        // Uses standard JDBC DatabaseMetaData rather than Calcite's metadata schema — matches
        // how every BI tool (Tableau, Power BI, DBeaver, dbt) enumerates schemas for its
        // browser. Validates our datasets register with stable, tool-discoverable names.
        try (Connection calcite = openCalcite();
                // catalog/schema null → walk everything. Calcite mixes hidden sub-schemas (our
                // __SALES_jdbc holder) with real ones; filter by the SALES prefix on the fly.
                ResultSet rs = calcite.getMetaData().getTables(null, null, "%", null)) {
            java.util.List<String> qualified = new java.util.ArrayList<>();
            while (rs.next()) {
                String schemaName = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                if ("SALES".equals(schemaName)) {
                    qualified.add(tableName);
                }
            }
            assertTrue("expected CUSTOMERS in SALES metadata: " + qualified, qualified.contains("CUSTOMERS"));
            assertTrue("expected ORDERS in SALES metadata: " + qualified, qualified.contains("ORDERS"));
        }
    }

    private Connection openCalcite() throws Exception {
        // Build the connect-model JSON inline so the test is hermetic.
        String modelJson = "{\n"
                + "  \"version\": \"1.0\",\n"
                + "  \"defaultSchema\": \"SALES\",\n"
                + "  \"schemas\": [{\n"
                + "    \"name\": \"SALES\",\n"
                + "    \"type\": \"custom\",\n"
                + "    \"factory\": \"org.saiku.sql.adapter.OssieSchemaFactory\",\n"
                + "    \"operand\": {\n"
                + "      \"ossieYaml\": \"" + ossieYaml.toString().replace("\\", "\\\\") + "\",\n"
                + "      \"jdbcUrl\": \"jdbc:h2:mem:selectpushdown;DB_CLOSE_DELAY=-1;MODE=PostgreSQL\",\n"
                + "      \"jdbcUser\": \"sa\",\n"
                + "      \"jdbcPassword\": \"\"\n"
                + "    }\n"
                + "  }]\n"
                + "}";
        Path modelPath = Files.createTempFile("ossie-model-", ".json");
        Files.writeString(modelPath, modelJson);
        Properties p = new Properties();
        p.put("model", modelPath.toString());
        // Case-insensitive planner matches how BI tools cast identifiers.
        p.put("caseSensitive", "false");
        return DriverManager.getConnection("jdbc:calcite:", p);
    }
}
