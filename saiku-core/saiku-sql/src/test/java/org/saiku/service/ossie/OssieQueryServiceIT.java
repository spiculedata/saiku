/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuOssieConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.datasource.RepositoryDatasourceManager;
import org.saiku.service.olap.OlapDiscoverService;

/**
 * End-to-end integration test: shelf state → SQL → live H2 warehouse via
 * {@link org.saiku.sql.adapter.OssieSchemaFactory} → {@link CellDataSet}.
 *
 * <p>Lives in {@code saiku-sql} rather than {@code saiku-service} so the Calcite adapter class
 * is on the test classpath — pulling {@code saiku-sql} into {@code saiku-service}'s tests would
 * create a Maven cycle.
 */
public class OssieQueryServiceIT {

    private static final String H2_URL = "jdbc:h2:mem:saiku_ossie_qs_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Connection h2;
    private Path yaml;
    private OssieQueryService service;
    private StubDatasourceManager dsManager;
    private FakeConnectionManager connManager;

    @Before
    public void setUp() throws Exception {
        h2 = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement s = h2.createStatement()) {
            s.execute("DROP TABLE IF EXISTS ORDERS");
            s.execute("DROP TABLE IF EXISTS CUSTOMERS");
            s.execute("CREATE TABLE CUSTOMERS (ID INT PRIMARY KEY, REGION VARCHAR(32))");
            s.execute("INSERT INTO CUSTOMERS VALUES (1,'North'),(2,'North'),(3,'South'),(4,'South'),(5,'East')");
            s.execute("CREATE TABLE ORDERS (ORDER_ID INT PRIMARY KEY, CUSTOMER_ID INT, AMOUNT DECIMAL(10,2))");
            s.execute(
                    "INSERT INTO ORDERS VALUES (1,1,100.00),(2,2,50.00),(3,3,25.00),(4,4,75.00),(5,5,10.00),(6,1,200.00)");
        }
        yaml = Files.createTempFile("ossie-query-svc-", ".yaml");
        Files.writeString(
                yaml,
                "version: 0.2.0.dev0\n"
                        + "semantic_model:\n"
                        + "- name: SALES\n"
                        + "  datasets:\n"
                        + "  - name: CUSTOMERS\n"
                        + "    source: CUSTOMERS\n"
                        + "  - name: ORDERS\n"
                        + "    source: ORDERS\n"
                        + "  metrics:\n"
                        + "  - name: revenue\n"
                        + "    expression:\n"
                        + "      dialects:\n"
                        + "      - dialect: ANSI_SQL\n"
                        + "        expression: SUM(\"ORDERS\".\"AMOUNT\")\n"
                        + "  - name: order_count\n"
                        + "    expression:\n"
                        + "      dialects:\n"
                        + "      - dialect: ANSI_SQL\n"
                        + "        expression: COUNT(*)\n"
                        + "  relationships:\n"
                        + "  - name: orders_to_customers\n"
                        + "    from: ORDERS\n"
                        + "    to: CUSTOMERS\n"
                        + "    from_columns: [CUSTOMER_ID]\n"
                        + "    to_columns: [ID]\n");

        Properties dsProps = new Properties();
        dsProps.setProperty(ISaikuConnection.OSSIE_YAML_KEY, yaml.toString());
        dsProps.setProperty(ISaikuConnection.URL_KEY, H2_URL);
        dsProps.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        dsProps.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        dsProps.setProperty("schema", "SALES");
        SaikuDatasource ds = new SaikuDatasource("SALES", SaikuDatasource.Type.OSSIE, dsProps);
        dsManager = new StubDatasourceManager();
        dsManager.put("SALES", ds);

        // Fake connection manager returns a pre-built SaikuOssieConnection.
        SaikuOssieConnection connection = new SaikuOssieConnection("SALES", dsProps);
        assertTrue(connection.connect());
        connManager = new FakeConnectionManager();
        connManager.put("SALES", connection);

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

