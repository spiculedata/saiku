/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.exception.SaikuValidationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * End-to-end proof for saiku#2526: the field-level {@code saiku.roles} allow/deny cannot be
 * bypassed by naming the hidden field's PHYSICAL column. On the base behaviour, the shelf
 * translator emitted any identifier it could not resolve as a quoted physical column, so a caller
 * denied {@code email} could ask for {@code EMAIL_ADDRESS} — through {@code rows}, through a
 * {@code filters} entry (with or without {@code dataset}) or through {@code sorts} — and read the
 * restricted values over the real {@link OssieQueryService#execute} path (shelf state → SQL →
 * live H2 warehouse). Every variant is now refused before any SQL is emitted.
 *
 * <p>Fixture: a {@code customer} dataset whose {@code email} field (physical column
 * {@code EMAIL_ADDRESS}) is restricted to {@code ROLE_ADMIN}. Caller roles are supplied the same
 * way production does — a Spring Security authentication on {@link SecurityContextHolder} — since
 * {@link OssieQueryService} reads them from there.
 */
public class OssieRoleBypassIT {

    private static final String H2_URL = "jdbc:h2:mem:saiku_ossie_bypass;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Connection h2;
    private Path yaml;
    private OssieQueryService service;

    @Before
    public void setUp() throws Exception {
        h2 = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement s = h2.createStatement()) {
            s.execute("DROP TABLE IF EXISTS CUSTOMER");
            s.execute("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, EMAIL_ADDRESS VARCHAR(64), LAST_NAME VARCHAR(32))");
            s.execute("INSERT INTO CUSTOMER VALUES (1,'alice@example.com','Smith'),(2,'bob@example.com','Jones')");
        }

        yaml = Files.createTempFile("ossie-bypass-", ".yaml");
        Files.writeString(
                yaml,
                "version: 0.2.0.dev0\n"
                        + "semantic_model:\n"
                        + "- name: BYPASS\n"
                        + "  datasets:\n"
                        + "  - name: customer\n"
                        + "    source: CUSTOMER\n"
                        + "    primary_key: [ID]\n"
                        + "    fields:\n"
                        + "    - name: id\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: ID\n"
                        + "    - name: email\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: EMAIL_ADDRESS\n"
                        + "      custom_extensions:\n"
                        + "      - vendor_name: SAIKU\n"
                        + "        data: '{\"roles\": {\"allow\": [\"ROLE_ADMIN\"]}}'\n"
                        + "    - name: last_name\n      expression:\n        dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: LAST_NAME\n"
                        + "  metrics:\n"
                        + "  - name: customers\n    expression:\n      dialects:\n"
                        + "        - dialect: ANSI_SQL\n          expression: COUNT(*)\n");

        Properties dsProps = new Properties();
        dsProps.setProperty(ISaikuConnection.OSSIE_YAML_KEY, yaml.toString());
        dsProps.setProperty(ISaikuConnection.URL_KEY, H2_URL);
        dsProps.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        dsProps.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        dsProps.setProperty("schema", "BYPASS");
        SaikuDatasource ds = new SaikuDatasource("BYPASS", SaikuDatasource.Type.OSSIE, dsProps);
        OssieFuzzIT.StubDatasourceManager dsManager = new OssieFuzzIT.StubDatasourceManager();
        dsManager.put("BYPASS", ds);

        SaikuOssieConnection connection = new SaikuOssieConnection("BYPASS", dsProps);
        connection.connect();
        OssieFuzzIT.FakeConnectionManager connManager = new OssieFuzzIT.FakeConnectionManager();
        connManager.put("BYPASS", connection);

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

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    private static ThinQuery thinQuery(OssieQueryModel model) {
        ThinQuery tq = new ThinQuery();
        tq.setName("bypass-it");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(model);
        return tq;
    }

    private static OssieQueryModel rowsModel(String field) {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("BYPASS");
        m.setModel("BYPASS");
        m.setFactDataset("customer");
        OssieQueryModel.FieldRef row = new OssieQueryModel.FieldRef();
        row.setDataset("customer");
        row.setField(field);
        m.setRows(List.of(row));
        return m;
    }

    /** The hidden field's semantic name on rows is an access denial (saiku#1393 baseline). */
    @Test
    public void deniedFieldByNameIsDenied() throws Exception {
        authenticateAs("analyst", "ROLE_USER");
        try {
            service.execute(thinQuery(rowsModel("email")));
            fail("expected SaikuAccessDeniedException");
        } catch (SaikuAccessDeniedException expected) {
            // expected
        }
    }

    /** THE bypass: the hidden field's physical column on rows must now be refused too. */
    @Test
    public void physicalColumnNameOnRowsIsRefused() throws Exception {
        authenticateAs("analyst", "ROLE_USER");
        try {
            service.execute(thinQuery(rowsModel("EMAIL_ADDRESS")));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    /** The physical column in a filter that names no dataset (previously skipped entirely). */
    @Test
    public void physicalColumnNameInUnqualifiedFilterIsRefused() throws Exception {
        authenticateAs("analyst", "ROLE_USER");
        OssieQueryModel m = rowsModel("last_name");
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setField("EMAIL_ADDRESS");
        f.setOp("EQ");
        f.setValue("alice@example.com");
        m.setFilters(List.of(f));
        try {
            service.execute(thinQuery(m));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    /** The physical column in a qualified filter — also an equality oracle on the hidden values. */
    @Test
    public void physicalColumnNameInQualifiedFilterIsRefused() throws Exception {
        authenticateAs("analyst", "ROLE_USER");
        OssieQueryModel m = rowsModel("last_name");
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setDataset("customer");
        f.setField("EMAIL_ADDRESS");
        f.setOp("EQ");
        f.setValue("alice@example.com");
        m.setFilters(List.of(f));
        try {
            service.execute(thinQuery(m));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    /** The physical column in sorts — an ordering oracle on the hidden values. */
    @Test
    public void physicalColumnNameInSortIsRefused() throws Exception {
        authenticateAs("analyst", "ROLE_USER");
        OssieQueryModel m = rowsModel("last_name");
        OssieQueryModel.SortRef s = new OssieQueryModel.SortRef();
        s.setDataset("customer");
        s.setField("EMAIL_ADDRESS");
        s.setDirection("ASC");
        m.setSorts(List.of(s));
        try {
            service.execute(thinQuery(m));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    /** An unrestricted field still executes end to end for the same restricted caller. */
    @Test
    public void unrestrictedFieldStillExecutesForRestrictedCaller() throws Exception {
        authenticateAs("analyst", "ROLE_USER");
        OssieQueryModel m = rowsModel("last_name");
        OssieQueryModel.MetricRef v = new OssieQueryModel.MetricRef();
        v.setMetric("customers");
        m.setValues(List.of(v));
        assertEquals(2, service.execute(thinQuery(m)).getCellSetBody().length);
    }

    /** The admin role reads the restricted field unchanged — the allow window still opens. */
    @Test
    public void adminRoleStillReadsTheRestrictedField() throws Exception {
        authenticateAs("root", "ROLE_ADMIN");
        OssieQueryModel m = rowsModel("email");
        assertEquals(2, service.execute(thinQuery(m)).getCellSetBody().length);
    }
}
