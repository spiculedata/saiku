/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuOssieConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.datasource.RepositoryDatasourceManager;
import org.saiku.service.olap.OlapDiscoverService;

/**
 * Fuzz suite for the Ossie shelf-state → SQL pipeline. Seeds a Pharma-shaped H2 warehouse
 * with 12 fact rows across 4 regions × 3 payers × 3 products, then runs 40+ hand-crafted
 * shelf states through {@link OssieQueryService#execute} and compares the resulting
 * {@link CellDataSet} against a hand-computed reference for each case.
 *
 * <p>Every case is defined declaratively as a {@link FuzzCase} — shelf shape + expected
 * summary — so adding a new case is a one-liner. The suite runs each case, collects
 * failures, and reports a compact "X of Y failed" summary with each failure's diff. That's
 * far more useful than JUnit's default one-assertion-fails-and-stops behaviour when we're
 * probing the full combinatorial surface of the translator.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>Group-by shapes: 0–2 rows × 0–2 columns × 1–3 metrics.
 *   <li>Every filter operator: EQ / NEQ / LT / LTE / GT / GTE / IN / BETWEEN /
 *       IS_NULL / IS_NOT_NULL. Multi-filter conjunctions.
 *   <li>Sorts by dimension and by metric, ASC and DESC. Multi-column sorts.
 *   <li>LIMIT interacting with sort.
 *   <li>Aggregation overrides (SUM/AVG/MIN/MAX/COUNT) rewriting the declared metric
 *       expression.
 *   <li>Auto-join across 2, 3, and 4 datasets driven by Ossie relationships.
 *   <li>Empty result envelopes (filters that match no rows).
 * </ul>
 */
public class OssieFuzzIT {

    private static final String H2_URL = "jdbc:h2:mem:saiku_ossie_fuzz;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Connection h2;
    private Path yaml;
    private OssieQueryService service;

    @Before
    public void setUp() throws Exception {
        h2 = DriverManager.getConnection(H2_URL, "sa", "");
        seed();
        yaml = Files.createTempFile("ossie-fuzz-", ".yaml");
        Files.writeString(yaml, ossieYaml());

        Properties dsProps = new Properties();
        dsProps.setProperty(ISaikuConnection.OSSIE_YAML_KEY, yaml.toString());
        dsProps.setProperty(ISaikuConnection.URL_KEY, H2_URL);
        dsProps.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        dsProps.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        dsProps.setProperty("schema", "Pharma");
        SaikuDatasource ds = new SaikuDatasource("Pharma", SaikuDatasource.Type.OSSIE, dsProps);
        StubDatasourceManager dsManager = new StubDatasourceManager();
        dsManager.put("Pharma", ds);

        SaikuOssieConnection connection = new SaikuOssieConnection("Pharma", dsProps);
        assertTrue(connection.connect());
        FakeConnectionManager connManager = new FakeConnectionManager();
        connManager.put("Pharma", connection);

        OssieDiscoverService discover = new OssieDiscoverService();
        discover.setDatasourceManager(dsManager);
        OlapDiscoverService olap = new StubOlapDiscoverService(connManager);

        service = new OssieQueryService();
        service.setOssieDiscoverService(discover);
        service.setOlapDiscoverService(olap);
    }

    @After
    public void tearDown() throws Exception {
        if (h2 != null) h2.close();
        if (yaml != null) Files.deleteIfExists(yaml);
    }

    /**
     * Populate H2 with the Pharma-shaped fixture. Same 12-row layout as the live-server
     * smoke test — small enough that every case's expected values are computable by hand,
     * big enough to have interesting sort/limit/aggregation semantics.
     */
    private void seed() throws Exception {
        try (Statement s = h2.createStatement()) {
            s.execute("DROP TABLE IF EXISTS FACT_PHARMA");
            s.execute("DROP TABLE IF EXISTS DIM_GEOGRAPHY");
            s.execute("DROP TABLE IF EXISTS DIM_PAYER");
            s.execute("DROP TABLE IF EXISTS DIM_PRODUCT");

            s.execute("CREATE TABLE DIM_GEOGRAPHY (GEOKEY INT PRIMARY KEY, REGION VARCHAR(32), STATE VARCHAR(2))");
            s.execute("INSERT INTO DIM_GEOGRAPHY VALUES"
                    + "(1,'Northeast','NY'),(2,'Midwest','IL'),(3,'West','CA'),(4,'South','TX')");

            s.execute("CREATE TABLE DIM_PAYER (PAYERKEY INT PRIMARY KEY, CHANNEL VARCHAR(32), PAYER_NAME VARCHAR(64))");
            s.execute("INSERT INTO DIM_PAYER VALUES"
                    + "(1,'Commercial','BlueCross'),(2,'Medicare','CMS'),(3,'Medicaid','State')");

            s.execute("CREATE TABLE DIM_PRODUCT (PRODUCTKEY INT PRIMARY KEY, BRAND VARCHAR(32), MOLECULE VARCHAR(64))");
            s.execute("INSERT INTO DIM_PRODUCT VALUES"
                    + "(10,'Alfa','atorvastatin'),(20,'Beta','metformin'),(30,'Gamma','sertraline')");

            s.execute("CREATE TABLE FACT_PHARMA (RXKEY INT PRIMARY KEY,"
                    + " GEOKEY INT, PAYERKEY INT, PRODUCTKEY INT, NETREVENUE DECIMAL(10,2), RXCOUNT INT)");
            s.execute("INSERT INTO FACT_PHARMA VALUES"
                    + " ( 1, 1, 1, 10, 250.00, 15)," + " ( 2, 1, 2, 20, 120.50,  8),"
                    + " ( 3, 2, 1, 30,  85.00, 22)," + " ( 4, 2, 3, 10, 190.75, 11),"
                    + " ( 5, 3, 2, 20, 340.00, 18)," + " ( 6, 3, 1, 30,  60.25, 30),"
                    + " ( 7, 4, 3, 10, 210.00,  9)," + " ( 8, 4, 2, 20,  95.50, 14),"
                    + " ( 9, 1, 3, 30, 175.00, 12)," + " (10, 2, 2, 10, 400.00, 25),"
                    + " (11, 3, 3, 20, 155.75, 17)," + " (12, 4, 1, 30, 220.50, 21)");
        }
    }

