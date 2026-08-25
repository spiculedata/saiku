/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.introspect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Builds an A1 {@link DbModel} from a live JDBC {@link Connection} using
 * {@link DatabaseMetaData}. Produces the JDBC-neutral shape the downstream
 * schema-generation pipeline (classifier, dim/measure/time builders,
 * inferrer) consumes; never leaks JDBC types upward.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Enumerates tables via {@code getTables} filtered by {@link Options#catalog}
 *       / {@link Options#schemaPattern} / {@link Options#includeTableTypes}.</li>
 *   <li>Columns via {@code getColumns}; JDBC type resolved through
 *       {@link JDBCType#valueOf(int)} with a safe fallback to {@link JDBCType#OTHER}.</li>
 *   <li>Primary-key flags via {@code getPrimaryKeys}.</li>
 *   <li>Foreign-key edges via {@code getImportedKeys} (child-side view: each FK's
 *       {@code fromColumn} lives on <em>this</em> table, pointing at the parent).</li>
 *   <li>Row-count estimates: dialect-dispatched optimiser stats where cheap
 *       (H2's {@code INFORMATION_SCHEMA.TABLES.ROW_COUNT_ESTIMATE}), otherwise
 *       {@code SELECT COUNT(*)} guarded by {@link Options#tableSizeThreshold}.</li>
 * </ul>
 */
public class JdbcIntrospector {

    /** Introspection options. All values have sane defaults; mutators return {@code this}. */
    public static class Options {
        private String catalog;
        private String schemaPattern;
        private long tableSizeThreshold = 1_000_000L;
        private List<String> includeTableTypes = Collections.singletonList("TABLE");

        public Options withCatalog(String catalog) {
            this.catalog = catalog;
            return this;
        }

        public Options withSchemaPattern(String schemaPattern) {
            this.schemaPattern = schemaPattern;
            return this;
        }

        public Options withTableSizeThreshold(long threshold) {
            this.tableSizeThreshold = threshold;
            return this;
        }

        public Options withIncludeTableTypes(List<String> types) {
            this.includeTableTypes =
                    (types == null || types.isEmpty()) ? Collections.singletonList("TABLE") : new ArrayList<>(types);
            return this;
        }

        public String catalog() {
            return catalog;
        }

        public String schemaPattern() {
            return schemaPattern;
        }

        public long tableSizeThreshold() {
            return tableSizeThreshold;
        }

        public List<String> includeTableTypes() {
            return Collections.unmodifiableList(includeTableTypes);
        }
    }

    private final Options opts;

    public JdbcIntrospector(Options opts) {
        this.opts = (opts == null) ? new Options() : opts;
    }

    /**
     * Introspect the given connection and return an immutable {@link DbModel}.
     * The connection is neither closed nor altered.
     */
    public DbModel introspect(Connection conn) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        String dialect = md.getDatabaseProductName();

        String catalog = opts.catalog;
        String schemaPattern = opts.schemaPattern;
        String[] types = opts.includeTableTypes.toArray(new String[0]);

        List<TableRef> tableRefs = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog, schemaPattern, "%", types)) {
            while (rs.next()) {
                tableRefs.add(new TableRef(
                        rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME")));
            }
        }

        List<DbTable> tables = new ArrayList<>(tableRefs.size());
        for (TableRef ref : tableRefs) {
            // Primary-key column names first so we can flag them while reading columns.
            java.util.Set<String> pkCols = readPrimaryKeys(md, ref);
            List<DbColumn> columns = readColumns(md, ref, pkCols);
            List<DbForeignKey> fks = readForeignKeys(md, ref);
            Long rowCount = estimateRowCount(conn, md, dialect, ref);
            tables.add(new DbTable(ref.schema, ref.name, columns, fks, rowCount));
        }
        return new DbModel(tables);
    }

    // ------------------------------------------------------------------
    // column / key readers
    // ------------------------------------------------------------------

    private List<DbColumn> readColumns(DatabaseMetaData md, TableRef ref, java.util.Set<String> pkCols)
            throws SQLException {
        // Preserve ordinal order: use a LinkedHashMap keyed by ORDINAL_POSITION-derived insertion.
        Map<Integer, DbColumn> ordered = new LinkedHashMap<>();
        try (ResultSet rs = md.getColumns(ref.catalog, ref.schema, ref.name, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                int dataType = rs.getInt("DATA_TYPE");
                JDBCType type;
                try {
                    type = JDBCType.valueOf(dataType);
                } catch (IllegalArgumentException ex) {
                    type = JDBCType.OTHER;
                }
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                int ordinal = rs.getInt("ORDINAL_POSITION");
                boolean pk = pkCols.contains(name);
                ordered.put(ordinal, new DbColumn(name, type, nullable, pk));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private java.util.Set<String> readPrimaryKeys(DatabaseMetaData md, TableRef ref) throws SQLException {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(ref.catalog, ref.schema, ref.name)) {
            while (rs.next()) {
                out.add(rs.getString("COLUMN_NAME"));
            }
        }
        return out;
    }

    private List<DbForeignKey> readForeignKeys(DatabaseMetaData md, TableRef ref) throws SQLException {
        List<DbForeignKey> fks = new ArrayList<>();
        try (ResultSet rs = md.getImportedKeys(ref.catalog, ref.schema, ref.name)) {
            while (rs.next()) {
                String fromColumn = rs.getString("FKCOLUMN_NAME");
                String toTable = rs.getString("PKTABLE_NAME");
                String toColumn = rs.getString("PKCOLUMN_NAME");
                fks.add(new DbForeignKey(fromColumn, toTable, toColumn));
            }
        }
        return fks;
    }

    // ------------------------------------------------------------------
    // row-count estimation (dialect-dispatched)
    // ------------------------------------------------------------------

    private Long estimateRowCount(Connection conn, DatabaseMetaData md, String dialect, TableRef ref)
            throws SQLException {
        String normalized = dialect == null ? "" : dialect.toLowerCase(Locale.ROOT);

        Long stats = null;
        try {
            if (normalized.contains("h2")) {
                stats = h2RowCountEstimate(conn, ref);
            } else if (normalized.contains("postgres")) {
                stats = postgresRowCountEstimate(conn, ref);
            }
        } catch (SQLException ex) {
            // Stats query isn't available / view missing; fall through to COUNT(*).
            stats = null;
        }

        if (stats != null && stats > 0L) {
            return stats;
        }

        // Fallback: COUNT(*), but only if we have no upper-bound signal telling us it's huge.
        // Since stats were unavailable/zero, we conservatively honour tableSizeThreshold by
        // skipping the count when threshold is 0 (never count) — otherwise count, since any
        // available size signal was null.
        if (opts.tableSizeThreshold <= 0L) {
            return null;
        }
        return selectCountStar(conn, ref);
    }

    private Long h2RowCountEstimate(Connection conn, TableRef ref) throws SQLException {
        String sql = "SELECT ROW_COUNT_ESTIMATE FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ref.schema);
            ps.setString(2, ref.name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    if (rs.wasNull()) {
                        return null;
                    }
                    return v;
                }
                return null;
            }
        }
    }

    private Long postgresRowCountEstimate(Connection conn, TableRef ref) throws SQLException {
        String sql = "SELECT c.reltuples::bigint"
                + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ref.schema);
            ps.setString(2, ref.name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    if (rs.wasNull()) {
                        return null;
                    }
                    return v;
                }
                return null;
            }
        }
    }

    private Long selectCountStar(Connection conn, TableRef ref) throws SQLException {
        // Qualify with schema when we have one; this keeps the query safe when the
        // connection's current schema differs from the table's.
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ");
        if (ref.schema != null && !ref.schema.isEmpty()) {
            sb.append(quoteIdent(ref.schema)).append('.');
        }
        sb.append(quoteIdent(ref.name));
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sb.toString())) {
            if (rs.next()) {
                long v = rs.getLong(1);
                if (rs.wasNull()) {
                    return null;
                }
                return v;
            }
            return null;
        }
    }

    /** SQL-standard double-quote identifier quoting; escape embedded quotes. */
    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------
    // internal
    // ------------------------------------------------------------------

    private static final class TableRef {
        final String catalog;
        final String schema;
        final String name;

        TableRef(String catalog, String schema, String name) {
            this.catalog = catalog;
            this.schema = schema;
            this.name = name;
        }
    }
}
