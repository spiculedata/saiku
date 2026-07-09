/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.Field;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Calcite {@link Table} projection of a single Ossie {@link Dataset}.
 *
 * <p>When a JDBC-backed {@link JdbcSchema} is available, this class delegates rowType + query
 * planning to the underlying JDBC table so Calcite pushes SELECT/WHERE/GROUP BY down to the
 * warehouse as native SQL. Without JDBC (schema-only mode), it synthesises a rowType from the
 * Ossie {@code fields} array with every column typed as VARCHAR and returns zero rows on scan —
 * enough for BI tools to introspect the schema.
 *
 * <p>Ossie's dataset {@code source} is parsed as {@code schema.table} (or bare {@code table}).
 * When the JDBC warehouse uses a different schema layout, the Ossie exporter needs to be
 * corrected upstream; this table doesn't try to invent aliases.
 */
public class OssieDatasetTable extends AbstractTable implements ScannableTable, TranslatableTable {

    private final Dataset dataset;
    private final JdbcSchema jdbcSchema;

    /** Cached delegate resolved once against the JDBC schema; null in schema-only mode. */
    private Table jdbcDelegate;

    public OssieDatasetTable(Dataset dataset, JdbcSchema jdbcSchema) {
        this.dataset = dataset;
        this.jdbcSchema = jdbcSchema;
    }

    public Dataset dataset() {
        return dataset;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        Table delegate = resolveDelegate();
        if (delegate != null) {
            return delegate.getRowType(typeFactory);
        }
        // Schema-only mode. Build a rowType from Ossie fields; every column defaults to VARCHAR
        // because Ossie fields don't yet carry a type hint (would be nice to model in a v2 spec
        // pass — the exporter has the info at hand from Mondrian's <Level type=...>).
        RelDataTypeFactory.Builder b = typeFactory.builder();
        for (Field f : dataset.getFields()) {
            b.add(f.getName(), typeFactory.createSqlType(SqlTypeName.VARCHAR)).nullable(true);
        }
        return b.build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        Table delegate = resolveDelegate();
        if (delegate instanceof ScannableTable scannable) {
            return scannable.scan(root);
        }
        // No JDBC, no delegate → empty result set. Keeps schema introspection queries happy.
        return Linq4j.emptyEnumerable();
    }

    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
        Table delegate = resolveDelegate();
        if (delegate instanceof TranslatableTable translatable) {
            return translatable.toRel(context, relOptTable);
        }
        // Fallback: schema-only mode. Use Calcite's LogicalTableScan so the planner has a valid
        // relational node to work with even if it can't push down to a warehouse.
        return org.apache.calcite.rel.logical.LogicalTableScan.create(context.getCluster(), relOptTable, List.of());
    }

    /**
     * Look up the JDBC-backed physical table for this dataset. Split-name form: everything after
     * the last "." is the table; anything before it is the schema qualifier and is currently
     * IGNORED (JdbcSchema only sees the tables in its default catalog/schema — a future pass will
     * resolve fully-qualified names). Cache once resolved.
     */
    private Table resolveDelegate() {
        if (jdbcSchema == null) return null;
        if (jdbcDelegate != null) return jdbcDelegate;
        String source = dataset.getSource();
        String tableName = source == null ? dataset.getName() : lastDot(source);
        jdbcDelegate = jdbcSchema.getTable(tableName);
        if (jdbcDelegate == null) {
            // Try case-insensitive fallback — some warehouses lowercase, some uppercase.
            String upper = tableName.toUpperCase();
            String lower = tableName.toLowerCase();
            for (String candidate : new String[] {upper, lower}) {
                jdbcDelegate = jdbcSchema.getTable(candidate);
                if (jdbcDelegate != null) break;
            }
        }
        return jdbcDelegate;
    }

    private static String lastDot(String s) {
        int idx = s.lastIndexOf('.');
        return idx < 0 ? s : s.substring(idx + 1);
    }

    @Override
    public Schema.TableType getJdbcTableType() {
        Table delegate = resolveDelegate();
        return delegate == null ? Schema.TableType.TABLE : delegate.getJdbcTableType();
    }

    /** For debugging and error messages. */
    @Override
    public String toString() {
        List<String> columns = new ArrayList<>();
        for (Field f : dataset.getFields()) columns.add(f.getName());
        return "OssieDatasetTable{name=" + dataset.getName() + ", source=" + dataset.getSource() + ", columns="
                + columns + "}";
    }
}
