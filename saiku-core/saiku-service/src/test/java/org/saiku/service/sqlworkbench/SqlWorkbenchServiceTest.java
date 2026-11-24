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

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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

    /**
     * saiku#2318 — a connection that reports a PostgreSQL URL and records every statement it is
     * handed, so the dialect read-only session statements can be asserted without a Postgres
     * container. Built with {@link java.lang.reflect.Proxy} because the JDBC interfaces are too
     * wide to hand-implement usefully; every other call is answered leniently (empty result set).
     */
    private static Connection recordingConnection(String url, List<String> statements, boolean failOnExecute) {
        return recordingConnection(url, statements, null, failOnExecute);
    }

    /**
     * As above, but also records every {@code setEscapeProcessing} flag into
     * {@code escapeProcessingCalls} (nullable) — saiku#2522 asserts the service disables escape
     * processing BEFORE the user's statement is handed to the driver.
     */
    private static Connection recordingConnection(
            String url, List<String> statements, List<Boolean> escapeProcessingCalls, boolean failOnExecute) {
        Statement statement = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, (proxy, method, args) -> {
                    if ("setEscapeProcessing".equals(method.getName()) && escapeProcessingCalls != null) {
                        escapeProcessingCalls.add((Boolean) args[0]);
                        return null;
                    }
                    if ("execute".equals(method.getName())) {
                        statements.add((String) args[0]);
                        if (failOnExecute) {
                            throw new SQLException("read-only session refused");
                        }
                        return false;
                    }
                    if ("executeQuery".equals(method.getName())) {
                        statements.add((String) args[0]);
                        return emptyResultSet();
                    }
                    return lenient(method);
                });
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return statement;
                    }
                    if ("getMetaData".equals(method.getName())) {
                        return (DatabaseMetaData) Proxy.newProxyInstance(
                                DatabaseMetaData.class.getClassLoader(),
                                new Class<?>[] {DatabaseMetaData.class},
                                (m, mm, ma) -> "getURL".equals(mm.getName()) ? url : lenient(mm));
                    }
                    return lenient(method);
                });
    }

    /** A one-column, zero-row result set — enough for the success path to walk harmlessly. */
    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return (ResultSetMetaData) Proxy.newProxyInstance(
                                ResultSetMetaData.class.getClassLoader(),
                                new Class<?>[] {ResultSetMetaData.class},
                                (m, mm, ma) -> "getColumnCount".equals(mm.getName()) ? 1 : lenient(mm));
                    }
                    return lenient(method);
                });
    }

    /** Zero/false for the primitive returns the JDBC mocks can be handed; null otherwise. */
    private static Object lenient(java.lang.reflect.Method method) {
        Class<?> returns = method.getReturnType();
        if (returns == boolean.class) return false;
        if (returns == int.class) return 0;
        if (returns == long.class) return 0L;
        return null;
    }

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
        sqlExecRoles = new String[] {"ROLE_SQL_EXEC"};
    }

    private String[] sqlExecRoles;

    @After
    public void tearDown() throws Exception {
        if (seedConn != null && !seedConn.isClosed()) {
            seedConn.close();
        }
    }

    @Test
    public void selectOne_returnsOneRowOneColumn() {
        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT 1", null);
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
        SqlWorkbenchService.SqlQueryResult result = service.execute(
                "alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT id, name FROM customer ORDER BY id", null);
        assertEquals(List.of("ID", "NAME"), result.columns());
        assertEquals(2, result.rowCount());
        assertEquals(List.of(1, "alice"), result.rows().get(0));
        assertEquals(List.of(2, "bob"), result.rows().get(1));
    }

    @Test
    public void rowsAreCappedAtMaxRows_andFlaggedTruncated() {
        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT * FROM customer ORDER BY id", 1);
        assertEquals(1, result.rowCount());
        assertTrue(result.truncated());
    }

    @Test
    public void insert_isRejectedBeforeTouchingTheConnection() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute(
                        "alice", sqlExecRoles, DATA_SOURCE_ID, "INSERT INTO customer VALUES (3, 'carol')", null));
        assertEquals(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, ex.getCode());

        // The row never made it in — the guard fired before a connection was ever opened.
        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT COUNT(*) FROM customer", null);
        assertEquals(2L, result.rows().get(0).get(0));

        // recent() is newest-first: index 0 is the SELECT COUNT(*) that ran second, index 1 is
        // the rejected INSERT that ran first.
        List<SqlWorkbenchAuditEntry> audited = auditLog.recent(10, 0, null);
        assertEquals(SqlWorkbenchAuditEntry.OUTCOME_REJECTED, audited.get(1).outcome);
    }

    @Test
    public void unknownDatasource_isRejected() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute("alice", sqlExecRoles, "no-such-ds", "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
    }

    /**
     * saiku#2318 — EXPLAIN ANALYZE executes the DML it explains on PostgreSQL/H2/DuckDB/MySQL. The
     * guard must fire before a connection is opened: the row count never changes and the audit
     * entry is REJECTED.
     */
    @Test
    public void explainAnalyzeDelete_isRejectedBeforeTouchingTheConnection() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute(
                        "alice", sqlExecRoles, DATA_SOURCE_ID, "EXPLAIN ANALYZE DELETE FROM customer", null));
        assertEquals(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, ex.getCode());

        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT COUNT(*) FROM customer", null);
        assertEquals(2L, result.rows().get(0).get(0));

        List<SqlWorkbenchAuditEntry> audited = auditLog.recent(10, 0, null);
        assertEquals(SqlWorkbenchAuditEntry.OUTCOME_REJECTED, audited.get(1).outcome);
    }

    /**
     * saiku#2318 — the datasource field is a free-text body field, so the target is re-checked
     * against the role-filtered datasource map: a caller whose roles do not expose the datasource
     * gets the same answer as for a datasource that does not exist.
     */
    @Test
    public void execute_rejectsDatasourceCallerIsNotEntitledTo() {
        DatasourceService excluding = new StubDatasourceService(DATA_SOURCE_ID) {
            @Override
            public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                // Simulates the role filter excluding everything for this caller.
                return Map.of();
            }
        };
        SqlWorkbenchService scoped =
                new SqlWorkbenchService(id -> DriverManager.getConnection(JDBC_URL, "sa", ""), excluding, auditLog);

        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> scoped.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
        assertEquals(
                SqlWorkbenchAuditEntry.OUTCOME_ERROR,
                auditLog.recent(1, 0, null).get(0).outcome);
    }

    /**
     * saiku#2394 — the collision bypass. A caller may SEE {@code sales_payroll} but is denied
     * {@code payroll}. The old check tolerated any single {@code <segment>_} prefix, so the
     * visible {@code sales_payroll} authorised a request for the distinct restricted
     * {@code payroll}, and the connection provider opened it with its stored credentials. The
     * resolved datasource record must itself be in the caller's role-filtered map; the query is
     * rejected before a JDBC connection is opened.
     */
    @Test
    public void execute_rejectsRestrictedSuffixWhenAPrefixedNameIsVisible() {
        List<String> openedFor = new ArrayList<>();
        SqlWorkbenchService colliding = new SqlWorkbenchService(
                id -> {
                    openedFor.add(id);
                    return DriverManager.getConnection(JDBC_URL, "sa", "");
                },
                new StubDatasourceService(DATA_SOURCE_ID) {
                    @Override
                    public SaikuDatasource getDatasource(String datasourceName) {
                        // The restricted 'payroll' resolves by name — exactly what the connection
                        // provider would open.
                        return "payroll".equals(datasourceName)
                                ? new SaikuDatasource("payroll", SaikuDatasource.Type.OLAP, null)
                                : super.getDatasource(datasourceName);
                    }

                    @Override
                    public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                        // The role filter only shows the unrelated prefixed datasource.
                        return Map.of(
                                "sales_payroll", new SaikuDatasource("sales_payroll", SaikuDatasource.Type.OLAP, null));
                    }
                },
                auditLog);

        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> colliding.execute("alice", sqlExecRoles, "payroll", "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
        // The restricted datasource was never opened.
        assertTrue(openedFor.isEmpty());
        assertEquals(
                SqlWorkbenchAuditEntry.OUTCOME_ERROR,
                auditLog.recent(1, 0, null).get(0).outcome);
    }

    /**
     * saiku#2394 — no over-correction: requesting an authorized datasource by its UUID (a legacy
     * alias the lookup has always supported) still executes, because authorisation compares the
     * RESOLVED record's immutable id against the role-filtered map.
     */
    @Test
    public void execute_acceptsDatasourceResolvedByUuidWhenVisible() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("id", "ds-uuid-1");
        SaikuDatasource visible = new SaikuDatasource("visible-ds", SaikuDatasource.Type.OLAP, props);
        DatasourceService byId = new StubDatasourceService(DATA_SOURCE_ID) {
            @Override
            public SaikuDatasource getDatasource(String datasourceName) {
                return null; // the UUID never matches the connection name
            }

            @Override
            public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                return Map.of("visible-ds", visible);
            }
        };
        SqlWorkbenchService service2 =
                new SqlWorkbenchService(id -> DriverManager.getConnection(JDBC_URL, "sa", ""), byId, auditLog);

        SqlWorkbenchService.SqlQueryResult result =
                service2.execute("alice", sqlExecRoles, "ds-uuid-1", "SELECT 1", null);
        assertEquals(1, result.rowCount());
    }

    /**
     * saiku#2394 — exact and decorated-name requests for an authorized datasource still work: the
     * workspace-decoration alias is applied at RESOLUTION, not at authorisation.
     */
    @Test
    public void execute_acceptsDecoratedNameAliasForAuthorizedDatasource() {
        SaikuDatasource decorated = new SaikuDatasource("unknown_test-ds", SaikuDatasource.Type.OLAP, null);
        DatasourceService aliasing = new StubDatasourceService(DATA_SOURCE_ID) {
            @Override
            public SaikuDatasource getDatasource(String datasourceName) {
                // Mirror the saiku#1869 lookup alias: either spelling resolves to the one record.
                return datasourceName.equals("test-ds") || datasourceName.equals("unknown_test-ds") ? decorated : null;
            }

            @Override
            public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                return Map.of("unknown_test-ds", decorated);
            }
        };
        SqlWorkbenchService service2 =
                new SqlWorkbenchService(id -> DriverManager.getConnection(JDBC_URL, "sa", ""), aliasing, auditLog);

        assertEquals(
                1,
                service2.execute("alice", sqlExecRoles, "unknown_test-ds", "SELECT 1", null)
                        .rowCount());
        assertEquals(
                1,
                service2.execute("alice", sqlExecRoles, "test-ds", "SELECT 1", null)
                        .rowCount());
    }

    /**
     * saiku#2318 — on a backend with a session-level read-only switch, the service runs that
     * statement on the workbench connection BEFORE the caller's statement.
     */
    @Test
    public void sessionIsEnforcedReadOnly_theDialectStatementRunsBeforeTheQuery() {
        List<String> statements = new ArrayList<>();
        SqlWorkbenchService postgres = new SqlWorkbenchService(
                id -> recordingConnection("jdbc:postgresql://host:5432/warehouse", statements, false),
                new StubDatasourceService(DATA_SOURCE_ID),
                auditLog);

        postgres.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT 1", null);
        assertEquals(List.of("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY", "SELECT 1"), statements);
    }

    /**
     * saiku#2318 — fail closed: when the datasource refuses the read-only session, the caller's
     * statement is never executed and the failure is surfaced (and audited) as QUERY_FAILED.
     */
    @Test
    public void sessionIsEnforcedReadOnly_writeFailsAtDriver_failsClosedWithoutRunningTheStatement() {
        List<String> statements = new ArrayList<>();
        SqlWorkbenchService postgres = new SqlWorkbenchService(
                id -> recordingConnection("jdbc:postgresql://host:5432/warehouse", statements, true),
                new StubDatasourceService(DATA_SOURCE_ID),
                auditLog);

        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> postgres.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.QUERY_FAILED, ex.getCode());
        // The caller's statement never reached the driver — only the failed SET SESSION did.
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).toUpperCase().contains("READ ONLY"));
        assertEquals(
                SqlWorkbenchAuditEntry.OUTCOME_ERROR,
                auditLog.recent(1, 0, null).get(0).outcome);
        assertFalse(ex.getMessage().contains("org.saiku"));
    }

    @Test
    public void aQueryErrorIsAuditedAndSurfacedWithoutAStackTrace() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT * FROM no_such_table", null));
        assertEquals(SqlWorkbenchException.Code.QUERY_FAILED, ex.getCode());
        assertFalse(ex.getMessage().contains("org.saiku"));
        assertFalse(ex.getMessage().contains("Exception"));
    }

    /**
     * saiku#2522 — the guard validates one text and the service executes exactly that text, with
     * JDBC escape processing disabled: the recording connection sees the validated statement —
     * byte-for-byte the submission when there is nothing to strip, the comment-stripped text
     * otherwise — never the raw submission with a comment the engine could re-read differently.
     */
    @Test
    public void theValidatedStatementIsTheExecutedStatement_escapeProcessingDisabled() {
        List<String> statements = new ArrayList<>();
        List<Boolean> escapeCalls = new ArrayList<>();
        SqlWorkbenchService guarded = new SqlWorkbenchService(
                id -> recordingConnection("jdbc:h2:mem:x", statements, escapeCalls, false),
                new StubDatasourceService(DATA_SOURCE_ID),
                auditLog);

        guarded.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT 1 AS a", null);
        assertEquals(1, statements.size());
        // Comment-free: what was validated IS what was submitted IS what was executed.
        assertEquals("SELECT 1 AS a", statements.get(0));

        // With comments: the executed text is the comment-stripped, validated text — an engine
        // cannot re-interpret a comment the guard has already read past.
        guarded.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "/* plan */ SELECT 2 -- note\n", null);
        assertEquals("SELECT 2", statements.get(1));

        // Escape processing was turned OFF before the statement reached the driver.
        assertEquals(2, escapeCalls.size());
        assertFalse(escapeCalls.get(0));
        assertFalse(escapeCalls.get(1));
    }

    /**
     * saiku#2522 (integration, real H2) — a line comment ended by {@code \r} is a comment to H2
     * too, so the executed statement would gain a second column. The guard must reject it before
     * a connection is opened.
     */
    @Test
    public void crEndedComment_hidingExtraColumns_isRejectedBeforeExecution() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT 1 AS a --\r, 2 AS b", null));
        assertEquals(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, ex.getCode());
        assertEquals(
                SqlWorkbenchAuditEntry.OUTCOME_REJECTED,
                auditLog.recent(1, 0, null).get(0).outcome);
    }

    /**
     * saiku#2522 (integration, real H2) — stacked DDL behind a CR-ended comment, the exact
     * payload from the issue: the temporary table must never be created.
     */
    @Test
    public void stackedDdl_behindACrEndedComment_neverReachesRealH2() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute(
                        "alice",
                        sqlExecRoles,
                        DATA_SOURCE_ID,
                        "SELECT 1 AS a --\r; CREATE LOCAL TEMPORARY TABLE T(X INT); DROP TABLE T",
                        null));
        assertEquals(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, ex.getCode());
        assertEquals(0, tableCount("T"));
    }

    /**
     * saiku#2522 (integration, real H2) — a JDBC function escape must never reach the driver: on
     * H2 {@code {fn DATABASE_PATH()}} answers 200 today; the guard rejects it first.
     */
    @Test
    public void jdbcEscapeFunction_isRejectedBeforeExecution() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT {fn DATABASE_PATH()} AS p", null));
        assertEquals(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, ex.getCode());
        assertEquals(
                SqlWorkbenchAuditEntry.OUTCOME_REJECTED,
                auditLog.recent(1, 0, null).get(0).outcome);
    }

    /**
     * saiku#2522 (integration, real H2) — the SHOW shortcut cannot carry a second statement
     * behind a CR-ended comment.
     */
    @Test
    public void showShortcut_carryingStackedDdl_neverReachesRealH2() {
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> service.execute(
                        "alice",
                        sqlExecRoles,
                        DATA_SOURCE_ID,
                        "SHOW TABLES --\r; CREATE LOCAL TEMPORARY TABLE T3(X INT); DROP TABLE T3",
                        null));
        assertEquals(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, ex.getCode());
        assertEquals(0, tableCount("T3"));
    }

    /** Rows in INFORMATION_SCHEMA.TABLES matching a table name (0 when it was never created). */
    private long tableCount(String tableName) {
        SqlWorkbenchService.SqlQueryResult result = service.execute(
                "alice",
                sqlExecRoles,
                DATA_SOURCE_ID,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = '" + tableName + "'",
                null);
        return (Long) result.rows().get(0).get(0);
    }

    /** Hand-rolled stub (house pattern — saiku-service tests avoid Mockito for DatasourceService). */
    private static class StubDatasourceService extends DatasourceService {
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
