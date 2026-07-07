/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.saiku.service.schema.ossie.model.Dataset;
import org.saiku.service.schema.ossie.model.DialectExpression;
import org.saiku.service.schema.ossie.model.Metric;
import org.saiku.service.schema.ossie.model.SemanticModel;

/**
 * Calcite {@link Schema} projection of one Ossie {@link SemanticModel}.
 *
 * <p>Each Ossie {@code dataset} becomes one Calcite {@link Table} named after the dataset. When a
 * JDBC connection URL is supplied, the table delegates to Calcite's {@link JdbcSchema} so
 * SELECT/WHERE/GROUP BY get pushed down as warehouse-native SQL. Without a JDBC URL the tables
 * still register (schema introspection works — BI tools can list them) but queries return zero
 * rows because there's no underlying data source.
 *
 * <p>Metrics land as computed views in a follow-up commit on this branch — the first slice keeps
 * the boundary narrow: datasets → tables, with join semantics driven by explicit SQL {@code
 * JOIN}s. Auto-injection of Ossie relationships and metric-expression expansion come next.
 */
public class OssieSchema extends AbstractSchema {

    private final SemanticModel model;
    private final JdbcSchema jdbcSchema;
    /** Name of the hidden sub-schema attached to the root that holds our JdbcSchema — needed to
     *  qualify the SELECT emitted for each Ossie dataset view. Null when no JDBC is wired. */
    private final String jdbcSubSchemaName;

    /** Schema name Calcite hands us at registration time (from the connect model). Used to
     *  qualify identifiers in metric-view SQL so the parser resolves them across schemas.
     *  Falls back to the Ossie model name (usually the same). */
    private volatile String selfSchemaName;

    public OssieSchema(SemanticModel model, JdbcSchema jdbcSchema, String jdbcSubSchemaName) {
        this.model = model;
        this.jdbcSchema = jdbcSchema;
        this.jdbcSubSchemaName = jdbcSubSchemaName;
    }

    /** Called by {@link OssieSchemaFactory} immediately after construction. */
    void bindSchemaName(String name) {
        this.selfSchemaName = name;
    }

    public SemanticModel model() {
        return model;
    }

    @Override
    protected Map<String, Table> getTableMap() {
        // Deterministic linked map so SHOW TABLES / information_schema output is stable across
        // restarts (BI tools cache introspection results by dataset name).
        //
        // Structure of what we register:
        //  1. One table per Ossie dataset — either the underlying JdbcTable when a warehouse is
        //     wired, or an OssieDatasetTable placeholder in schema-only mode.
        //  2. One view per Ossie metric — an OssieMetricViewTable whose SQL is
        //     "SELECT <ANSI_SQL expression> AS <metric_name> FROM <schema>.<home_dataset>".
        //     Users write 'SELECT * FROM <schema>.<metric>' to get the aggregate over the whole
        //     home dataset; downstream slices add per-dimension grouping via relationship-aware
        //     rewrites. Metrics with only an MDX dialect are skipped — those live in Mondrian,
        //     not in this SQL surface.
        //
        // Name collisions between metrics and datasets are broken in favour of the dataset (the
        // Mondrian exporter's conventions keep them distinct — this is a safety net rather than
        // an expected case).
        Map<String, Table> tables = new LinkedHashMap<>();
        for (Dataset dataset : model.getDatasets()) {
            Table table = null;
            if (jdbcSchema != null) {
                String sourceTable = lastDot(dataset.getSource() == null ? dataset.getName() : dataset.getSource());
                table = firstNonNull(
                        jdbcSchema.getTable(sourceTable),
                        jdbcSchema.getTable(sourceTable.toUpperCase()),
                        jdbcSchema.getTable(sourceTable.toLowerCase()));
            }
            if (table == null) {
                // Schema-only fallback — no JDBC, or the underlying table wasn't found. Register
                // a placeholder table synthesised from the Ossie fields so introspection works.
                table = new OssieDatasetTable(dataset, null);
            }
            tables.put(dataset.getName(), table);
        }
        for (Metric metric : model.getMetrics()) {
            if (tables.containsKey(metric.getName())) continue;
            OssieMetricViewTable view = buildMetricView(metric);
            if (view != null) tables.put(metric.getName(), view);
        }
        return ImmutableMap.copyOf(tables);
    }

