/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DialectExpression;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
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

/**
 * Calcite {@link Schema} projection of one Ossie {@link SemanticModel}.
 *
 * <p>Three kinds of tables land here:
 *
 * <ul>
 *   <li><b>Datasets</b> — one Calcite {@link Table} per Ossie dataset. When JDBC is wired, this
 *       delegates directly to the underlying {@link org.apache.calcite.adapter.jdbc.JdbcSchema}
 *       so SELECT / WHERE / GROUP BY / JOIN push down to the warehouse as native SQL. Without
 *       JDBC (schema-only mode), a placeholder {@link OssieDatasetTable} keeps introspection
 *       working but scans return zero rows.
 *   <li><b>Metrics</b> — one {@link OssieMetricViewTable} per Ossie metric that carries an
 *       ANSI_SQL dialect. Users write {@code SELECT * FROM SALES.TOTAL_REVENUE} to get the
 *       scalar aggregate over its home dataset. MDX-only metrics (calculated members) skip;
 *       they live in Mondrian, not on the SQL surface.
 *   <li><b>Join views</b> — one {@link OssieRelationshipViewTable} per Ossie {@code
 *       relationship}, named {@code <from>_JOIN_<to>}. Materialises the JOIN predicate from
 *       the YAML so users can query pre-joined data with a single {@code SELECT}.
 * </ul>
 *
 * <p>Auto-injected joins via a proper Calcite planner rule (so users can write {@code SELECT c.x,
 * SUM(o.y) FROM ORDERS o, CUSTOMERS c} without any JOIN clause and have the predicate injected
 * from Ossie relationships) is the next follow-up on the parent epic.
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

    /**
     * Registry of live OssieSchema instances keyed by the name Calcite registered them under.
     * Populated by {@link OssieSchemaFactory#create} via {@link #register}. Consulted by
     * {@link OssieAutoJoinRule} when it needs to identify whether a TableScan is Ossie-backed —
     * {@code RelOptTable.unwrap(OssieSchema.class)} doesn't work because our datasets surface as
     * {@link JdbcSchema}-owned tables, so the direct unwrap path finds JdbcSchema not OssieSchema.
     * A name-based registry is the pragmatic fallback.
     *
     * <p>Static state is unfortunate but acceptable: Calcite's own JdbcSchema uses similar
     * process-wide caches. Multiple factories creating a schema with the same name → last-wins
     * (config error the user needs to fix upstream).
     */
    private static final java.util.concurrent.ConcurrentMap<String, OssieSchema> REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public OssieSchema(SemanticModel model, JdbcSchema jdbcSchema, String jdbcSubSchemaName) {
        this.model = model;
        this.jdbcSchema = jdbcSchema;
        this.jdbcSubSchemaName = jdbcSubSchemaName;
    }

    /** Called by {@link OssieSchemaFactory} immediately after construction. Also registers this
     *  schema in the process-wide registry so {@link OssieAutoJoinRule} can look it up by name. */
    void bindSchemaName(String name) {
        this.selfSchemaName = name;
        REGISTRY.put(name, this);
    }

    /** Look up a registered OssieSchema by the name it was registered under. Used by
     *  {@link OssieAutoJoinRule} to identify Ossie-backed TableScans without unwrapping through
     *  {@link JdbcSchema}. Returns null when no OssieSchema is registered under {@code name}. */
    static OssieSchema lookupRegistered(String name) {
        return REGISTRY.get(name);
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
        // Register one pre-joined view per Ossie relationship. Named `<from>_JOIN_<to>`.
        // Users get a flat rowtype of both underlying tables' columns and Calcite pushes the
        // JOIN down to the warehouse — no runtime overhead over writing JOIN ... ON ... by hand.
        // Skips relationships whose from/to don't resolve to registered datasets (defensive,
        // should never happen for a well-formed Ossie doc).
        for (Relationship rel : model.getRelationships()) {
            String viewName = joinViewName(rel);
            if (tables.containsKey(viewName)) continue;
            OssieRelationshipViewTable view = buildJoinView(rel);
            if (view != null) tables.put(viewName, view);
        }
        return ImmutableMap.copyOf(tables);
    }

    /** Public for the schema-only mode too — kept short so it never collides with a dataset. */
    static String joinViewName(Relationship rel) {
        return rel.getFrom() + "_JOIN_" + rel.getTo();
    }

    /**
     * Build a pre-joined view for an Ossie relationship. Returns null when either side doesn't
     * resolve to a registered dataset or the relationship has zero join columns.
     */
    private OssieRelationshipViewTable buildJoinView(Relationship rel) {
        if (rel.getFrom() == null || rel.getTo() == null) return null;
        if (rel.getFromColumns().isEmpty() || rel.getToColumns().isEmpty()) return null;
        if (rel.getFromColumns().size() != rel.getToColumns().size()) return null;
        Dataset fromDs = findDataset(rel.getFrom());
        Dataset toDs = findDataset(rel.getTo());
        if (fromDs == null || toDs == null) return null;
        String effectiveSchemaName = selfSchemaName != null ? selfSchemaName : model.getName();
        // Build "a.<col> = b.<col> AND ..." predicate.
        StringBuilder predicate = new StringBuilder();
        for (int i = 0; i < rel.getFromColumns().size(); i++) {
            if (i > 0) predicate.append(" AND ");
            predicate.append("a.\"").append(rel.getFromColumns().get(i)).append("\" = ");
            predicate.append("b.\"").append(rel.getToColumns().get(i)).append("\"");
        }
        String viewSql = "SELECT * FROM \"" + effectiveSchemaName + "\".\"" + fromDs.getName() + "\" a "
                + "JOIN \"" + effectiveSchemaName + "\".\"" + toDs.getName() + "\" b "
                + "ON " + predicate;
        String fromSource = lastDot(fromDs.getSource() == null ? fromDs.getName() : fromDs.getSource());
        String toSource = lastDot(toDs.getSource() == null ? toDs.getName() : toDs.getSource());
        return new OssieRelationshipViewTable(
                joinViewName(rel), viewSql, List.of(effectiveSchemaName), jdbcSchema, fromSource, toSource);
    }

    private Dataset findDataset(String name) {
        for (Dataset d : model.getDatasets()) {
            if (d.getName().equals(name)) return d;
        }
        return null;
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
