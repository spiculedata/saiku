/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import java.util.List;
import java.util.Locale;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Calcite {@link TranslatableTable} that materialises an Ossie {@code relationship} as a
 * pre-joined view. Users query it like:
 *
 * <pre>{@code
 * SELECT REGION, SUM(AMOUNT) FROM SALES.ORDERS_JOIN_CUSTOMERS GROUP BY REGION;
 * }</pre>
 *
 * <p>The point: the JOIN predicate lives in the Ossie YAML, not the query. Users who don't want to
 * remember which columns link ORDERS to CUSTOMERS can SELECT from the pre-joined view and get
 * both tables' columns as a single flat rowtype. Calcite pushes the whole thing down to the
 * warehouse as a single JOIN query, so there's no runtime overhead compared to hand-rolling
 * {@code JOIN ... ON ...}.
 *
 * <p>Naming convention: {@code <from>_JOIN_<to>} where {@code <from>} and {@code <to>} come from
 * the Ossie {@code relationship}'s {@code from} and {@code to} fields (usually fact then
 * dimension for Mondrian-exported schemas).
 *
 * <p>Follows the same expandView-at-toRel pattern as {@link OssieMetricViewTable} so we don't
 * need a {@link org.apache.calcite.schema.SchemaPlus} at construction time.
 */
public class OssieRelationshipViewTable extends AbstractTable implements TranslatableTable {

    private final String viewName;
    private final String viewSql;
    private final List<String> schemaPath;
    private final JdbcSchema jdbcSchema;
    private final String fromSourceTable;
    private final String toSourceTable;

    public OssieRelationshipViewTable(
            String viewName,
            String viewSql,
            List<String> schemaPath,
            JdbcSchema jdbcSchema,
            String fromSourceTable,
            String toSourceTable) {
        this.viewName = viewName;
        this.viewSql = viewSql;
        this.schemaPath = schemaPath;
        this.jdbcSchema = jdbcSchema;
        this.fromSourceTable = fromSourceTable;
        this.toSourceTable = toSourceTable;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        // Row type = union of both underlying dataset rowtypes. When names collide (both sides
        // have `id`, for instance), Calcite's builder appends numeric suffixes (id, id0). That's
        // acceptable — the point of this view is for users to reach into either side, not for
        // stable column naming. Users who want cleaner projection can wrap it in their own
        // SELECT.
        RelDataTypeFactory.Builder b = typeFactory.builder();
        if (jdbcSchema != null) {
            addColumnsFromSourceTable(typeFactory, b, fromSourceTable);
            addColumnsFromSourceTable(typeFactory, b, toSourceTable);
        }
        if (b.getFieldCount() == 0) {
            // Neither side resolvable — schema-only mode or the JDBC lookup missed. Return a
            // one-column ANY row so the table registers without blowing up the schema.
            b.add(viewName, typeFactory.createSqlType(SqlTypeName.ANY));
        }
        return b.build();
    }

    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
        // Defer SQL parsing to query time — expandView has full schema context. Mirrors the
        // pattern in OssieMetricViewTable; see that class for the why (SchemaPlus is not
        // available inside SchemaFactory.create).
        return context.expandView(relOptTable.getRowType(), viewSql, schemaPath, List.of(viewName)).rel;
    }

    private void addColumnsFromSourceTable(
            RelDataTypeFactory typeFactory, RelDataTypeFactory.Builder builder, String sourceTable) {
        if (sourceTable == null) return;
        Table underlying = firstNonNull(
                jdbcSchema.getTable(sourceTable),
                jdbcSchema.getTable(sourceTable.toUpperCase(Locale.ROOT)),
                jdbcSchema.getTable(sourceTable.toLowerCase(Locale.ROOT)));
        if (underlying == null) return;
        RelDataType rowType = underlying.getRowType(typeFactory);
        for (RelDataTypeField f : rowType.getFieldList()) {
            builder.add(f.getName(), f.getType());
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    @Override
    public String toString() {
        return "OssieRelationshipViewTable{name=" + viewName + ", sql=" + viewSql + "}";
    }
}