    /**
     * Build an {@link OssieMetricViewTable} for a metric. Returns null when the metric has no
     * ANSI SQL dialect (MDX-only calculated members — they live in Mondrian, not here) or the
     * model has zero datasets (nowhere to aggregate against).
     */
    private OssieMetricViewTable buildMetricView(Metric metric) {
        String ansiSql = pickAnsiSql(metric);
        if (ansiSql == null) return null;
        String homeDataset = pickHomeDataset(ansiSql);
        if (homeDataset == null) return null;
        String effectiveSchemaName = selfSchemaName != null ? selfSchemaName : model.getName();
        // Quote identifiers so mixed-case names (Pharma Rx, TOTAL_REVENUE) survive Calcite's
        // parser without being lower-cased to unresolvable names.
        String viewSql = "SELECT " + ansiSql + " AS \"" + metric.getName() + "\" " + "FROM \"" + effectiveSchemaName
                + "\".\"" + homeDataset + "\"";
        // Look up the home dataset's underlying table name so the metric view can resolve
        // column types via the JdbcSchema's rowType. Falls back to the dataset name itself.
        String homeSource = null;
        for (Dataset d : model.getDatasets()) {
            if (d.getName().equals(homeDataset)) {
                homeSource = lastDot(d.getSource() == null ? d.getName() : d.getSource());
                break;
            }
        }
        return new OssieMetricViewTable(metric, viewSql, List.of(effectiveSchemaName), jdbcSchema, homeSource);
    }

    /**
     * Return the ANSI SQL dialect expression from a metric, or null if the metric only has an
     * MDX dialect (e.g. calculated members). Ossie's spec lets metrics carry multiple dialects;
     * this adapter only understands ANSI SQL at query time.
     */
    private static String pickAnsiSql(Metric metric) {
        if (metric.getExpression() == null) return null;
        for (DialectExpression d : metric.getExpression().getDialects()) {
            if ("ANSI_SQL".equalsIgnoreCase(d.getDialect())) return d.getExpression();
        }
        return null;
    }

    /**
     * Best-effort resolution of a metric's home dataset from its ANSI SQL text. Scans for a
     * dataset name appearing as {@code <ds>.column}, {@code FROM <ds>}, or the bare dataset
     * name; returns the first match. Falls back to the first dataset in the model when the
     * expression is opaque (e.g. a literal or a scalar function with no column reference).
     * Returns null only if the model has zero datasets — in which case there's nothing to
     * aggregate over and the caller should skip the metric.
     */
    private String pickHomeDataset(String ansiSql) {
        String lower = ansiSql.toLowerCase(Locale.ROOT);
        for (Dataset d : model.getDatasets()) {
            String needle = d.getName().toLowerCase(Locale.ROOT);
            if (lower.contains(needle + ".") || lower.contains("from " + needle) || lower.equals(needle)) {
                return d.getName();
            }
        }
        return model.getDatasets().isEmpty() ? null : model.getDatasets().get(0).getName();
    }

    private static String lastDot(String s) {
        int idx = s.lastIndexOf('.');
        return idx < 0 ? s : s.substring(idx + 1);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    /**
     * Sub-schemas: we don't publish any today. Reserved for a future world where a single Ossie
     * document produces one Calcite sub-schema per semantic model, addressable by connect
     * operand.
     */
    @Override
    protected Map<String, Schema> getSubSchemaMap() {
        return ImmutableMap.of();
    }

    /** Signal to Calcite that this schema is safe to expose via {@code SchemaPlus.add(…)}. */
    @Override
    public boolean isMutable() {
        return false;
    }

    /**
     * Helper for tests that want the built schema without going through the JDBC connect path.
     * Wraps the schema in a Calcite {@link SchemaPlus} rooted under a caller-provided parent.
     */
    public SchemaPlus attachTo(SchemaPlus parent, String name) {
        return parent.add(name, this);
    }
}