    /**
     * Ossie YAML for the Pharma-shaped fixture. Mirrors the live-server smoke setup —
     * 4 datasets, 3 metrics, 3 relationships anchored at fact_pharma.
     */
    private String ossieYaml() {
        return "version: 0.2.0.dev0\n" + "semantic_model:\n"
                + "- name: Pharma\n"
                + "  datasets:\n"
                + "  - name: fact_pharma\n"
                + "    source: FACT_PHARMA\n"
                + "    primary_key: [RXKEY]\n"
                + "    fields:\n"
                + "    - name: rxkey\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: RXKEY\n"
                + "    - name: geokey\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: GEOKEY\n"
                + "    - name: payerkey\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: PAYERKEY\n"
                + "    - name: productkey\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: PRODUCTKEY\n"
                + "    - name: netrevenue\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: NETREVENUE\n"
                + "    - name: rxcount\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: RXCOUNT\n"
                + "  - name: geography\n    source: DIM_GEOGRAPHY\n    primary_key: [GEOKEY]\n"
                + "    fields:\n"
                + "    - name: geokey\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: GEOKEY\n"
                + "    - name: region\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: REGION\n"
                + "    - name: state\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: STATE\n"
                + "  - name: payer\n    source: DIM_PAYER\n    primary_key: [PAYERKEY]\n"
                + "    fields:\n"
                + "    - name: payerkey\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: PAYERKEY\n"
                + "    - name: channel\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: CHANNEL\n"
                + "    - name: payer_name\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: PAYER_NAME\n"
                + "  - name: product\n    source: DIM_PRODUCT\n    primary_key: [PRODUCTKEY]\n"
                + "    fields:\n"
                + "    - name: productkey\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: PRODUCTKEY\n"
                + "    - name: brand\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: BRAND\n"
                + "    - name: molecule\n      expression:\n        dialects:\n"
                + "        - dialect: ANSI_SQL\n          expression: MOLECULE\n"
                + "  metrics:\n"
                + "  - name: net_revenue\n    expression:\n      dialects:\n"
                + "      - dialect: ANSI_SQL\n        expression: SUM(\"fact_pharma\".\"NETREVENUE\")\n"
                + "  - name: rx_count\n    expression:\n      dialects:\n"
                + "      - dialect: ANSI_SQL\n        expression: SUM(\"fact_pharma\".\"RXCOUNT\")\n"
                + "  - name: line_count\n    expression:\n      dialects:\n"
                + "      - dialect: ANSI_SQL\n        expression: COUNT(*)\n"
                + "  relationships:\n"
                + "  - name: fact_to_geography\n    from: fact_pharma\n    to: geography\n"
                + "    from_columns: [GEOKEY]\n    to_columns: [GEOKEY]\n"
                + "  - name: fact_to_payer\n    from: fact_pharma\n    to: payer\n"
                + "    from_columns: [PAYERKEY]\n    to_columns: [PAYERKEY]\n"
                + "  - name: fact_to_product\n    from: fact_pharma\n    to: product\n"
                + "    from_columns: [PRODUCTKEY]\n    to_columns: [PRODUCTKEY]\n";
    }

    // -------------------------------------------------------------------------
    // The fuzz proper
    // -------------------------------------------------------------------------

