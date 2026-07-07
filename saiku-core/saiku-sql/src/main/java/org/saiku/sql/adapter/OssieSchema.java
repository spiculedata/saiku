/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.saiku.service.schema.ossie.model.Dataset;
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

    public OssieSchema(SemanticModel model, JdbcSchema jdbcSchema, String jdbcSubSchemaName) {
        this.model = model;
        this.jdbcSchema = jdbcSchema;
        this.jdbcSubSchemaName = jdbcSubSchemaName;
    }

    public SemanticModel model() {
        return model;
    }

    @Override
    protected Map<String, Table> getTableMap() {
        // Deterministic linked map so SHOW TABLES / information_schema output is stable across
        // restarts (BI tools cache introspection results by dataset name).
        //
        // First cut: expose the underlying JdbcTable directly under the Ossie dataset name. The
        // JdbcTable carries a reference back to its JdbcSchema (set during JdbcSchema.create in
        // OssieSchemaFactory with a real parentSchema), so Calcite's planner can walk up
        // getParentSchema() when it needs to build the JDBC push-down plan. ViewTables + metric
        // expansion layer on top in a follow-up commit — this shape gets basic SELECT/JOIN
        // pushdown working against the H2 fixture in SelectPushdownIT.
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
        return ImmutableMap.copyOf(tables);
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
