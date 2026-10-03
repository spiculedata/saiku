/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Loads a parsed {@link CsvTable} into a JDBC connection: one {@code CREATE TABLE} plus a batched
 * {@code INSERT}. Every identifier is double-quoted ({@link #quoteIdentifier}) so arbitrary CSV
 * header text — spaces, punctuation, a SQL reserved word — is always safe as a column name, and
 * so a crafted header can never break out of the identifier position (the quoting IS the
 * SQL-injection defence here, not {@link QuickstartNames}'s sanitising).
 */
final class CsvTableLoader {

    private static final int BATCH_SIZE = 500;

    private CsvTableLoader() {}

    /** Create {@code tableName} on {@code connection} and load every row of {@code table} into it. */
    static int load(CsvTable table, String tableName, Connection connection) throws SQLException {
        createTable(table, tableName, connection);
        return insertRows(table, tableName, connection);
    }

    private static void createTable(CsvTable table, String tableName, Connection connection) throws SQLException {
        StringBuilder ddl = new StringBuilder("CREATE TABLE ")
                .append(quoteIdentifier(tableName))
                .append(" (\"ID\" BIGINT AUTO_INCREMENT PRIMARY KEY");
        for (CsvTable.Column column : table.columns()) {
            ddl.append(", ").append(quoteIdentifier(column.name())).append(' ').append(sqlType(column.type()));
        }
        ddl.append(')');
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl.toString());
        }
    }

    private static int insertRows(CsvTable table, String tableName, Connection connection) throws SQLException {
        List<CsvTable.Column> columns = table.columns();
        StringBuilder insert = new StringBuilder("INSERT INTO ").append(quoteIdentifier(tableName)).append(" (");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                insert.append(", ");
                placeholders.append(", ");
            }
            insert.append(quoteIdentifier(columns.get(i).name()));
            placeholders.append('?');
        }
        insert.append(") VALUES (").append(placeholders).append(')');

        int rowCount = 0;
        try (PreparedStatement ps = connection.prepareStatement(insert.toString())) {
            for (List<String> row : table.rows()) {
                for (int i = 0; i < columns.size(); i++) {
                    bind(ps, i + 1, columns.get(i).type(), row.get(i));
                }
                ps.addBatch();
                rowCount++;
                if (rowCount % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return rowCount;
    }

    private static void bind(PreparedStatement ps, int index, CsvTable.ColumnType type, String raw)
            throws SQLException {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            ps.setNull(index, sqlTypeCode(type));
            return;
        }
        switch (type) {
            case LONG:
                ps.setLong(index, Long.parseLong(v));
                break;
            case DOUBLE:
                ps.setDouble(index, Double.parseDouble(v));
                break;
            case DATE:
                ps.setDate(index, java.sql.Date.valueOf(LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE)));
                break;
            case STRING:
            default:
                // Preserve the untrimmed value for STRING columns — trimming is only for parsing.
                ps.setString(index, raw);
                break;
        }
    }

    private static String sqlType(CsvTable.ColumnType type) {
        switch (type) {
            case LONG:
                return "BIGINT";
            case DOUBLE:
                return "DOUBLE";
            case DATE:
                return "DATE";
            case STRING:
            default:
                return "VARCHAR";
        }
    }

    private static int sqlTypeCode(CsvTable.ColumnType type) {
        switch (type) {
            case LONG:
                return Types.BIGINT;
            case DOUBLE:
                return Types.DOUBLE;
            case DATE:
                return Types.DATE;
            case STRING:
            default:
                return Types.VARCHAR;
        }
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