    @Test
    public void executesGroupByRegion() throws Exception {
        OssieQueryModel qm = new OssieQueryModel();
        qm.setConnection("SALES");
        qm.setModel("SALES");
        qm.setFactDataset("ORDERS");
        qm.setRows(List.of(fieldRef("CUSTOMERS", "REGION")));
        qm.setValues(List.of(metric("revenue")));
        qm.setSorts(List.of(sort(null, null, "revenue", "DESC")));
        ThinQuery tq = new ThinQuery();
        tq.setName("smoke");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(qm);

        CellDataSet result = service.execute(tq);
        assertNotNull(result);
        // Header: [REGION, revenue]. Body row order by revenue DESC:
        //   North: 100 + 50 + 200 = 350
        //   South: 25 + 75      = 100
        //   East : 10           =  10
        assertEquals(2, result.getCellSetHeaders()[0].length);
        assertEquals("CUSTOMERS.REGION", result.getCellSetHeaders()[0][0].getFormattedValue());
        assertEquals("revenue", result.getCellSetHeaders()[0][1].getFormattedValue());
        assertEquals(3, result.getCellSetBody().length);
        assertEquals("North", result.getCellSetBody()[0][0].getFormattedValue());
        assertTrue(result.getCellSetBody()[0][1] instanceof DataCell);
        DataCell revNorth = (DataCell) result.getCellSetBody()[0][1];
        assertEquals(350.0, revNorth.getRawNumber().doubleValue(), 0.01);
        assertEquals("South", result.getCellSetBody()[1][0].getFormattedValue());
        DataCell revSouth = (DataCell) result.getCellSetBody()[1][1];
        assertEquals(100.0, revSouth.getRawNumber().doubleValue(), 0.01);
        assertEquals("East", result.getCellSetBody()[2][0].getFormattedValue());
        DataCell revEast = (DataCell) result.getCellSetBody()[2][1];
        assertEquals(10.0, revEast.getRawNumber().doubleValue(), 0.01);
    }

    @Test
    public void filterAndLimit() throws Exception {
        OssieQueryModel qm = new OssieQueryModel();
        qm.setConnection("SALES");
        qm.setModel("SALES");
        qm.setFactDataset("ORDERS");
        qm.setRows(List.of(fieldRef("CUSTOMERS", "REGION")));
        qm.setValues(List.of(metric("order_count")));
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setDataset("CUSTOMERS");
        f.setField("REGION");
        f.setOp("EQ");
        f.setValue("North");
        qm.setFilters(List.of(f));
        qm.setLimit(5);
        ThinQuery tq = new ThinQuery();
        tq.setName("filter-smoke");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(qm);

        CellDataSet result = service.execute(tq);
        assertEquals(1, result.getCellSetBody().length);
        assertEquals("North", result.getCellSetBody()[0][0].getFormattedValue());
        // North has 3 orders (rows 1, 2, 6).
        assertEquals(
                3.0, ((DataCell) result.getCellSetBody()[0][1]).getRawNumber().doubleValue(), 0.01);
    }

    @Test
    public void nullMetricValueSurfacesAsEmptyDataCell() throws Exception {
        // No rows match the filter → no groups → zero body rows. Header still present.
        OssieQueryModel qm = new OssieQueryModel();
        qm.setConnection("SALES");
        qm.setModel("SALES");
        qm.setFactDataset("ORDERS");
        qm.setRows(List.of(fieldRef("CUSTOMERS", "REGION")));
        qm.setValues(List.of(metric("revenue")));
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setDataset("CUSTOMERS");
        f.setField("REGION");
        f.setOp("EQ");
        f.setValue("Nowhere");
        qm.setFilters(List.of(f));
        ThinQuery tq = new ThinQuery();
        tq.setName("empty-smoke");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(qm);

        CellDataSet result = service.execute(tq);
        assertEquals(0, result.getCellSetBody().length);
        assertNotNull(result.getCellSetHeaders());
        assertEquals(2, result.getCellSetHeaders()[0].length);
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

    private static OssieQueryModel.SortRef sort(String dataset, String field, String metric, String dir) {
        OssieQueryModel.SortRef s = new OssieQueryModel.SortRef();
        s.setDataset(dataset);
        s.setField(field);
        s.setMetric(metric);
        s.setDirection(dir);
        return s;
    }

    /** Same shape as the discover test's stub — subclass of the real manager, overriding only lookups. */
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

    /**
     * Bare-minimum IConnectionManager stub — the query service only calls {@code getConnection(name)}.
     * Other methods either return sensible defaults or throw when accidentally reached.
     */
    static final class FakeConnectionManager implements IConnectionManager {
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

    /**
     * Minimal OlapDiscoverService subclass that only exposes the connection manager we need.
     * Constructing the real one requires a DatasourceService we don't want to build.
     */
    static final class StubOlapDiscoverService extends OlapDiscoverService {
        private final IConnectionManager cm;

        StubOlapDiscoverService(IConnectionManager cm) {
            this.cm = cm;
        }

        @Override
        public IConnectionManager getConnectionManager() {
            return cm;
        }
    }
}
