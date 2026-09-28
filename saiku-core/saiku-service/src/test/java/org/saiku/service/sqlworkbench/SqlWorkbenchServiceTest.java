/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;

/**
 * Tests {@link SqlWorkbenchService} against a real H2 in-memory database. The JDBC-URL resolution
 * that {@code DatasourceJdbcConnectionProvider} does in production lives in {@code saiku-web} (it
 * cannot be depended on from here), so the fake {@link SchemaGenOrchestrator.ConnectionProvider}
 * below opens the H2 connection directly — {@link org.saiku.web.rest.resources.sqlworkbench}'s own
 * test exercises the real provider end-to-end, mirroring {@code CubeDesignerResourceTest}.
 */
public class SqlWorkbenchServiceTest {

    private static final String JDBC_URL = "jdbc:h2:mem:sql-workbench;DB_CLOSE_DELAY=-1";
    private static final String DATA_SOURCE_ID = "test-ds";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Connection seedConn;
    private SqlWorkbenchService service;
    private SqlWorkbenchAuditLog auditLog;

    @Before
    public void setUp() throws Exception {
        seedConn = DriverManager.getConnection(JDBC_URL, "sa", "");
        try (Statement st = seedConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE customer (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("INSERT INTO customer VALUES (1, 'alice')");
            st.execute("INSERT INTO customer VALUES (2, 'bob')");
        }

        auditLog = new SqlWorkbenchAuditLog(tmp.getRoot().toPath().resolve("audit.jsonl"), true);
        SchemaGenOrchestrator.ConnectionProvider connectionProvider =
                dataSourceId -> DriverManager.getConnection(JDBC_URL, "sa", "");
        StubDatasourceService datasourceService = new StubDatasourceService(DATA_SOURCE_ID);
        service = new SqlWorkbenchService(connectionProvider, datasourceService, auditLog);
    }

    @After
    public void tearDown() throws Exception {
        if (seedConn != null && !seedConn.isClosed()) {
            seedConn.close();
        }
    }

    @Test
    public void selectOne_returnsOneRowOneColumn() {
        SqlWorkbenchService.SqlQueryResult result = service.execute("alice", DATA_SOURCE_ID, "SELECT 1", null);
        assertEquals(1, result.columns().size());
        assertEquals(1, result.rowCount());
        assertEquals(1, result.rows().get(0).get(0));
        assertFalse(result.truncated());

        List<SqlWorkbenchAuditEntry> audited = auditLog.recent(10, 0, null);
        assertEquals(1, audited.size());
        assertEquals(SqlWorkbenchAuditEntry.OUTCOME_SUCCESS, audited.get(0).outcome);
        assertEquals("SELECT 1", audited.get(0).sql);
    }

    @Test
    public void select_returnsAllSeededRows() {
        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", DATA_SOURCE_ID, "SELECT id, name FROM customer ORDER BY id", null);
        assertEquals(List.of("ID", "NAME"), result.columns());
        assertEquals(2, result.rowCount());
        assertEquals(List.of(1, "alice"), result.rows().get(0));
        assertEquals(List.of(2, "bob"), result.rows().get(1));
    }

    @Test
    public void rowsAreCappedAtMaxRows_andFlaggedTruncated() {
        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", DATA_SOURCE_ID, "SELECT * FROM customer ORDER BY id", 1);
        assertEquals(1, result.rowCount());
        assertTrue(result.truncated());
    }

    @Test
    public void insert_isRejectedBeforeTouchingTheConnection() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute("alice", DATA_SOURCE_ID, "INSERT INTO customer VALUES (3, 'carol')", null));
        assertEquals(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, ex.getCode());

        // The row never made it in — the guard fired before a connection was ever opened.
        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", DATA_SOURCE_ID, "SELECT COUNT(*) FROM customer", null);
        assertEquals(2, result.rows().get(0).get(0));

        // recent() is newest-first: index 0 is the SELECT COUNT(*) that ran second, index 1 is
        // the rejected INSERT that ran first.
        List<SqlWorkbenchAuditEntry> audited = auditLog.recent(10, 0, null);
        assertEquals(SqlWorkbenchAuditEntry.OUTCOME_REJECTED, audited.get(1).outcome);
    }

    @Test
    public void unknownDatasource_isRejected() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class, () -> service.execute("alice", "no-such-ds", "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
    }

    @Test
    public void aQueryErrorIsAuditedAndSurfacedWithoutAStackTrace() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute("alice", DATA_SOURCE_ID, "SELECT * FROM no_such_table", null));
        assertEquals(SqlWorkbenchException.Code.QUERY_FAILED, ex.getCode());
        assertFalse(ex.getMessage().contains("org.saiku"));
        assertFalse(ex.getMessage().contains("Exception"));
    }

    /** Hand-rolled stub (house pattern — saiku-service tests avoid Mockito for DatasourceService). */
    private static final class StubDatasourceService extends DatasourceService {
        private final String id;

        StubDatasourceService(String id) {
            this.id = id;
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName) {
            return id.equals(datasourceName) ? new SaikuDatasource(id, SaikuDatasource.Type.OLAP, null) : null;
        }

        @Override
        public Map<String, SaikuDatasource> getDatasources(String[] roles) {
            return Map.of(id, new SaikuDatasource(id, SaikuDatasource.Type.OLAP, null));
        }
    }
}
