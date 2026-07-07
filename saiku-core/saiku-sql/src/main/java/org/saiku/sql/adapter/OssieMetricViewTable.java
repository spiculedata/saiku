/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.saiku.service.schema.ossie.model.Metric;

/**
 * Calcite {@link TranslatableTable} that expands an Ossie {@link Metric} into a scalar SELECT
 * against its home dataset.
 *
 * <p>Users query it like:
 *
 * <pre>{@code
 * SELECT * FROM SALES.TOTAL_REVENUE;
 * }</pre>
 *
 * <p>which returns a single row with the aggregate value. The class exists because {@code
 * ViewTable.viewMacro(SchemaPlus, …)} — Calcite's usual view-registration path — requires a
 * {@code SchemaPlus} at construction time, which we can't obtain from inside {@link
 * OssieSchemaFactory#create} (the factory returns a {@link org.apache.calcite.schema.Schema}
 * BEFORE Calcite has wrapped it in a {@code SchemaPlus}). By using {@link
 * org.apache.calcite.plan.RelOptTable.ToRelContext#expandView} from within {@link #toRel}, we
 * shift SQL parsing to query time when Calcite has full schema context.
 *
 * <p>Return type inference is limited to the common aggregate functions produced by our
 * Mondrian→Ossie exporter ({@code SUM}, {@code COUNT}, {@code AVG}, {@code MIN}, {@code MAX},
 * {@code COUNT(DISTINCT …)}). Anything else falls back to {@link SqlTypeName#ANY} so Calcite
 * resolves the type at expand time — safer than guessing wrong. A future slice can improve this
 * by parsing the expression once at construction time and caching the resolved type.
 */
public class OssieMetricViewTable extends AbstractTable implements TranslatableTable {

    /** Parses out {@code AGG(TABLE.COLUMN)} or {@code AGG(DISTINCT TABLE.COLUMN)} — the shape our
     *  Mondrian→Ossie exporter emits. Falls through to {@link SqlTypeName#ANY} for anything
     *  more exotic; per-dialect return-type inference is a follow-up. */
    private static final Pattern AGG_PATTERN = Pattern.compile(
            "^\\s*(SUM|COUNT|MIN|MAX|AVG)\\s*\\(\\s*(DISTINCT\\s+)?(?:([\\w\"]+)\\.)?([\\w\"]+)\\s*\\)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final String metricName;
    private final String viewSql;
    private final List<String> schemaPath;
    private final Metric metric;
    private final JdbcSchema jdbcSchema;
    private final String homeDatasetSourceTable;

    public OssieMetricViewTable(
            Metric metric,
            String viewSql,
            List<String> schemaPath,
            JdbcSchema jdbcSchema,
            String homeDatasetSourceTable) {
        this.metricName = metric.getName();
        this.viewSql = viewSql;
        this.schemaPath = schemaPath;
        this.metric = metric;
        this.jdbcSchema = jdbcSchema;
        this.homeDatasetSourceTable = homeDatasetSourceTable;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        // Calcite's checkConvertedType compares getRowType (declared here) against the type of
        // the RelNode expandView produces from viewSql. They MUST match exactly, else the
        // planner throws "Conversion to relational algebra failed to preserve datatypes". So
        // rather than guess a coarse SqlTypeName (DOUBLE/BIGINT/etc), we look up the underlying
        // column's exact type from the JDBC-backed home dataset, then apply Calcite's default
        // aggregate return-type derivation via RelDataTypeSystem. For the aggregators our
        // Mondrian exporter emits (SUM/COUNT/MIN/MAX/AVG/COUNT-DISTINCT) that's an exact match;
        // anything more exotic falls back to ANY (Calcite's wildcard) which is permissive
        // enough to keep the planner happy.
        RelDataType returnType = deriveReturnType(typeFactory);
        return typeFactory.builder().add(metricName, returnType).build();
    }

    /**
     * Derive the metric's return type by combining Calcite's default aggregate rules with the
     * column type looked up on the home dataset's underlying JdbcTable. Returns ANY when we
     * can't parse the expression or don't have a JDBC schema (schema-only mode) — safe, and the
     * planner handles ANY without a type-conversion error.
     */
    private RelDataType deriveReturnType(RelDataTypeFactory typeFactory) {
        String ansi = metric.getExpression() == null
                ? ""
                : metric.getExpression().getDialects().stream()
                        .filter(d -> "ANSI_SQL".equalsIgnoreCase(d.getDialect()))
                        .map(d -> d.getExpression())
                        .findFirst()
                        .orElse("");
        Matcher m = AGG_PATTERN.matcher(ansi);
        if (!m.matches()) return typeFactory.createSqlType(SqlTypeName.ANY);
        String aggregator = m.group(1).toUpperCase(Locale.ROOT);
        String columnName = stripQuotes(m.group(4));
        RelDataType columnType = lookupColumnType(typeFactory, columnName);
        if (columnType == null) return typeFactory.createSqlType(SqlTypeName.ANY);
        RelDataTypeSystem system = RelDataTypeSystem.DEFAULT;
        RelDataType derived;
        boolean nullable;
        switch (aggregator) {
            case "SUM":
                derived = system.deriveSumType(typeFactory, columnType);
                nullable = true; // SUM over empty set → NULL
                break;
            case "COUNT":
                // COUNT never returns NULL — even over an empty set it's 0.
                derived = typeFactory.createSqlType(SqlTypeName.BIGINT);
                nullable = false;
                break;
            case "AVG":
                derived = system.deriveAvgAggType(typeFactory, columnType);
                nullable = true;
                break;
            case "MIN":
            case "MAX":
                // MIN/MAX preserve the input type. NULL over empty set.
                derived = columnType;
                nullable = true;
                break;
            default:
                return typeFactory.createSqlType(SqlTypeName.ANY);
        }
        return typeFactory.createTypeWithNullability(derived, nullable);
    }

    /**
     * Resolve a column name against the JDBC-backed home dataset's rowType. Returns null when
     * no JDBC schema is wired, the home dataset isn't found in it, or the column name doesn't
     * appear on the resolved table.
     */
    private RelDataType lookupColumnType(RelDataTypeFactory typeFactory, String columnName) {
        if (jdbcSchema == null || homeDatasetSourceTable == null) return null;
        Table underlying = firstNonNull(
                jdbcSchema.getTable(homeDatasetSourceTable),
                jdbcSchema.getTable(homeDatasetSourceTable.toUpperCase(Locale.ROOT)),
                jdbcSchema.getTable(homeDatasetSourceTable.toLowerCase(Locale.ROOT)));
        if (underlying == null) return null;
        RelDataType rowType = underlying.getRowType(typeFactory);
        for (RelDataTypeField f : rowType.getFieldList()) {
            if (f.getName().equalsIgnoreCase(columnName)) return f.getType();
        }
        return null;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        return s.replace("\"", "");
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
        // ToRelContext.expandView parses the viewSql and resolves identifiers against the
        // supplied schemaPath — everything Calcite needs is available on this hook. The rowType
        // returned by relOptTable comes from getRowType above, which Calcite already validated
        // against the parsed SELECT list.
        return context.expandView(relOptTable.getRowType(), viewSql, schemaPath, List.of(metricName)).rel;
    }

    @Override
    public String toString() {
        return "OssieMetricViewTable{name=" + metricName + ", sql=" + viewSql + "}";
    }
}
