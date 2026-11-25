/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceNameDecoration;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend for the SQL workbench (saiku#1107 phase 1): runs a single read-only statement straight
 * against a Saiku datasource's underlying JDBC connection (not through Mondrian/MDX) and returns a
 * capped, paginatable result set. Every run — allowed or rejected — is written to
 * {@link SqlWorkbenchAuditLog}.
 *
 * <p>Reuses the schema-generator's {@link SchemaGenOrchestrator.ConnectionProvider} (wired in
 * {@code saiku-beans.xml} as {@code schemaGenConnectionProvider}, backed by
 * {@code DatasourceJdbcConnectionProvider}) rather than duplicating JDBC-URL resolution — that
 * class already applies {@code JdbcUrlPolicy} (saiku#1902) before a driver is ever touched.
 */
public class SqlWorkbenchService {

    private static final Logger LOG = LoggerFactory.getLogger(SqlWorkbenchService.class);

    private static final int DEFAULT_MAX_ROWS = 500;
    private static final int MAX_MAX_ROWS = 5000;
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    /** saiku#2599 — a schema with more tables than this is unreadable in a picker anyway. */
    private static final int MAX_TABLES = 2000;

    /** saiku#2599 — URL userinfo (scheme://user:password@host) never survives redaction. */
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?i)(\\w+://)[^/\\\s\"']*@", Pattern.UNICODE_CHARACTER_CLASS);

    /** saiku#2599 — secret-bearing URL properties (;password=…, ?JdbcPassword=…) never survive redaction. */
    private static final Pattern URL_SECRET_PROPERTY = Pattern.compile(
            "(?i)([;?&])(jdbc\\s*password|password|pwd|passphrase|keystore\\s*password|truststore\\s*password|ssl\\s*password)\\s*=\\s*[^;\\\s&\"']{0,512}");

    /** saiku#2599 — the Oracle thin form jdbc:oracle:thin:user/password@host never survives redaction. */
    private static final Pattern ORACLE_THIN_CREDENTIALS =
            Pattern.compile("(?i)(thin:)[^/@\\\s\"']+/[^/@\\\s\"']+@", Pattern.UNICODE_CHARACTER_CLASS);

    /** saiku#2550h: byte cap on a single materialised cell. A cell whose stringified
     *  value exceeds this is TRUNCATED (with a marker appended) rather than refused —
     *  one giant TEXT/BLOB cell should not dominate the budget. Counts UTF-8 bytes
     *  of the stringified value. */
    static final int MAX_CELL_BYTES = Integer.getInteger("saiku.sqlworkbench.maxCellBytes", 64 * 1024);

    /** saiku#2550h: byte cap on the materialised result as a whole (all cells,
     *  stringified, UTF-8). The row cap alone is not a heap cap: 5,000 rows of a
     *  {@code REPEAT('x', 1000000)} column were ~5 GB. Reading stops once the cap is
     *  exceeded and the result is reported {@code truncated}. */
    static final long MAX_RESULT_BYTES = Long.getLong("saiku.sqlworkbench.maxResultBytes", 10L * 1024 * 1024);

    /** Appended to a cell value that {@link #MAX_CELL_BYTES} cut off. */
    static final String CELL_TRUNCATION_MARKER = "…[truncated]";

    private final SchemaGenOrchestrator.ConnectionProvider connectionProvider;
    private final DatasourceService datasourceService;
    private final SqlWorkbenchAuditLog auditLog;

    public SqlWorkbenchService(
            SchemaGenOrchestrator.ConnectionProvider connectionProvider,
            DatasourceService datasourceService,
            SqlWorkbenchAuditLog auditLog) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.datasourceService = Objects.requireNonNull(datasourceService, "datasourceService");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    public record DatasourceView(String name, String type) {}

    public record SqlQueryResult(
            List<String> columns, List<List<Object>> rows, int rowCount, boolean truncated, long durationMs) {}

    /** saiku#2599 — what the selected datasource actually connects to, read live from
     *  {@link DatabaseMetaData}. {@code url} is redacted (never a password, never URL userinfo); the
     *  password itself is never present anywhere in this record. */
    public record DatasourceInfo(
            String name,
            String databaseProductName,
            String databaseProductVersion,
            String driverName,
            String driverVersion,
            String catalog,
            String schema,
            String url) {}

    /** Datasources visible to {@code userRoles} — the same ACL {@link DatasourceService} already
     *  applies for cube listing, so the picker never offers a datasource the user couldn't
     *  otherwise reach. Credentials/properties are never included. */
    public List<DatasourceView> listDatasources(String[] userRoles) {
        Map<String, SaikuDatasource> all = datasourceService.getDatasources(userRoles);
        List<DatasourceView> out = new ArrayList<>();
        for (SaikuDatasource ds : all.values()) {
            out.add(new DatasourceView(
                    ds.getName(), ds.getType() == null ? null : ds.getType().name()));
        }
        out.sort(Comparator.comparing(DatasourceView::name));
        return out;
    }

    /**
     * Runs {@code sql} against {@code dataSourceId} and returns up to {@code maxRowsParam} rows
     * (clamped to {@code [1, MAX_MAX_ROWS]}, default {@link #DEFAULT_MAX_ROWS}).
     *
     * <p>saiku#2522: the statement is validated once by {@link ReadOnlySqlGuard#validatedStatement},
     * and the VALIDATED text (comments stripped) is what is executed and audited — the checked
     * statement and the executed statement can never differ. Rejected submissions are audited with
     * the raw text the caller sent.
     *
     * <p>saiku#2318: {@code dataSourceId} comes in as a free-text body field, so the target is
     * re-checked against {@link DatasourceService#getDatasources(String[])} for the caller's roles
     * — the same filtered map the picker offers — rather than trusted. A datasource the caller's
     * roles cannot see is reported as unknown (never a distinguishable "exists but denied").
     *
     * @param userRoles the caller's Spring authorities; the datasource must be visible to them
     * @throws SqlWorkbenchException with a message safe to show a {@code ROLE_SQL_EXEC} user —
     *     never a stack trace or Java class name — when the statement is rejected or execution
     *     fails.
     */
    public SqlQueryResult execute(
            String username, String[] userRoles, String dataSourceId, String sql, Integer maxRowsParam) {
        int maxRows = clampMaxRows(maxRowsParam);
        long started = System.nanoTime();

        // saiku#2522: validatedStatement returns the exact text the guard approved — comments
        // stripped, single statement, no escapes — and that text is what gets executed below, so
        // the checked statement and the executed statement can never differ.
        String validated;
        try {
            validated = ReadOnlySqlGuard.validatedStatement(sql);
        } catch (IllegalArgumentException rejected) {
            audit(
                    username,
                    dataSourceId,
                    sql,
                    SqlWorkbenchAuditEntry.OUTCOME_REJECTED,
                    null,
                    rejected.getMessage(),
                    elapsedMs(started));
            throw new SqlWorkbenchException(SqlWorkbenchException.Code.READ_ONLY_VIOLATION, rejected.getMessage());
        }

        if (!isEntitledTo(userRoles, dataSourceId)) {
            // Same message as an unknown datasource: never confirm that a datasource the caller is
            // not entitled to exists.
            String message = "No such datasource: " + dataSourceId;
            audit(username, dataSourceId, sql, SqlWorkbenchAuditEntry.OUTCOME_ERROR, null, message, elapsedMs(started));
            throw new SqlWorkbenchException(SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, message);
        }

        try (Connection conn = connectionProvider.get(dataSourceId)) {
            try {
                // Best-effort: not every JDBC driver honours setReadOnly. The parse guard above and
                // executeQuery() below (which most drivers already refuse for a non-SELECT) are the
                // primary defense — this is one more layer, not the only one.
                conn.setReadOnly(true);
            } catch (SQLException notSupported) {
                LOG.debug("driver for '{}' does not support Connection.setReadOnly", dataSourceId);
            }
            enforceReadOnlySession(username, dataSourceId, validated, conn, started);
            try (Statement st = conn.createStatement()) {
                st.setMaxRows(maxRows);
                st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                disableEscapeProcessing(dataSourceId, st);
                try (ResultSet rs = st.executeQuery(validated)) {
                    SqlQueryResult result = toResult(rs, maxRows, elapsedMs(started));
                    audit(
                            username,
                            dataSourceId,
                            validated,
                            SqlWorkbenchAuditEntry.OUTCOME_SUCCESS,
                            result.rowCount(),
                            null,
                            result.durationMs());
                    return result;
                }
            }
        } catch (SQLException e) {
            long elapsed = elapsedMs(started);
            String safe = safeMessage(e);
            LOG.warn("SQL workbench query failed for datasource '{}': {}", dataSourceId, e.toString());
            audit(username, dataSourceId, validated, SqlWorkbenchAuditEntry.OUTCOME_ERROR, null, safe, elapsed);
            throw new SqlWorkbenchException(SqlWorkbenchException.Code.QUERY_FAILED, safe);
        }
    }

    /**
     * saiku#2599 — what {@code dataSourceId} connects to, so the workbench can show the picker's
     * effect even when several datasources share one warehouse (where {@code SHOW TABLES} looks
     * identical for all of them). Read live from {@link DatabaseMetaData} on the datasource's own
     * connection — the same connection {@link #execute} would use. Same entitlement check as
     * {@link #execute}: a datasource the caller's roles cannot see is reported as unknown.
     */
    public DatasourceInfo describeDatasource(String[] userRoles, String dataSourceId) {
        requireEntitled(userRoles, dataSourceId);
        try (Connection conn = connectionProvider.get(dataSourceId)) {
            DatabaseMetaData md = conn.getMetaData();
            return new DatasourceInfo(
                    dataSourceId,
                    md.getDatabaseProductName(),
                    md.getDatabaseProductVersion(),
                    md.getDriverName(),
                    md.getDriverVersion(),
                    bestEffort(conn::getCatalog),
                    bestEffort(conn::getSchema),
                    redactUrl(bestEffort(md::getURL)));
        } catch (SQLException e) {
            LOG.warn("SQL workbench info failed for datasource '{}': {}", dataSourceId, e.toString());
            throw new SqlWorkbenchException(SqlWorkbenchException.Code.QUERY_FAILED, redactUrl(safeMessage(e)));
        }
    }

    /**
     * saiku#2599 — the table names of the datasource's default schema ({@link DatabaseMetaData#getTables}
     * on the same connection {@link #execute} would use, base-table types only), sorted
     * case-insensitively and capped at {@link #MAX_TABLES}. Read-only metadata; nothing here
     * executes user SQL. Same entitlement check as {@link #execute}.
     */
    public List<String> listTables(String[] userRoles, String dataSourceId) {
        requireEntitled(userRoles, dataSourceId);
        List<String> out = new ArrayList<>();
        try (Connection conn = connectionProvider.get(dataSourceId)) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = bestEffort(conn::getCatalog);
            String schema = bestEffort(conn::getSchema);
            try (ResultSet rs = md.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
                while (rs.next() && out.size() < MAX_TABLES) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null && !name.isBlank()) {
                        out.add(name);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("SQL workbench table list failed for datasource '{}': {}", dataSourceId, e.toString());
            throw new SqlWorkbenchException(SqlWorkbenchException.Code.QUERY_FAILED, redactUrl(safeMessage(e)));
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * The entitlement half of {@link #execute} — the target is resolved by name or UUID and the
     * resolved record must be in the caller's role-filtered datasource map. A miss is reported as
     * an unknown datasource (never a distinguishable "exists but denied", saiku#2318).
     */
    private void requireEntitled(String[] userRoles, String dataSourceId) {
        if (!isEntitledTo(userRoles, dataSourceId)) {
            throw new SqlWorkbenchException(
                    SqlWorkbenchException.Code.UNKNOWN_DATASOURCE, "No such datasource: " + dataSourceId);
        }
    }

    /** A JDBC metadata read that may throw {@link SQLException} — what {@link #bestEffort} tolerates. */
    private interface SqlStringCall {
        String get() throws SQLException;
    }

    /** A JDBC metadata read that returns {@code null} instead of throwing when the driver does not support the call. */
    private static String bestEffort(SqlStringCall call) {
        try {
            return call.get();
        } catch (SQLException | UnsupportedOperationException notSupported) {
            return null;
        }
    }

    /**
     * saiku#2599 (cf. #2547) — redacts credentials out of any text that may carry a JDBC URL:
     * {@code scheme://user:password@host} userinfo becomes {@code scheme://***@}, and secret-bearing
     * URL properties ({@code ;password=…}, {@code ?JdbcPassword=…}) become {@code ;password=***}.
     * Applied both to the URL we report and to any error message that might quote one.
     */
    static String redactUrl(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = URL_USERINFO.matcher(text).replaceAll("$1***@");
        out = URL_SECRET_PROPERTY.matcher(out).replaceAll("$1$2=***");
        out = ORACLE_THIN_CREDENTIALS.matcher(out).replaceAll("$1***/***@");
        return out;
    }

    /**
     * Whether {@code dataSourceId} names a datasource the caller's roles can see.
     *
     * <p>saiku#2394: the requested id is RESOLVED once — name or UUID, the same resolution the
     * connection provider will apply — and the RESOLVED record is then checked against the
     * role-filtered datasource map. The previous check applied
     * {@link DatasourceNameDecoration#sameConnection} between the requested name and every visible
     * name, which tolerates any single {@code <segment>_} prefix: a caller entitled to
     * {@code sales_payroll} was entitled to the distinct, role-restricted {@code payroll}, and
     * {@code connectionProvider.get} then opened that restricted datasource with its stored
     * credentials. Distinct datasource records are therefore never matched by prefix tolerance —
     * only by equal immutable {@code id} property (the UUID resolution) or exact connection name.
     * Workspace-decoration aliases still work because the resolution step itself accepts both
     * spellings (saiku#1869).
     */
    private boolean isEntitledTo(String[] userRoles, String dataSourceId) {
        SaikuDatasource requested = datasourceService.getDatasourceByIdOrName(dataSourceId);
        if (requested == null) {
            return false;
        }
        Map<String, SaikuDatasource> visible = datasourceService.getDatasources(userRoles);
        if (visible == null) {
            return false;
        }
        for (SaikuDatasource ds : visible.values()) {
            if (isSameDatasource(ds, requested)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same datasource record, not merely a similar-looking name: equal by immutable {@code id}
     * property when both carry one, otherwise by exact connection name. Deliberately strict —
     * {@code payroll} and {@code sales_payroll} are different datasources even when a prefix makes
     * one a suffix of the other.
     */
    private static boolean isSameDatasource(SaikuDatasource a, SaikuDatasource b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        String idA = idOf(a);
        String idB = idOf(b);
        if (idA != null && !idA.isEmpty() && idB != null && !idB.isEmpty()) {
            return idA.equals(idB);
        }
        return a.getName() != null && a.getName().equals(b.getName());
    }

    private static String idOf(SaikuDatasource ds) {
        return ds.getProperties() == null ? null : ds.getProperties().getProperty("id");
    }

    /**
     * saiku#2522 — turns JDBC escape processing OFF so the driver runs the raw text the guard
     * validated, never a driver-rewritten translation of it ({@code {fn FILE_READ(…)}} must not
     * become a callable function behind the guard's back). {@link ReadOnlySqlGuard} already
     * rejects any {@code {} outside quotes, so this is a second, engine-side layer: if it cannot
     * be set, the statement is not executed (fail closed).
     */
    private static void disableEscapeProcessing(String dataSourceId, Statement st) throws SQLException {
        try {
            st.setEscapeProcessing(false);
        } catch (SQLException notSupported) {
            LOG.warn(
                    "SQL workbench could not disable JDBC escape processing on '{}'; statement not executed",
                    dataSourceId);
            throw notSupported;
        }
    }

    /**
     * saiku#2318 — dialect-level session read-only, enforced not best-effort. On backends that have
     * a session-level read-only switch, it is applied to the workbench connection and any failure
     * is fatal: the statement is never executed against a session that could write (fail closed).
     * Backends without such a switch (H2, DuckDB) rely on the parse guard, which is why the guard
     * also blocks their file/network built-ins.
     */
    private void enforceReadOnlySession(
            String username, String dataSourceId, String validated, Connection conn, long started) {
        String statement = sessionReadOnlyStatement(jdbcUrl(conn, dataSourceId));
        if (statement == null) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute(statement);
        } catch (SQLException failed) {
            // The datasource refused a read-only session — do NOT run the user's statement anyway.
            String safe = "The datasource could not be placed in read-only mode, so the statement was not executed";
            LOG.warn(
                    "SQL workbench could not enforce a read-only session on '{}': {}", dataSourceId, failed.toString());
            audit(
                    username,
                    dataSourceId,
                    validated,
                    SqlWorkbenchAuditEntry.OUTCOME_ERROR,
                    null,
                    safe,
                    elapsedMs(started));
            throw new SqlWorkbenchException(SqlWorkbenchException.Code.QUERY_FAILED, safe);
        }
    }

    /** The datasource's JDBC URL; unreadable metadata fails closed (empty string → no allowance). */
    private static String jdbcUrl(Connection conn, String dataSourceId) {
        try {
            return conn.getMetaData().getURL();
        } catch (SQLException e) {
            LOG.debug("could not read JDBC URL for '{}'", dataSourceId);
            return "";
        }
    }

    /**
     * The backend's session-level read-only statement, or {@code null} when the backend has none.
     * Detected from the JDBC URL the driver reports, never from user input.
     */
    private static String sessionReadOnlyStatement(String url) {
        if (url == null) {
            return null;
        }
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(":postgresql:")) {
            // PostgreSQL 9.0+: the session default applies to every subsequent transaction.
            return "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY";
        }
        if (u.contains(":mysql:") || u.contains(":mariadb:")) {
            return "SET SESSION TRANSACTION READ ONLY";
        }
        return null;
    }

    private static SqlQueryResult toResult(ResultSet rs, int maxRows, long durationMs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> columns = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            columns.add(md.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        long resultBytes = 0;
        boolean byteCapped = false;
        rows:
        while (rs.next()) {
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                Object cell = rs.getObject(i);
                // saiku#2550h: the row cap is not a heap cap — 5,000 rows of a
                // REPEAT('x', 1000000) column were ~5 GB. Every cell is measured as
                // its stringified UTF-8 bytes (the wire form the UI gets), a cell over
                // MAX_CELL_BYTES is truncated in place, and reading stops entirely
                // once the whole result crosses MAX_RESULT_BYTES. Binary values are
                // measured via their string form too — a close-enough, documented
                // approximation; the cap exists to bound materialisation, not to audit.
                if (cell != null) {
                    String s = String.valueOf(cell);
                    byte[] utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (utf8.length > MAX_CELL_BYTES) {
                        s = truncateCell(s);
                        utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        cell = s;
                    }
                    // Keep only complete rows. A wide row must not overshoot the result cap.
                    if (resultBytes > MAX_RESULT_BYTES - utf8.length) {
                        byteCapped = true;
                        break rows;
                    }
                    resultBytes += utf8.length;
                }
                row.add(cell);
            }
            rows.add(row);
        }
        // setMaxRows caps the driver at maxRows, so hitting that count exactly means there was
        // very likely more to fetch — a heuristic, not a certainty, but good enough for a "showing
        // the first N rows" banner without a second COUNT(*) round-trip. The byte cap is a
        // certainty: we stopped reading because the materialised result got too big.
        boolean truncated = byteCapped || rows.size() >= maxRows;
        return new SqlQueryResult(columns, rows, rows.size(), truncated, durationMs);
    }

    /** Keep complete Unicode code points within the UTF-8 budget. */
    static String truncateCell(String value) {
        int chars = 0;
        int bytes = 0;
        while (chars < value.length()) {
            int codePoint = value.codePointAt(chars);
            int nextBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + nextBytes > MAX_CELL_BYTES) break;
            bytes += nextBytes;
            chars += Character.charCount(codePoint);
        }
        return value.substring(0, chars) + CELL_TRUNCATION_MARKER;
    }

    private void audit(
            String username,
            String dataSourceId,
            String sql,
            String outcome,
            Integer rowCount,
            String error,
            long latencyMs) {
        SqlWorkbenchAuditEntry entry = new SqlWorkbenchAuditEntry();
        entry.user = username;
        entry.datasource = dataSourceId;
        entry.sql = sql;
        entry.outcome = outcome;
        entry.rowCount = rowCount;
        entry.error = error;
        entry.latencyMs = latencyMs;
        auditLog.record(entry);
    }

    private static int clampMaxRows(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(requested, MAX_MAX_ROWS);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** The JDBC driver's own error text, bounded — never a stack trace or Java class name. */
    private static String safeMessage(SQLException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "The query failed to execute";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…"
                : message;
    }
}
