/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.sqlworkbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.sqlworkbench.SqlWorkbenchAuditLog;
import org.saiku.service.sqlworkbench.SqlWorkbenchService;
import org.saiku.web.rest.resources.schemagen.DatasourceJdbcConnectionProvider;
import org.saiku.web.rest.resources.sqlworkbench.SqlWorkbenchResource.ErrorBody;
import org.saiku.web.rest.resources.sqlworkbench.SqlWorkbenchResource.QueryRequest;

/**
 * End-to-end tests for {@link SqlWorkbenchResource} against a real H2 in-memory DB, mirroring
 * {@code CubeDesignerResourceTest}: the datasource service is stubbed to hand back a
 * {@link SaikuDatasource} whose location is the H2 JDBC URL, so the production
 * {@link DatasourceJdbcConnectionProvider} resolves a live connection exactly as it would against
 * a real Saiku datasource. {@code @RolesAllowed} enforcement is Jersey's job (RolesAllowedDynamicFeature),
 * not exercised here — this covers the resource's own request handling and error mapping.
 */
public class SqlWorkbenchResourceTest {

    private static final String JDBC_URL = "jdbc:h2:mem:sql-workbench-resource;DB_CLOSE_DELAY=-1";
    private static final String DATA_SOURCE_ID = "test-ds";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Connection seedConn;
    private SqlWorkbenchResource resource;

    @Before
    public void setUp() throws Exception {
        seedConn = DriverManager.getConnection(JDBC_URL, "sa", "");
        try (Statement st = seedConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE customer (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("INSERT INTO customer VALUES (1, 'alice')");
        }

        Properties props = new Properties();
        props.setProperty(ISaikuConnection.URL_KEY, JDBC_URL);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        SaikuDatasource ds = new SaikuDatasource(DATA_SOURCE_ID, SaikuDatasource.Type.OLAP, props);

        StubDatasourceService datasourceService = new StubDatasourceService(DATA_SOURCE_ID, ds);
        DatasourceJdbcConnectionProvider provider = new DatasourceJdbcConnectionProvider(datasourceService);
        Path auditFile = tmp.getRoot().toPath().resolve("sql-workbench-audit.jsonl");
        SqlWorkbenchService service =
                new SqlWorkbenchService(provider, datasourceService, new SqlWorkbenchAuditLog(auditFile, true));
        resource = new SqlWorkbenchResource(service);
        // userService left null -> falls back to an empty role set / "unknown" username (headless).
    }

    @After
    public void tearDown() throws Exception {
        if (seedConn != null && !seedConn.isClosed()) {
            seedConn.close();
        }
    }

    @Test
    public void datasources_listsTheStubbedDatasource() {
        List<SqlWorkbenchService.DatasourceView> datasources = resource.datasources();
        assertEquals(1, datasources.size());
        assertEquals(DATA_SOURCE_ID, datasources.get(0).name());
    }

    @Test
    public void query_selectOne_returns200WithTheRow() {
        Response response = resource.query(new QueryRequest(DATA_SOURCE_ID, "SELECT 1", null));
        assertEquals(200, response.getStatus());
        SqlWorkbenchService.SqlQueryResult result = (SqlWorkbenchService.SqlQueryResult) response.getEntity();
        assertEquals(1, result.rowCount());
        assertEquals(1, result.rows().get(0).get(0));
    }

    @Test
    public void query_insert_returns400WithReadOnlyViolationCode() {
        Response response =
                resource.query(new QueryRequest(DATA_SOURCE_ID, "INSERT INTO customer VALUES (2, 'bob')", null));
        assertEquals(400, response.getStatus());
        ErrorBody body = (ErrorBody) response.getEntity();
        assertEquals("READ_ONLY_VIOLATION", body.code());
    }

    @Test
    public void query_missingSql_returns400() {
        Response response = resource.query(new QueryRequest(DATA_SOURCE_ID, "", null));
        assertEquals(400, response.getStatus());
        ErrorBody body = (ErrorBody) response.getEntity();
        assertEquals("INVALID_REQUEST", body.code());
    }

    @Test
    public void query_missingDatasource_returns400() {
        Response response = resource.query(new QueryRequest("", "SELECT 1", null));
        assertEquals(400, response.getStatus());
        ErrorBody body = (ErrorBody) response.getEntity();
        assertEquals("INVALID_REQUEST", body.code());
    }

    @Test
    public void query_unknownDatasource_returns400WithUnknownDatasourceCode() {
        Response response = resource.query(new QueryRequest("no-such-ds", "SELECT 1", null));
        assertEquals(400, response.getStatus());
        ErrorBody body = (ErrorBody) response.getEntity();
        assertEquals("UNKNOWN_DATASOURCE", body.code());
    }

    @Test
    public void query_errorMessageNeverLeaksAJavaStackTrace() {
        Response response = resource.query(new QueryRequest(DATA_SOURCE_ID, "SELECT * FROM no_such_table", null));
        assertEquals(400, response.getStatus());
        ErrorBody body = (ErrorBody) response.getEntity();
        assertTrue(body.message() != null && !body.message().contains("org.saiku"));
    }

    /** Hand-rolled stub (house pattern — saiku-web has no Mockito), mirrors CubeDesignerResourceTest's. */
    private static final class StubDatasourceService extends DatasourceService {
        private final String id;
        private final SaikuDatasource ds;

        StubDatasourceService(String id, SaikuDatasource ds) {
            this.id = id;
            this.ds = ds;
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName) {
            return id.equals(datasourceName) ? ds : null;
        }

        @Override
        public Map<String, SaikuDatasource> getDatasources(String[] roles) {
            return Map.of(ds.getName(), ds);
        }
    }
}
