/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.user.UserService;

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
     * saiku#2532 — a security-enabled datasource whose OLAP-side decision the workbench cannot
     * reproduce is refused BEFORE a JDBC connection is opened, with the same unknown-datasource
     * answer as a datasource that does not exist (never a distinguishable "exists but denied").
     * Covers one2one and passthrough: the connection path would deny a caller with no Mondrian
     * role / no warehouse credentials — but the workbench opens raw JDBC with the stored
     * credentials, so over SQL the denial would never fire.
     */
    @Test
    public void execute_refusesOne2OneAndPassthroughSecurityEnabledDatasources() {
        for (String securityType : new String[] {
            ISaikuConnection.SECURITY_TYPE_SPRING2MONDRIAN_VALUE, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE
        }) {
            java.util.Properties props = new java.util.Properties();
            props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
            props.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, securityType);
            SaikuDatasource secured = new SaikuDatasource("secured-ds", SaikuDatasource.Type.OLAP, props);
            List<String> openedFor = new ArrayList<>();
            SqlWorkbenchService securedService = new SqlWorkbenchService(
                    id -> {
                        openedFor.add(id);
                        return DriverManager.getConnection(JDBC_URL, "sa", "");
                    },
                    new StubDatasourceService("secured-ds") {
                        @Override
                        public SaikuDatasource getDatasource(String datasourceName) {
                            return "secured-ds".equals(datasourceName) ? secured : null;
                        }

                        @Override
                        public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                            // The shared visibility filter leaves one2one/passthrough visible —
                            // over OLAP the connection path denies. Mirrors that exactly.
                            return Map.of("secured-ds", secured);
                        }
                    },
                    auditLog);

            SqlWorkbenchException ex = assertThrows(
                    SqlWorkbenchException.class,
                    () -> securedService.execute("alice", sqlExecRoles, "secured-ds", "SELECT 1", null));
            assertEquals(securityType, SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
            assertTrue(openedFor.isEmpty());
            assertEquals(
                    SqlWorkbenchAuditEntry.OUTCOME_ERROR,
                    auditLog.recent(1, 0, null).get(0).outcome);
        }
    }

    /** saiku#2532 — an unrecognised security.type applies no role at all: refused, fail-closed. */
    @Test
    public void execute_refusesSecurityEnabledDatasourceWithUnrecognisedSecurityType() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        props.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, "something-else");
        SaikuDatasource secured = new SaikuDatasource("secured-ds", SaikuDatasource.Type.OLAP, props);
        SqlWorkbenchService securedService = new SqlWorkbenchService(
                id -> DriverManager.getConnection(JDBC_URL, "sa", ""),
                new StubDatasourceService("secured-ds") {
                    @Override
                    public SaikuDatasource getDatasource(String datasourceName) {
                        return "secured-ds".equals(datasourceName) ? secured : null;
                    }

                    @Override
                    public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                        return Map.of("secured-ds", secured);
                    }
                },
                auditLog);

        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> securedService.execute("alice", sqlExecRoles, "secured-ds", "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
    }

    /**
     * saiku#2532 — a lookup datasource whose {@code security.mapping} grants the caller's own
     * authorities at least one Mondrian role IS served: the decision is reproducible without the
     * OLAP connection. No mapping for the caller is refused, mirroring the visibility filter.
     */
    @Test
    public void execute_servesLookupOnlyWhenTheCallerRolesMapToAMondrianRole() {
        java.util.Properties mapped = new java.util.Properties();
        mapped.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        mapped.setProperty(
                ISaikuConnection.SECURITY_TYPE_KEY, ISaikuConnection.SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE);
        mapped.setProperty(ISaikuConnection.SECURITY_LOOKUP_KEY, "ROLE_SQL_EXEC=Sales;");
        SaikuDatasource lookupDs = new SaikuDatasource("lookup-ds", SaikuDatasource.Type.OLAP, mapped);
        DatasourceService lookupService = new StubDatasourceService("lookup-ds") {
            @Override
            public SaikuDatasource getDatasource(String datasourceName) {
                return "lookup-ds".equals(datasourceName) ? lookupDs : null;
            }

            @Override
            public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                return Map.of("lookup-ds", lookupDs);
            }
        };
        SqlWorkbenchService service2 =
                new SqlWorkbenchService(id -> DriverManager.getConnection(JDBC_URL, "sa", ""), lookupService, auditLog);

        // ROLE_SQL_EXEC maps to Mondrian role "Sales" — the caller is served.
        assertEquals(
                1,
                service2.execute("alice", sqlExecRoles, "lookup-ds", "SELECT 1", null)
                        .rowCount());

        // A caller with no mapped authority is refused before a connection is opened.
        List<String> openedFor = new ArrayList<>();
        SqlWorkbenchService denied = new SqlWorkbenchService(
                id -> {
                    openedFor.add(id);
                    return DriverManager.getConnection(JDBC_URL, "sa", "");
                },
                lookupService,
                auditLog);
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> denied.execute("alice", new String[] {"ROLE_OTHER"}, "lookup-ds", "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
        assertTrue(openedFor.isEmpty());
    }

    /**
     * saiku#2532 — the admin exemption mirrors the connection path: a configured admin keeps
     * access to security-enabled one2one/passthrough datasources on the workbench, while the same
     * roles without the admin list (or with no UserService wired) fail closed to non-admin.
     */
    @Test
    public void execute_adminExemptionMirrorsTheConnectionPath_andFailsClosedWithoutAdminRoles() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        props.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE);
        SaikuDatasource secured = new SaikuDatasource("secured-ds", SaikuDatasource.Type.OLAP, props);
        DatasourceService securedService = new StubDatasourceService("secured-ds") {
            @Override
            public SaikuDatasource getDatasource(String datasourceName) {
                return "secured-ds".equals(datasourceName) ? secured : null;
            }

            @Override
            public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                return Map.of("secured-ds", secured);
            }
        };

        UserService adminUser = new UserService();
        adminUser.setAdminRoles(List.of("ROLE_ADMIN"));
        SqlWorkbenchService admin = new SqlWorkbenchService(
                id -> DriverManager.getConnection(JDBC_URL, "sa", ""), securedService, auditLog);
        admin.setUserService(adminUser);
        assertEquals(
                1,
                admin.execute("root", new String[] {"ROLE_ADMIN"}, "secured-ds", "SELECT 1", null)
                        .rowCount());

        // Same service, a non-admin caller: refused.
        SqlWorkbenchException ex = assertThrows(
                SqlWorkbenchException.class,
                () -> admin.execute("alice", sqlExecRoles, "secured-ds", "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());

        // No UserService wired (headless/test wiring): admin roles unknown — fail closed to
        // non-admin, so the admin role in the authorities alone is NOT enough.
        SqlWorkbenchService noUserService = new SqlWorkbenchService(
                id -> DriverManager.getConnection(JDBC_URL, "sa", ""), securedService, auditLog);
        ex = assertThrows(
                SqlWorkbenchException.class,
                () -> noUserService.execute("root", new String[] {"ROLE_ADMIN"}, "secured-ds", "SELECT 1", null));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
    }

    /**
     * saiku#2532 — the picker applies the same decision as {@code execute}: a security-enabled
     * datasource the workbench cannot serve is not offered to a non-admin, and a lookup datasource
     * is offered only when the caller's roles map to a Mondrian role.
     */
    @Test
    public void listDatasources_omitsSecurityEnabledDatasourcesTheWorkbenchCannotServe() {
        java.util.Properties one2one = new java.util.Properties();
        one2one.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        one2one.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, ISaikuConnection.SECURITY_TYPE_SPRING2MONDRIAN_VALUE);
        SaikuDatasource one2oneDs = new SaikuDatasource("b-one2one", SaikuDatasource.Type.OLAP, one2one);

        java.util.Properties mapped = new java.util.Properties();
        mapped.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        mapped.setProperty(
                ISaikuConnection.SECURITY_TYPE_KEY, ISaikuConnection.SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE);
        mapped.setProperty(ISaikuConnection.SECURITY_LOOKUP_KEY, "ROLE_SQL_EXEC=Sales");
        SaikuDatasource lookupDs = new SaikuDatasource("c-lookup", SaikuDatasource.Type.OLAP, mapped);

        SaikuDatasource plain = new SaikuDatasource("a-plain", SaikuDatasource.Type.OLAP, null);

        DatasourceService mixed = new StubDatasourceService(DATA_SOURCE_ID) {
            @Override
            public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                Map<String, SaikuDatasource> all = new java.util.LinkedHashMap<>();
                all.put(plain.getName(), plain);
                all.put(one2oneDs.getName(), one2oneDs);
                all.put(lookupDs.getName(), lookupDs);
                return all;
            }
        };

        UserService adminUser = new UserService();
        adminUser.setAdminRoles(List.of("ROLE_ADMIN"));

        SqlWorkbenchService service2 =
                new SqlWorkbenchService(id -> DriverManager.getConnection(JDBC_URL, "sa", ""), mixed, auditLog);

        // Non-admin: the one2one datasource is not offered; the mapped lookup and the
        // security-disabled one are.
        List<String> names = service2.listDatasources(sqlExecRoles).stream()
                .map(SqlWorkbenchService.DatasourceView::name)
                .toList();
        assertEquals(List.of("a-plain", "c-lookup"), names);

        // Admin: everything is offered, mirroring the connection path's admin exemption.
        service2.setUserService(adminUser);
        names = service2.listDatasources(new String[] {"ROLE_ADMIN"}).stream()
                .map(SqlWorkbenchService.DatasourceView::name)
                .toList();
        assertEquals(List.of("a-plain", "b-one2one", "c-lookup"), names);
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

    // saiku#2599 — the datasource info / table-list metadata surface.

    @Test
    public void describeDatasource_reportsTheTargetDatabaseAndSchema() {
        SqlWorkbenchService.DatasourceInfo info = service.describeDatasource(sqlExecRoles, DATA_SOURCE_ID);
        assertEquals(DATA_SOURCE_ID, info.name());
        assertEquals("H2", info.databaseProductName());
        assertTrue(info.url().startsWith("jdbc:h2:"));
        // The driver-reported URL is what we show (credential-free here), never the datasource's
        // stored property text — and whatever it is, it went through the redactor.
        assertTrue(info.url().startsWith("jdbc:"));
        assertFalse(info.url().toLowerCase(java.util.Locale.ROOT).contains("password"));
    }

    @Test
    public void describeDatasource_redactsCredentialsFromTheReportedUrl() {
        Connection lying = recordingConnection(
                "jdbc:postgresql://warehouse:SecretPass123@db.internal:5432/foodmart?password=SecretPass123",
                new ArrayList<>(),
                false);
        SqlWorkbenchService redaction =
                new SqlWorkbenchService(id -> lying, new StubDatasourceService(DATA_SOURCE_ID), auditLog);

        SqlWorkbenchService.DatasourceInfo info = redaction.describeDatasource(sqlExecRoles, DATA_SOURCE_ID);
        assertFalse("userinfo password must not survive", info.url().contains("SecretPass123"));
        assertTrue("userinfo is replaced", info.url().contains("://***@"));
        assertTrue("secret URL properties are replaced", info.url().contains("password=***"));
    }

    @Test
    public void listTables_listsTheDefaultSchemaTablesSorted() {
        List<String> tables = service.listTables(sqlExecRoles, DATA_SOURCE_ID);
        assertTrue("the seeded table must be listed, got " + tables, tables.contains("CUSTOMER"));
        List<String> sorted = new ArrayList<>(tables);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        assertEquals("tables come back sorted case-insensitively", sorted, tables);
    }

    @Test
    public void describeDatasource_refusesCallerWhoseRolesCannotSeeTheDatasource() {
        assertRefusedWithoutOpeningConnection(
                id -> DriverManager.getConnection(JDBC_URL, "sa", ""),
                (scoped, roles) -> scoped.describeDatasource(roles, DATA_SOURCE_ID));
    }

    @Test
    public void listTables_refusesCallerWhoseRolesCannotSeeTheDatasource() {
        assertRefusedWithoutOpeningConnection(
                id -> DriverManager.getConnection(JDBC_URL, "sa", ""),
                (scoped, roles) -> scoped.listTables(roles, DATA_SOURCE_ID));
    }

    /** Shared body of the two entitlement refusals: UNKNOWN_DATASOURCE, no connection opened. */
    private void assertRefusedWithoutOpeningConnection(
            SchemaGenOrchestrator.ConnectionProvider provider,
            java.util.function.BiFunction<SqlWorkbenchService, String[], Object> call) {
        List<String> openedFor = new ArrayList<>();
        SqlWorkbenchService scoped = new SqlWorkbenchService(
                id -> {
                    openedFor.add(id);
                    return provider.get(id);
                },
                new StubDatasourceService(DATA_SOURCE_ID) {
                    @Override
                    public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                        // Simulates the role filter excluding everything for this caller.
                        return Map.of();
                    }
                },
                auditLog);

        SqlWorkbenchException ex = assertThrows(SqlWorkbenchException.class, () -> call.apply(scoped, sqlExecRoles));
        assertEquals(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, ex.getCode());
        assertTrue("the datasource was never opened", openedFor.isEmpty());
    }

    @Test
    public void redactUrl_redactsUserinfoAndSecretProperties_andLeavesPlainUrlsAlone() {
        String plain = "jdbc:h2:file:/data/foodmart;IFEXISTS=TRUE";
        assertEquals("a credential-free URL is untouched", plain, SqlWorkbenchService.redactUrl(plain));
        assertEquals(
                "jdbc:postgresql://***@db.internal:5432/foodmart",
                SqlWorkbenchService.redactUrl("jdbc:postgresql://warehouse:hunter2@db.internal:5432/foodmart"));
        assertEquals(
                "jdbc:mysql://db.internal/foodmart;JdbcPassword=***;Catalog=FoodMart",
                SqlWorkbenchService.redactUrl(
                        "jdbc:mysql://db.internal/foodmart;JdbcPassword=hunter2;Catalog=FoodMart"));
        assertEquals(
                "jdbc:postgresql://db.internal/foodmart?password=***&ssl=true",
                SqlWorkbenchService.redactUrl("jdbc:postgresql://db.internal/foodmart?password=hunter2&ssl=true"));
        assertEquals(
                "the Oracle thin credential form is redacted too",
                "jdbc:oracle:thin:***/***@host:1521/orcl",
                SqlWorkbenchService.redactUrl("jdbc:oracle:thin:scott/tiger@host:1521/orcl"));
        assertNull(SqlWorkbenchService.redactUrl(null));
    }

    // --- saiku#2550h: byte caps on materialised cells and the result as a whole ---

    @Test
    public void anOversizedCellIsTruncatedToTheCellCap() {
        SqlWorkbenchService.SqlQueryResult result = service.execute(
                "alice",
                sqlExecRoles,
                DATA_SOURCE_ID,
                "SELECT REPEAT('x', 200000) AS big FROM SYSTEM_RANGE(1, 1)",
                null);
        assertEquals(1, result.rowCount());
        Object cell = result.rows().get(0).get(0);
        String s = String.valueOf(cell);
        assertTrue("cell is capped to the cell limit plus marker", s.length() < 70_000);
        assertTrue("truncated cell is marked", s.endsWith(SqlWorkbenchService.CELL_TRUNCATION_MARKER));
    }

    @Test
    public void resultReadingStopsAtTheResultByteCap() {
        SqlWorkbenchService.SqlQueryResult result = service.execute(
                "alice",
                sqlExecRoles,
                DATA_SOURCE_ID,
                "SELECT REPEAT('x', 70000) AS big FROM SYSTEM_RANGE(1, 200)",
                null);
        assertTrue("reading stops at the byte cap, not the row cap", result.rowCount() < 200);
        assertTrue(result.truncated());
        long bytes = result.rows().stream()
                .flatMap(List::stream)
                .mapToLong(cell -> String.valueOf(cell).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();
        assertTrue("materialised cells stay within the result cap", bytes <= SqlWorkbenchService.MAX_RESULT_BYTES);
    }

    @Test
    public void truncationNeverSplitsASurrogatePair() {
        String input = "x".repeat(SqlWorkbenchService.MAX_CELL_BYTES - 1) + "😀";
        String cut = SqlWorkbenchService.truncateCell(input);
        assertEquals(
                "x".repeat(SqlWorkbenchService.MAX_CELL_BYTES - 1) + SqlWorkbenchService.CELL_TRUNCATION_MARKER, cut);
    }

    @Test
    public void wideRowIsNotMaterialisedPastTheResultCap() {
        String columns = String.join(", ", java.util.Collections.nCopies(165, "REPEAT('x', 70000)"));
        SqlWorkbenchService.SqlQueryResult result =
                service.execute("alice", sqlExecRoles, DATA_SOURCE_ID, "SELECT " + columns, null);
        assertTrue(result.truncated());
        assertEquals(0, result.rowCount());
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
