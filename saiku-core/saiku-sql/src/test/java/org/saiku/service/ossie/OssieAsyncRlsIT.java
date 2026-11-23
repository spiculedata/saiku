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
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuOssieConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.ossie.ai.OssieAsyncQueryService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2323 (CWE-863) — end-to-end proof that the Ossie async execution path honours
 * {@code saiku.roles} exactly as the synchronous path does. {@link OssieRoleSecurityIT} proves
 * the sync path over the real warehouse; this proves the async one, where the roles must
 * propagate from the submitting (request) thread to the worker (saiku#1968 parity, already
 * shipped for the MDX side in the {@code AsyncQueryService}).
 *
 * <p>Fixture: same 3-row warehouse as {@link OssieRoleSecurityIT}, plus a
 * {@code ROLE_APAC}-denied {@code manager} field on {@code geography}. A caller holding
 * {@code ROLE_APAC} must see, via {@code OssieAsyncQueryService.submit} + poll, exactly what
 * {@link OssieQueryService#execute} returns them synchronously — and a query naming the denied
 * field must end {@code FAILED}, never {@code DONE} with data.
 */
public class OssieAsyncRlsIT {

    private static final String H2_URL = "jdbc:h2:mem:saiku_ossie_async_rls;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Connection h2;
    private Path yaml;
    private OssieQueryService service;
    private OssieAsyncQueryService async;

    @Before
    public void setUp() throws Exception {
        h2 = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement s = h2.createStatement()) {
            s.execute("DROP TABLE IF EXISTS FACT");
            s.execute("DROP TABLE IF EXISTS GEOGRAPHY");
            s.execute("CREATE TABLE GEOGRAPHY (GEO_ID INT PRIMARY KEY, REGION VARCHAR(32), MANAGER VARCHAR(32))");
            s.execute("INSERT INTO GEOGRAPHY VALUES (1,'APAC','Mei'),(2,'EMEA','Hans')");
            s.execute("CREATE TABLE FACT (ID INT PRIMARY KEY, GEO_ID INT, AMOUNT DECIMAL(10,2))");
            s.execute("INSERT INTO FACT VALUES (1,1,100.00),(2,2,200.00),(3,1,150.00)");
        }

        yaml = Files.createTempFile("ossie-async-rls-", ".yaml");
        Files.writeString(
                yaml,
                "version: 0.2.0.dev0\n"
                        + "semantic_model:\n"
                        + "- name: RLS\n"
                        + "  datasets:\n"
                        + "  - name: fact\n"
                        + "    source: FACT\n"
                        + "    primary_key: [ID]\n"
                        + "    fields:\n"
                        + "    - name: id\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: ID\n"
                        + "    - name: geo_id\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: GEO_ID\n"
                        + "    - name: amount\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: AMOUNT\n"
                        + "  - name: geography\n"
                        + "    source: GEOGRAPHY\n"
                        + "    primary_key: [GEO_ID]\n"
                        + "    fields:\n"
                        + "    - name: geo_id\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: GEO_ID\n"
                        + "    - name: region\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: REGION\n"
                        + "    - name: manager\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: MANAGER\n"
                        + "      custom_extensions:\n"
                        + "      - vendor_name: SAIKU\n"
                        + "        data: '{\"roles\":{\"deny\":[\"ROLE_APAC\"]}}'\n"
                        + "    custom_extensions:\n"
                        + "    - vendor_name: SAIKU\n"
                        + "      data: '{\"roles\":{\"row_predicates\":["
                        + "{\"role\":\"ROLE_APAC\",\"expression\":\"REGION = ''APAC''\"}]}}'\n"
                        + "  metrics:\n"
                        + "  - name: revenue\n    expression:\n      dialects:\n"
                        + "      - dialect: ANSI_SQL\n        expression: SUM(\"fact\".\"AMOUNT\")\n"
                        + "  relationships:\n"
                        + "  - name: fact_to_geography\n    from: fact\n    to: geography\n"
                        + "    from_columns: [GEO_ID]\n    to_columns: [GEO_ID]\n");

        Properties dsProps = new Properties();
        dsProps.setProperty(ISaikuConnection.OSSIE_YAML_KEY, yaml.toString());
        dsProps.setProperty(ISaikuConnection.URL_KEY, H2_URL);
        dsProps.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        dsProps.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        dsProps.setProperty("schema", "RLS");
        SaikuDatasource ds = new SaikuDatasource("RLS", SaikuDatasource.Type.OSSIE, dsProps);
        OssieFuzzIT.StubDatasourceManager dsManager = new OssieFuzzIT.StubDatasourceManager();
        dsManager.put("RLS", ds);

        SaikuOssieConnection connection = new SaikuOssieConnection("RLS", dsProps);
        connection.connect();
        OssieFuzzIT.FakeConnectionManager connManager = new OssieFuzzIT.FakeConnectionManager();
        connManager.put("RLS", connection);

        OssieDiscoverService discover = new OssieDiscoverService();
        discover.setDatasourceManager(dsManager);
        OlapDiscoverService olap = new OssieFuzzIT.StubOlapDiscoverService(connManager);

        service = new OssieQueryService();
        service.setOssieDiscoverService(discover);
        service.setOlapDiscoverService(olap);
        async = new OssieAsyncQueryService(service);
    }

    @After
    public void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        if (async != null) async.shutdown();
        if (h2 != null) h2.close();
        if (yaml != null) Files.deleteIfExists(yaml);
    }

    @Test
    public void asyncResultMatchesSyncResultForRowPredicateCaller() throws Exception {
        authenticateAs("analyst", "ROLE_APAC");
        ThinQuery tq = thinQuery(regionRevenueQuery());

        // Control: the synchronous path the workbench uses.
        CellDataSet sync = service.execute(tq);
        assertEquals(1, sync.getCellSetBody().length);
        assertEquals("APAC", sync.getCellSetBody()[0][0].getFormattedValue());

        // The async path must produce the identical, role-scoped result.
        OssieAsyncQueryService.Handle h = async.submit(tq, "analyst");
        awaitTerminal(h);
        assertEquals(OssieAsyncQueryService.Status.DONE, h.getStatus());
        CellDataSet asyncResult = h.getResult();
        assertEquals(
                "async must return the same rows as sync",
                sync.getCellSetBody().length,
                asyncResult.getCellSetBody().length);
        assertEquals(
                "async row must be the predicate-scoped region",
                "APAC",
                asyncResult.getCellSetBody()[0][0].getFormattedValue());
        assertEquals(
                "async aggregate must match sync",
                sync.getCellSetBody()[0][1].getFormattedValue(),
                asyncResult.getCellSetBody()[0][1].getFormattedValue());
    }

    @Test
    public void asyncDeniedFieldFailsInsteadOfLeaking() throws Exception {
        authenticateAs("analyst", "ROLE_APAC");
        OssieAsyncQueryService.Handle h = async.submit(thinQuery(deniedFieldQuery()), "analyst");
        awaitTerminal(h);
        assertEquals(
                "a denied field must never complete with data on the async path",
                OssieAsyncQueryService.Status.FAILED,
                h.getStatus());
        assertNotNull(h.getErrorMessage());
        assertTrue(
                "error must be the translator's access denial, got: " + h.getErrorMessage(),
                h.getErrorMessage().contains("Access denied"));
    }

    @Test
    public void asyncUnrestrictedCallerStillSeesBothRegions() throws Exception {
        // Opt-in semantics intact: a caller whose roles match no predicate row sees the
        // unrestricted window, async and sync alike.
        SecurityContextHolder.clearContext();
        ThinQuery tq = thinQuery(regionRevenueQuery());
        CellDataSet sync = service.execute(tq);
        OssieAsyncQueryService.Handle h = async.submit(tq, "anon");
        awaitTerminal(h);
        assertEquals(OssieAsyncQueryService.Status.DONE, h.getStatus());
        assertEquals(
                "async must match sync for an unrestricted caller",
                sync.getCellSetBody().length,
                h.getResult().getCellSetBody().length);
    }

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    private static void awaitTerminal(OssieAsyncQueryService.Handle h) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            OssieAsyncQueryService.Status s = h.getStatus();
            if (s == OssieAsyncQueryService.Status.DONE
                    || s == OssieAsyncQueryService.Status.FAILED
                    || s == OssieAsyncQueryService.Status.CANCELLED) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("handle did not reach a terminal state in time, status=" + h.getStatus());
    }

    private static OssieQueryModel regionRevenueQuery() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("RLS");
        m.setModel("RLS");
        m.setFactDataset("fact");
        OssieQueryModel.FieldRef region = new OssieQueryModel.FieldRef();
        region.setDataset("geography");
        region.setField("region");
        m.setRows(List.of(region));
        OssieQueryModel.MetricRef revenue = new OssieQueryModel.MetricRef();
        revenue.setMetric("revenue");
        m.setValues(List.of(revenue));
        return m;
    }

    private static OssieQueryModel deniedFieldQuery() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("RLS");
        m.setModel("RLS");
        m.setFactDataset("fact");
        OssieQueryModel.FieldRef manager = new OssieQueryModel.FieldRef();
        manager.setDataset("geography");
        manager.setField("manager");
        m.setRows(List.of(manager));
        OssieQueryModel.MetricRef revenue = new OssieQueryModel.MetricRef();
        revenue.setMetric("revenue");
        m.setValues(List.of(revenue));
        return m;
    }

    private static ThinQuery thinQuery(OssieQueryModel model) {
        ThinQuery tq = new ThinQuery();
        tq.setName("async-rls-it");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(model);
        return tq;
    }
}
