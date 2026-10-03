/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuOssieConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.OlapDiscoverService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * End-to-end proof for saiku#1393: a caller holding a role with a {@code row_predicates} entry
 * sees a strict subset of the rows an unrestricted caller sees, over the real {@link
 * OssieQueryService#execute} path (shelf state → SQL → live H2 warehouse → {@link CellDataSet}).
 *
 * <p>Fixture: 3 fact rows across 2 regions (APAC ×2, EMEA ×1). The {@code geography} dataset
 * carries a {@code ROLE_APAC} row predicate restricting to {@code REGION = 'APAC'}. Caller roles
 * are supplied the same way production does — a Spring Security {@link
 * org.springframework.security.core.Authentication} on {@link SecurityContextHolder} — since
 * {@link OssieQueryService} reads them from there, not from a parameter.
 */
public class OssieRoleSecurityIT {

    private static final String H2_URL = "jdbc:h2:mem:saiku_ossie_rls;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Connection h2;
    private Path yaml;
    private OssieQueryService service;

    @Before
    public void setUp() throws Exception {
        h2 = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement s = h2.createStatement()) {
            s.execute("DROP TABLE IF EXISTS FACT");
            s.execute("DROP TABLE IF EXISTS GEOGRAPHY");
            s.execute("CREATE TABLE GEOGRAPHY (GEO_ID INT PRIMARY KEY, REGION VARCHAR(32))");
            s.execute("INSERT INTO GEOGRAPHY VALUES (1,'APAC'),(2,'EMEA')");
            s.execute("CREATE TABLE FACT (ID INT PRIMARY KEY, GEO_ID INT, AMOUNT DECIMAL(10,2))");
            s.execute("INSERT INTO FACT VALUES (1,1,100.00),(2,2,200.00),(3,1,150.00)");
        }

        yaml = Files.createTempFile("ossie-rls-", ".yaml");
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
    }

    @After
    public void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        if (h2 != null) h2.close();
        if (yaml != null) Files.deleteIfExists(yaml);
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

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    private static ThinQuery thinQuery(OssieQueryModel model) {
        ThinQuery tq = new ThinQuery();
        tq.setName("rls-it");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(model);
        return tq;
    }

    @Test
    public void unrestrictedCallerSeesBothRegions() throws Exception {
        SecurityContextHolder.clearContext(); // no authentication at all — same as an unauthenticated caller
        CellDataSet result = service.execute(thinQuery(regionRevenueQuery()));
        assertEquals(2, result.getCellSetBody().length);
    }

    @Test
    public void apacRoleCallerSeesOnlyApac() throws Exception {
        authenticateAs("analyst", "ROLE_APAC");
        CellDataSet result = service.execute(thinQuery(regionRevenueQuery()));
        AbstractBaseCell[][] body = result.getCellSetBody();
        assertEquals(1, body.length);
        assertEquals("APAC", body[0][0].getFormattedValue());
        assertEquals("250.00", body[0][1].getFormattedValue());
    }

    @Test
    public void emeaOnlyRoleCallerStillSeesUnrestrictedEmea() throws Exception {
        // EMEA has no row_predicates entry naming it, so a caller with an unrelated role sees it
        // unrestricted — row-level security here is opt-in per dataset/role, not a blanket gate.
        authenticateAs("guest", "ROLE_UNRELATED");
        CellDataSet result = service.execute(thinQuery(regionRevenueQuery()));
        assertEquals(2, result.getCellSetBody().length);
    }
}
