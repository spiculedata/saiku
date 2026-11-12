/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import java.sql.Connection;
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

        try {
            ReadOnlySqlGuard.checkReadOnly(sql);
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
            enforceReadOnlySession(username, dataSourceId, sql, conn, started);
            try (Statement st = conn.createStatement()) {
                st.setMaxRows(maxRows);
                st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = st.executeQuery(sql)) {
                    SqlQueryResult result = toResult(rs, maxRows, elapsedMs(started));
                    audit(
                            username,
                            dataSourceId,
                            sql,
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
            audit(username, dataSourceId, sql, SqlWorkbenchAuditEntry.OUTCOME_ERROR, null, safe, elapsed);
            throw new SqlWorkbenchException(SqlWorkbenchException.Code.QUERY_FAILED, safe);
        }
    }

    /**
     * Whether {@code dataSourceId} is visible to {@code userRoles} through the role-filtered
     * datasource map — the same ACL the picker lists. Matching tolerates the workspace decoration
     * ({@code unknown_foodmart} vs {@code foodmart}), the same leniency the by-name lookup applies
     * everywhere else.
     */
    private boolean isEntitledTo(String[] userRoles, String dataSourceId) {
        Map<String, SaikuDatasource> visible = datasourceService.getDatasources(userRoles);
        if (visible == null) {
            return false;
        }
        for (Map.Entry<String, SaikuDatasource> entry : visible.entrySet()) {
            if (DatasourceNameDecoration.sameConnection(entry.getKey(), dataSourceId)
                    || DatasourceNameDecoration.sameConnection(entry.getValue().getName(), dataSourceId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * saiku#2318 — dialect-level session read-only, enforced not best-effort. On backends that have
     * a session-level read-only switch, it is applied to the workbench connection and any failure
     * is fatal: the statement is never executed against a session that could write (fail closed).
     * Backends without such a switch (H2, DuckDB) rely on the parse guard, which is why the guard
     * also blocks their file/network built-ins.
     */
    private void enforceReadOnlySession(
            String username, String dataSourceId, String sql, Connection conn, long started) {
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
            audit(username, dataSourceId, sql, SqlWorkbenchAuditEntry.OUTCOME_ERROR, null, safe, elapsedMs(started));
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
        while (rs.next()) {
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        // setMaxRows caps the driver at maxRows, so hitting that count exactly means there was
        // very likely more to fetch — a heuristic, not a certainty, but good enough for a "showing
        // the first N rows" banner without a second COUNT(*) round-trip.
        boolean truncated = rows.size() >= maxRows;
        return new SqlQueryResult(columns, rows, rows.size(), truncated, durationMs);
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