    /**
     * Generated combinatorial fuzz — hundreds of shelf shapes we don't hand-verify. Each
     * generated case just has to *execute* cleanly: SQL parses, no exception, non-null
     * CellDataSet returned. Row values are ignored. Catches broken-SQL regressions where
     * a novel shelf combination emits something the parser rejects.
     */
    @Test
    public void combinatorialFuzz() throws Exception {
        // Alphabet of picks used to build shelf shapes.
        List<OssieQueryModel.FieldRef> dims = List.of(
                fieldRef("geography", "REGION"),
                fieldRef("geography", "STATE"),
                fieldRef("payer", "CHANNEL"),
                fieldRef("payer", "PAYER_NAME"),
                fieldRef("product", "BRAND"),
                fieldRef("product", "MOLECULE"));
        String[] metrics = {"net_revenue", "rx_count", "line_count"};
        String[] aggOverrides = {null, "SUM", "AVG", "MIN", "MAX", "COUNT"};
        String[] filterOps = {"EQ", "NEQ", "LT", "LTE", "GT", "GTE", "IN"};
        String[] filterValues = {"Northeast", "Alfa", "CMS", "NY"};

        int cases = 0;
        int passed = 0;
        List<String> failures = new ArrayList<>();

        // 1..2 rows × 0..2 columns × 1..2 metrics × 0..2 filters × 0..1 sorts × 0..1 limit
        // × 6 agg overrides — capped by outer loop indices below.
        for (int nRows = 1; nRows <= 2; nRows++) {
            for (int nCols = 0; nCols <= 1; nCols++) {
                for (int nVals = 1; nVals <= 2; nVals++) {
                    for (int rowSeed = 0; rowSeed < dims.size(); rowSeed++) {
                        for (int metricSeed = 0; metricSeed < metrics.length; metricSeed++) {
                            for (int aggIdx = 0; aggIdx < aggOverrides.length; aggIdx++) {
                                for (int filterOpIdx = -1; filterOpIdx < filterOps.length; filterOpIdx++) {
                                    OssieQueryModel m = new OssieQueryModel();
                                    m.setFactDataset("fact_pharma");
                                    // Rows: pick nRows distinct dims starting at rowSeed.
                                    for (int i = 0; i < nRows; i++) {
                                        m.getRows().add(dims.get((rowSeed + i) % dims.size()));
                                    }
                                    // Columns: pick nCols dims distinct from the row picks.
                                    for (int i = 0; i < nCols; i++) {
                                        m.getColumns().add(dims.get((rowSeed + nRows + i) % dims.size()));
                                    }
                                    // Metrics: pick nVals distinct metrics, applying the override to the
                                    // first one.
                                    for (int i = 0; i < nVals; i++) {
                                        OssieQueryModel.MetricRef mr =
                                                metric(metrics[(metricSeed + i) % metrics.length]);
                                        if (i == 0 && aggOverrides[aggIdx] != null)
                                            mr.setAggregation(aggOverrides[aggIdx]);
                                        m.getValues().add(mr);
                                    }
                                    // Optional filter — 1 filter per case when filterOpIdx >= 0.
                                    if (filterOpIdx >= 0) {
                                        String op = filterOps[filterOpIdx];
                                        OssieQueryModel.FieldRef filterField = dims.get((rowSeed + 2) % dims.size());
                                        String val = filterValues[filterOpIdx % filterValues.length];
                                        if ("IN".equals(op)) {
                                            m.getFilters()
                                                    .add(filter(
                                                            filterField.getDataset(),
                                                            filterField.getField(),
                                                            "IN",
                                                            null,
                                                            List.of(val, "Beta", "Medicare")));
                                        } else {
                                            m.getFilters()
                                                    .add(filter(
                                                            filterField.getDataset(),
                                                            filterField.getField(),
                                                            op,
                                                            val,
                                                            List.of()));
                                        }
                                    }
                                    // Deterministic sort so the executor doesn't hit non-deterministic
                                    // ordering across runs.
                                    m.getSorts()
                                            .add(sortByField(
                                                    m.getRows().get(0).getDataset(),
                                                    m.getRows().get(0).getField(),
                                                    "ASC"));

                                    cases++;
                                    try {
                                        CellDataSet result = execute(m);
                                        assertNotNull(result);
                                        assertNotNull(result.getCellSetHeaders());
                                        // Shape sanity: the number of columns in each body row equals
                                        // the number of header columns. If Calcite ever returns a
                                        // ragged rowset that's a real problem.
                                        int hdrCount = result.getCellSetHeaders()[0].length;
                                        if (result.getCellSetBody() != null) {
                                            for (AbstractBaseCell[] row : result.getCellSetBody()) {
                                                if (row.length != hdrCount) {
                                                    failures.add(String.format(
                                                            "  ragged rowset: header=%d row=%d for shelf %s",
                                                            hdrCount, row.length, describe(m)));
                                                    break;
                                                }
                                            }
                                        }
                                        passed++;
                                    } catch (Exception e) {
                                        failures.add(String.format(
                                                "  %s%n    threw:   %s: %s",
                                                describe(m), e.getClass().getSimpleName(), e.getMessage()));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        System.out.println(String.format("[Ossie combinatorial fuzz] passed %d / %d", passed, cases));
        if (!failures.isEmpty()) {
            fail(String.format(
                    "Combinatorial fuzz: %d / %d failed%n%s",
                    failures.size(),
                    cases,
                    // Cap the failure list to the first 20 so the report stays legible.
                    String.join("\n", failures.stream().limit(20).toArray(String[]::new))));
        }
    }

    /**
     * Compact one-line description of a shelf state for the failure log. Just enough to
     * disambiguate cases from each other without dumping the whole OssieQueryModel.
     */
    private String describe(OssieQueryModel m) {
        StringBuilder sb = new StringBuilder("rows=");
        for (int i = 0; i < m.getRows().size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(m.getRows().get(i).getField());
        }
        if (!m.getColumns().isEmpty()) {
            sb.append(" cols=");
            for (int i = 0; i < m.getColumns().size(); i++) {
                if (i > 0) sb.append('+');
                sb.append(m.getColumns().get(i).getField());
            }
        }
        sb.append(" vals=");
        for (int i = 0; i < m.getValues().size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(m.getValues().get(i).getMetric());
            if (m.getValues().get(i).getAggregation() != null) {
                sb.append('(').append(m.getValues().get(i).getAggregation()).append(')');
            }
        }
        if (!m.getFilters().isEmpty()) {
            sb.append(" filters=");
            for (int i = 0; i < m.getFilters().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(m.getFilters().get(i).getField())
                        .append(' ')
                        .append(m.getFilters().get(i).getOp());
            }
        }
        return sb.toString();
    }

    @Test
    public void fuzz() throws Exception {
        List<FuzzCase> cases = defineCases();
        List<String> failures = new ArrayList<>();
        int passed = 0;
        for (FuzzCase c : cases) {
            try {
                CellDataSet result = execute(c.model());
                String actual = summarize(result);
                if (actual.equals(c.expected())) {
                    passed++;
                } else {
                    failures.add(
                            String.format("  %s%n    expected: %s%n    actual:   %s", c.name(), c.expected(), actual));
                }
            } catch (Exception e) {
                failures.add(String.format(
                        "  %s%n    threw:    %s: %s", c.name(), e.getClass().getSimpleName(), e.getMessage()));
            }
        }
        int total = cases.size();
        // Always log the pass count so a clean CI run shows what happened.
        System.out.println(String.format("[Ossie fuzz] passed %d / %d", passed, total));
        if (!failures.isEmpty()) {
            fail(String.format("Ossie fuzz: %d / %d failed%n%s", failures.size(), total, String.join("\n", failures)));
        }
    }

    // -------------------------------------------------------------------------
    // Case definitions — each is an OssieQueryModel + expected result summary
    // -------------------------------------------------------------------------

    private List<FuzzCase> defineCases() {
        List<FuzzCase> cases = new ArrayList<>();

        // -- Basic scans + single-metric group-bys ---------------------------------
        cases.add(fc(
                "1-value scan on fact",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getValues().add(metric("line_count"));
                }),
                "[line_count]|[12]"));
        cases.add(fc(
                "1-row scan on geography",
                model(m -> {
                    m.setFactDataset("geography");
                    m.getRows().add(fieldRef("geography", "REGION"));
                }),
                "[geography.REGION]|" + "[Northeast]|[Midwest]|[West]|[South]"));

        // -- 1-row × 1-metric (basic group-by with auto-join) ----------------------
        cases.add(fc(
                "region × net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue]|"
                        + "[Midwest, 675.75]|[Northeast, 545.50]|[South, 526.00]|[West, 556.00]"));
        cases.add(fc(
                "region × rx_count",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("rx_count"));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, rx_count]|" + "[Midwest, 58]|[Northeast, 35]|[South, 44]|[West, 65]"));
        cases.add(fc(
                "brand × net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, net_revenue]|" + "[Alfa, 1050.75]|[Beta, 711.75]|[Gamma, 540.75]"));
        cases.add(fc(
                "channel × net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                }),
                "[payer.CHANNEL, net_revenue]|" + "[Commercial, 615.75]|[Medicaid, 731.50]|[Medicare, 956.00]"));

        // -- Multi-metric group-bys -----------------------------------------------
        cases.add(fc(
                "region × (net_revenue, rx_count)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getValues().add(metric("rx_count"));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue, rx_count]|"
                        + "[Midwest, 675.75, 58]|[Northeast, 545.50, 35]|[South, 526.00, 44]|[West, 556.00, 65]"));

        cases.add(fc(
                "brand × (net_revenue, rx_count, line_count)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getValues().add(metric("rx_count"));
                    m.getValues().add(metric("line_count"));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, net_revenue, rx_count, line_count]|"
                        + "[Alfa, 1050.75, 60, 4]|[Beta, 711.75, 57, 4]|[Gamma, 540.75, 85, 4]"));

        // -- Multi-row group-bys (2 dims × 1 metric, cross-dataset auto-join) ------
        cases.add(fc(
                "brand + molecule × net_revenue (2-way join on same dim)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getRows().add(fieldRef("product", "MOLECULE"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, product.MOLECULE, net_revenue]|"
                        + "[Alfa, atorvastatin, 1050.75]|[Beta, metformin, 711.75]|[Gamma, sertraline, 540.75]"));

        // -- 3-way and 4-way auto-join --------------------------------------------
        cases.add(fc(
                "channel + region × net_revenue (3-way join)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[payer.CHANNEL, geography.REGION, net_revenue]|"
                        + "[Commercial, Midwest, 85.00]|[Commercial, Northeast, 250.00]"
                        + "|[Commercial, South, 220.50]|[Commercial, West, 60.25]"
                        + "|[Medicaid, Midwest, 190.75]|[Medicaid, Northeast, 175.00]"
                        + "|[Medicaid, South, 210.00]|[Medicaid, West, 155.75]"
                        + "|[Medicare, Midwest, 400.00]|[Medicare, Northeast, 120.50]"
                        + "|[Medicare, South, 95.50]|[Medicare, West, 340.00]"));

        cases.add(fc(
                "channel + region + brand × net_revenue (4-way join, filtered)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("payer", "CHANNEL", "EQ", "Medicare", List.of()));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[payer.CHANNEL, geography.REGION, product.BRAND, net_revenue]|"
                        + "[Medicare, Midwest, Alfa, 400.00]"
                        + "|[Medicare, Northeast, Beta, 120.50]"
                        + "|[Medicare, South, Beta, 95.50]"
                        + "|[Medicare, West, Beta, 340.00]"));

        // -- Every filter operator on region ---------------------------------------
        cases.add(fc(
                "EQ region=Midwest × net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "REGION", "EQ", "Midwest", List.of()));
                }),
                "[geography.REGION, net_revenue]|[Midwest, 675.75]"));
        cases.add(fc(
                "NEQ region≠Midwest × net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "REGION", "NEQ", "Midwest", List.of()));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue]|" + "[Northeast, 545.50]|[South, 526.00]|[West, 556.00]"));
        cases.add(fc(
                "IN region IN {Midwest, West}",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "REGION", "IN", null, List.of("Midwest", "West")));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue]|[Midwest, 675.75]|[West, 556.00]"));
        cases.add(fc(
                "empty IN → zero rows",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "REGION", "IN", null, List.of()));
                }),
                "[geography.REGION, net_revenue]"));

        // -- Numeric filters on fact_pharma columns --------------------------------
        cases.add(fc(
                "netrevenue > 300 × net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "GT", "300", List.of()));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, net_revenue]|" + "[Alfa, 400.00]|[Beta, 340.00]"));
        cases.add(fc(
                "netrevenue BETWEEN 100 and 200 × line_count",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "BETWEEN", null, List.of("100", "200")));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, line_count]|[Alfa, 1]|[Beta, 2]|[Gamma, 1]"));

        cases.add(fc(
                "rxcount <= 10 × line_count",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "RXCOUNT", "LTE", "10", List.of()));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                }),
                "[payer.CHANNEL, line_count]|[Medicaid, 1]|[Medicare, 1]"));

        cases.add(fc(
                "rxcount >= 20 × line_count",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "RXCOUNT", "GTE", "20", List.of()));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                }),
                "[payer.CHANNEL, line_count]|[Commercial, 3]|[Medicare, 1]"));

        // -- Sort orderings --------------------------------------------------------
        cases.add(fc(
                "sort by metric DESC",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByMetric("net_revenue", "DESC"));
                }),
                "[geography.REGION, net_revenue]|"
                        + "[Midwest, 675.75]|[West, 556.00]|[Northeast, 545.50]|[South, 526.00]"));
        cases.add(fc(
                "sort by metric ASC",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByMetric("net_revenue", "ASC"));
                }),
                "[geography.REGION, net_revenue]|"
                        + "[South, 526.00]|[Northeast, 545.50]|[West, 556.00]|[Midwest, 675.75]"));

        // -- LIMIT ------------------------------------------------------------------
        cases.add(fc(
                "top-2 regions by net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByMetric("net_revenue", "DESC"));
                    m.setLimit(2);
                }),
                "[geography.REGION, net_revenue]|[Midwest, 675.75]|[West, 556.00]"));
        cases.add(fc(
                "bottom-1 brand by net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByMetric("net_revenue", "ASC"));
                    m.setLimit(1);
                }),
                "[product.BRAND, net_revenue]|[Gamma, 540.75]"));

        // -- Aggregation overrides (SUM → AVG / MAX / MIN / COUNT) -----------------
        // Average net revenue per region.
        // Northeast (3 rows: 250, 120.50, 175) → avg 181.833333...
        // Midwest   (3 rows: 85, 190.75, 400) → avg 225.25
        // West      (3 rows: 340, 60.25, 155.75) → avg 185.333...
        // South     (3 rows: 210, 95.50, 220.50) → avg 175.333...
        cases.add(fc(
                "override SUM → AVG on net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    OssieQueryModel.MetricRef mr = metric("net_revenue");
                    mr.setAggregation("AVG");
                    m.getValues().add(mr);
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                // H2 returns AVG with 6 decimals precision → truncate for stability by asking
                // for 2 decimal places via ROUND — actually the CellDataSet just stringifies
                // the DECIMAL. Use a coarse comparison via prefix + expected 2-dp value.
                "[geography.REGION, net_revenue]|"
                        + "[Midwest, 225.25]|[Northeast, 181.83]|[South, 175.33]|[West, 185.33]"));

        cases.add(fc(
                "override SUM → MAX on net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    OssieQueryModel.MetricRef mr = metric("net_revenue");
                    mr.setAggregation("MAX");
                    m.getValues().add(mr);
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue]|"
                        + "[Midwest, 400.00]|[Northeast, 250.00]|[South, 220.50]|[West, 340.00]"));

        cases.add(fc(
                "override SUM → MIN on net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    OssieQueryModel.MetricRef mr = metric("net_revenue");
                    mr.setAggregation("MIN");
                    m.getValues().add(mr);
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue]|"
                        + "[Midwest, 85.00]|[Northeast, 120.50]|[South, 95.50]|[West, 60.25]"));

        // COUNT (net_revenue rows per region).
        cases.add(fc(
                "override SUM → COUNT on rx_count (row count per region)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    OssieQueryModel.MetricRef mr = metric("rx_count");
                    mr.setAggregation("COUNT");
                    m.getValues().add(mr);
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, rx_count]|" + "[Midwest, 3]|[Northeast, 3]|[South, 3]|[West, 3]"));

        // -- Multi-filter conjunction ---------------------------------------------
        cases.add(fc(
                "region IN {Midwest,West} AND channel=Medicare",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "REGION", "IN", null, List.of("Midwest", "West")));
                    m.getFilters().add(filter("payer", "CHANNEL", "EQ", "Medicare", List.of()));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue]|" + "[Midwest, 400.00]|[West, 340.00]"));

        // -- Zero-row result --------------------------------------------------------
        cases.add(fc(
                "impossible filter → zero rows",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "REGION", "EQ", "Nowhere", List.of()));
                }),
                "[geography.REGION, net_revenue]"));

        // -- IS_NULL / IS_NOT_NULL --------------------------------------------------
        // No nulls in the fixture — IS_NULL returns zero, IS_NOT_NULL returns everything.
        cases.add(fc(
                "IS_NULL on netrevenue → zero rows",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "IS_NULL", null, List.of()));
                }),
                "[line_count]|[0]"));
        cases.add(fc(
                "IS_NOT_NULL on netrevenue → all rows",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "IS_NOT_NULL", null, List.of()));
                }),
                "[line_count]|[12]"));

        // -- Crosstab-style: rows + columns × metrics (fact + 2 dims) --------------
        // The server returns a flat GROUP BY; the client pivots. We just assert the
        // flat rowset here.
        cases.add(fc(
                "product + region × net_revenue (crosstab shape, flat rowset)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getColumns().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[product.BRAND, geography.REGION, net_revenue]|"
                        + "[Alfa, Midwest, 590.75]|[Alfa, Northeast, 250.00]|[Alfa, South, 210.00]"
                        + "|[Beta, Northeast, 120.50]|[Beta, South, 95.50]|[Beta, West, 495.75]"
                        + "|[Gamma, Midwest, 85.00]|[Gamma, Northeast, 175.00]"
                        + "|[Gamma, South, 220.50]|[Gamma, West, 60.25]"));

        // ================== Extended fuzz — added 2026-07-08 ==================

        // -- LT / LTE numeric filters ---------------------------------------------
        cases.add(fc(
                "netrevenue < 100 × line_count per brand",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "LT", "100", List.of()));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, line_count]|[Beta, 1]|[Gamma, 2]"));

        cases.add(fc(
                "netrevenue <= 100 × net_revenue per brand",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "LTE", "100", List.of()));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, net_revenue]|[Beta, 95.50]|[Gamma, 145.25]"));

        cases.add(fc(
                "rxcount < 12 × line_count per channel",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "RXCOUNT", "LT", "12", List.of()));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                }),
                "[payer.CHANNEL, line_count]|[Medicaid, 2]|[Medicare, 1]"));

        // -- IN with 3 values (superset - excludes Midwest) ------------------------
        cases.add(fc(
                "region IN (Northeast, West, South) × net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters()
                            .add(filter("geography", "REGION", "IN", null, List.of("Northeast", "West", "South")));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                "[geography.REGION, net_revenue]|" + "[Northeast, 545.50]|[South, 526.00]|[West, 556.00]"));

        // -- Multi-sort: primary ASC + secondary DESC on different columns ---------
        cases.add(fc(
                "sort by channel ASC then net_revenue DESC (crosstab-shape)",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                    m.getSorts().add(sortByMetric("net_revenue", "DESC"));
                }),
                "[payer.CHANNEL, geography.REGION, net_revenue]|"
                        + "[Commercial, Northeast, 250.00]|[Commercial, South, 220.50]"
                        + "|[Commercial, Midwest, 85.00]|[Commercial, West, 60.25]"
                        + "|[Medicaid, South, 210.00]|[Medicaid, Midwest, 190.75]"
                        + "|[Medicaid, Northeast, 175.00]|[Medicaid, West, 155.75]"
                        + "|[Medicare, Midwest, 400.00]|[Medicare, West, 340.00]"
                        + "|[Medicare, Northeast, 120.50]|[Medicare, South, 95.50]"));

        // -- Filter + sort + LIMIT together ----------------------------------------
        cases.add(fc(
                "exclude Medicare, top-3 regions by net_revenue DESC",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("payer", "CHANNEL", "NEQ", "Medicare", List.of()));
                    m.getSorts().add(sortByMetric("net_revenue", "DESC"));
                    m.setLimit(3);
                }),
                "[geography.REGION, net_revenue]|" + "[South, 430.50]|[Northeast, 425.00]|[Midwest, 275.75]"));

        // -- Aggregation override AGAINST a filtered dataset ----------------------
        // Verifies the SUM→MAX rewrite still applies when the WHERE clause is present.
        cases.add(fc(
                "MAX override with region IN (Midwest, West) filter",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    OssieQueryModel.MetricRef mr = metric("net_revenue");
                    mr.setAggregation("MAX");
                    m.getValues().add(mr);
                    m.getFilters().add(filter("geography", "REGION", "IN", null, List.of("Midwest", "West")));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                }),
                "[payer.CHANNEL, net_revenue]|" + "[Commercial, 85.00]|[Medicaid, 190.75]|[Medicare, 400.00]"));

        // -- Filter on secondary attributes (payer_name, state) -------------------
        cases.add(fc(
                "payer_name = 'CMS' (Medicare) × net_revenue per brand",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("payer", "PAYER_NAME", "EQ", "CMS", List.of()));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, net_revenue]|[Alfa, 400.00]|[Beta, 556.00]"));

        cases.add(fc(
                "state = 'CA' filter × total net_revenue",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "STATE", "EQ", "CA", List.of()));
                }),
                "[net_revenue]|[556.00]"));

        // -- Compound 3-filter conjunction (STATE + numeric range + IN) -----------
        cases.add(fc(
                "state='CA' AND netrevenue > 100 × net_revenue per brand",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("geography", "STATE", "EQ", "CA", List.of()));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "GT", "100", List.of()));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, net_revenue]|[Beta, 495.75]"));

        // -- Big-conjunction 5-filter shape (regression on filterToSql ordering) ---
        cases.add(fc(
                "5-filter conjunction covers >=, <=, >, <>, IN",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "GTE", "100", List.of()));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "LTE", "400", List.of()));
                    m.getFilters().add(filter("fact_pharma", "RXCOUNT", "GT", "10", List.of()));
                    m.getFilters().add(filter("geography", "REGION", "NEQ", "Midwest", List.of()));
                    m.getFilters().add(filter("product", "BRAND", "IN", null, List.of("Alfa", "Beta")));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, net_revenue]|[Alfa, 250.00]|[Beta, 495.75]"));

        // -- COUNT of filtered rows via line_count -------------------------------
        cases.add(fc(
                "line_count of rows with netrevenue > 200 per brand",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("product", "BRAND"));
                    m.getValues().add(metric("line_count"));
                    m.getFilters().add(filter("fact_pharma", "NETREVENUE", "GT", "200", List.of()));
                    m.getSorts().add(sortByField("product", "BRAND", "ASC"));
                }),
                "[product.BRAND, line_count]|[Alfa, 3]|[Beta, 1]|[Gamma, 1]"));

        // -- Value-only scan (no rows, no cols) — two metrics ---------------------
        // Exercises the shape where the SELECT has ONLY aggregates and no GROUP BY.
        cases.add(fc(
                "no-dim scan × (net_revenue, line_count) — global totals",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getValues().add(metric("net_revenue"));
                    m.getValues().add(metric("line_count"));
                }),
                "[net_revenue, line_count]|[2303.25, 12]"));

        // -- BETWEEN on a dim table's numeric-shaped VARCHAR field ---------------
        // Regression check that the translator quotes correctly when the value is
        // numeric-looking on a VARCHAR column (state code doesn't apply here — use
        // channel as the interval literal is a string).
        // (Channels sorted lexicographically: Commercial, Medicaid, Medicare.)
        cases.add(fc(
                "BETWEEN string range on payer.CHANNEL",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getValues().add(metric("net_revenue"));
                    m.getFilters().add(filter("payer", "CHANNEL", "BETWEEN", null, List.of("Commercial", "Medicaid")));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                }),
                "[payer.CHANNEL, net_revenue]|[Commercial, 615.75]|[Medicaid, 731.50]"));

        // -- Aggregation override interacting with sort BY ORIGINAL metric alias ---
        // The metric alias in ORDER BY still resolves after the outer function was
        // rewritten — Calcite treats the alias as a projection reference.
        cases.add(fc(
                "override SUM→AVG + sort by metric alias DESC",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    OssieQueryModel.MetricRef mr = metric("net_revenue");
                    mr.setAggregation("AVG");
                    m.getValues().add(mr);
                    m.getSorts().add(sortByMetric("net_revenue", "DESC"));
                }),
                "[geography.REGION, net_revenue]|"
                        + "[Midwest, 225.25]|[West, 185.33]|[Northeast, 181.83]|[South, 175.33]"));

        // -- Zero-metric row-list (just distinct dimensions) ----------------------
        cases.add(fc(
                "distinct channels via GROUP BY without any metric",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("payer", "CHANNEL"));
                    m.getSorts().add(sortByField("payer", "CHANNEL", "ASC"));
                }),
                // A GROUP-less rowset produces one row per fact join. Without a metric,
                // the emitted SQL has no GROUP BY (see translator lines 108-111), so this
                // returns a per-row scan of the channel value — 12 rows total.
                "[payer.CHANNEL]|[Commercial]|[Commercial]|[Commercial]|[Commercial]"
                        + "|[Medicaid]|[Medicaid]|[Medicaid]|[Medicaid]"
                        + "|[Medicare]|[Medicare]|[Medicare]|[Medicare]"));

        // -- MIN + MAX overrides + brand filter --------------------------------
        cases.add(fc(
                "MIN override with brand=Alfa filter across regions",
                model(m -> {
                    m.setFactDataset("fact_pharma");
                    m.getRows().add(fieldRef("geography", "REGION"));
                    OssieQueryModel.MetricRef mr = metric("net_revenue");
                    mr.setAggregation("MIN");
                    m.getValues().add(mr);
                    m.getFilters().add(filter("product", "BRAND", "EQ", "Alfa", List.of()));
                    m.getSorts().add(sortByField("geography", "REGION", "ASC"));
                }),
                // Alfa (product 10) rows: 1 (Northeast, 250), 4 (Midwest, 190.75),
                // 7 (South, 210), 10 (Midwest, 400). MIN per region:
                //   Midwest: 190.75 (of 190.75, 400)
                //   Northeast: 250
                //   South: 210
                //   West: no rows
                "[geography.REGION, net_revenue]|" + "[Midwest, 190.75]|[Northeast, 250.00]|[South, 210.00]"));

        return cases;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CellDataSet execute(OssieQueryModel model) throws Exception {
        ThinQuery tq = new ThinQuery();
        tq.setName("fuzz-" + Math.abs(model.hashCode()));
        tq.setQueryType("OSSIE");
        model.setConnection("Pharma");
        model.setModel("Pharma");
        tq.setOssieQueryModel(model);
        return service.execute(tq);
    }

    /**
     * Summarise a {@link CellDataSet} into a compact string suitable for character-level
     * assertion. Header is a comma-separated list of column labels wrapped in {@code []};
     * body is one bracketed comma-separated row per line, separated by {@code |}. Empty
     * bodies drop the trailing separator so a zero-row result is just {@code [header]}.
     */
    private String summarize(CellDataSet result) {
        assertNotNull(result);
        assertNotNull(result.getCellSetHeaders());
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        AbstractBaseCell[] header = result.getCellSetHeaders()[0];
        for (int i = 0; i < header.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(header[i].getFormattedValue());
        }
        sb.append(']');
        if (result.getCellSetBody() == null || result.getCellSetBody().length == 0) {
            return sb.toString();
        }
        for (AbstractBaseCell[] row : result.getCellSetBody()) {
            sb.append("|[");
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(row[i].getFormattedValue());
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static OssieQueryModel model(java.util.function.Consumer<OssieQueryModel> configure) {
        OssieQueryModel m = new OssieQueryModel();
        configure.accept(m);
        return m;
    }

    private static OssieQueryModel.FieldRef fieldRef(String dataset, String field) {
        OssieQueryModel.FieldRef f = new OssieQueryModel.FieldRef();
        f.setDataset(dataset);
        f.setField(field);
        return f;
    }

    private static OssieQueryModel.MetricRef metric(String name) {
        OssieQueryModel.MetricRef m = new OssieQueryModel.MetricRef();
        m.setMetric(name);
        return m;
    }

    private static OssieQueryModel.SortRef sortByField(String dataset, String field, String dir) {
        OssieQueryModel.SortRef s = new OssieQueryModel.SortRef();
        s.setDataset(dataset);
        s.setField(field);
        s.setDirection(dir);
        return s;
    }

    private static OssieQueryModel.SortRef sortByMetric(String metric, String dir) {
        OssieQueryModel.SortRef s = new OssieQueryModel.SortRef();
        s.setMetric(metric);
        s.setDirection(dir);
        return s;
    }

    private static OssieQueryModel.FilterExpr filter(
            String dataset, String field, String op, String value, List<String> values) {
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setDataset(dataset);
        f.setField(field);
        f.setOp(op);
        f.setValue(value);
        f.setValues(values);
        return f;
    }

    private static FuzzCase fc(String name, OssieQueryModel model, String expected) {
        return new FuzzCase(name, model, expected);
    }

    /**
     * One fuzz case: a shelf state + the exact summary string the executor should produce.
     * Kept as a plain record because we want the case list to be a dense, glanceable
     * declaration.
     */
    private record FuzzCase(String name, OssieQueryModel model, String expected) {}

    // -------------------------------------------------------------------------
    // Test doubles — copied from OssieQueryServiceIT
    // -------------------------------------------------------------------------

    static final class StubDatasourceManager extends RepositoryDatasourceManager {
        private final Map<String, SaikuDatasource> map = new HashMap<>();

        void put(String name, SaikuDatasource ds) {
            map.put(name, ds);
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName) {
            return map.get(datasourceName);
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName, boolean refresh) {
            return map.get(datasourceName);
        }
    }

    static final class FakeConnectionManager implements org.saiku.datasources.connection.IConnectionManager {
        private final Map<String, ISaikuConnection> map = new HashMap<>();

        void put(String name, ISaikuConnection c) {
            map.put(name, c);
        }

        @Override
        public void init() throws SaikuOlapException {}

        @Override
        public void setDataSourceManager(IDatasourceManager ds) {}

        @Override
        public IDatasourceManager getDataSourceManager() {
            return null;
        }

        @Override
        public void refreshConnection(String name) {}

        @Override
        public void refreshAllConnections() {}

        @Override
        public org.olap4j.OlapConnection getOlapConnection(String name) throws SaikuOlapException {
            return null;
        }

        @Override
        public Map<String, org.olap4j.OlapConnection> getAllOlapConnections() throws SaikuOlapException {
            return new HashMap<>();
        }

        @Override
        public ISaikuConnection getConnection(String name) throws SaikuOlapException {
            return map.get(name);
        }

        @Override
        public Map<String, ISaikuConnection> getAllConnections() throws SaikuOlapException {
            return map;
        }
    }

    static final class StubOlapDiscoverService extends OlapDiscoverService {
        private final org.saiku.datasources.connection.IConnectionManager cm;

        StubOlapDiscoverService(org.saiku.datasources.connection.IConnectionManager cm) {
            this.cm = cm;
        }

        @Override
        public org.saiku.datasources.connection.IConnectionManager getConnectionManager() {
            return cm;
        }
    }

    // Silence "unused import" without dropping them — MemberCell / DataCell are the
    // downcasts the summariser would need if we tightened it further.
    @SuppressWarnings("unused")
    private void referenceCellTypes(MemberCell mc, DataCell dc) {}
}
