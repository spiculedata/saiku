/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.saiku.service.schema.ossie.OssieYamlReader;
import org.saiku.service.schema.ossie.model.OssieDocument;

/**
 * Calcite entry point for the Ossie semantic layer.
 *
 * <p>Instantiated by Calcite when a JDBC connect URL references this class through a JSON model
 * file — see the Calcite adapter docs. Typical connect model:
 *
 * <pre>{@code
 * {
 *   "version": "1.0",
 *   "defaultSchema": "SALES",
 *   "schemas": [
 *     {
 *       "name": "SALES",
 *       "type": "custom",
 *       "factory": "org.saiku.sql.adapter.OssieSchemaFactory",
 *       "operand": {
 *         "ossieYaml": "/path/to/schema.ossie.yaml",
 *         "modelName": "Sales",
 *         "jdbcUrl": "jdbc:postgresql://localhost:5432/warehouse",
 *         "jdbcUser": "app",
 *         "jdbcPassword": "..."
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Operand keys:
 *
 * <ul>
 *   <li>{@code ossieYaml} (required) — path to the Ossie YAML file.
 *   <li>{@code modelName} (optional) — which {@code semantic_model[]} entry to expose when the
 *       document carries multiple; defaults to the first one.
 *   <li>{@code jdbcUrl} / {@code jdbcUser} / {@code jdbcPassword} (optional but strongly
 *       encouraged) — where the actual data lives. Without them, the Ossie datasets surface as
 *       queryable virtual tables with no rows — useful only for schema-introspection tests.
 * </ul>
 */
public class OssieSchemaFactory implements SchemaFactory {

    static {
        // Register OssieAutoJoinRule GLOBALLY with every Calcite planner. Fires each time
        // Calcite instantiates a query planner (typically once per JDBC statement); our rule
        // becomes part of the standard rule set from then on. The rule's onMatch method has
        // enough guards (Ossie-schema check + Cartesian-condition check + relationship lookup)
        // that it's a no-op for any query touching non-Ossie tables — safe to register
        // globally.
        //
        // Timing: this static block runs when Calcite first loads OssieSchemaFactory (via
        // Class.forName reflection driven by the connect model's "factory" operand), which
        // happens BEFORE the planner for the first query is instantiated. So the hook is
        // already installed by the time the first query needs it.
        org.apache.calcite.runtime.Hook.PLANNER.add((java.util.function.Consumer<Object>) planner -> {
            if (planner instanceof org.apache.calcite.plan.RelOptPlanner) {
                ((org.apache.calcite.plan.RelOptPlanner) planner).addRule(OssieAutoJoinRule.INSTANCE);
            }
        });
    }

    public static final String OP_OSSIE_YAML = "ossieYaml";
    public static final String OP_MODEL_NAME = "modelName";
    public static final String OP_JDBC_URL = "jdbcUrl";
    public static final String OP_JDBC_USER = "jdbcUser";
    public static final String OP_JDBC_PASSWORD = "jdbcPassword";

    @Override
    public Schema create(SchemaPlus parentSchema, String name, Map<String, Object> operand) {
        String ossieYaml = (String) Objects.requireNonNull(
                operand.get(OP_OSSIE_YAML),
                "OssieSchemaFactory: '" + OP_OSSIE_YAML + "' operand is required (path to Ossie YAML)");
        String modelName = (String) operand.get(OP_MODEL_NAME);
        String jdbcUrl = (String) operand.get(OP_JDBC_URL);
        String jdbcUser = (String) operand.get(OP_JDBC_USER);
        String jdbcPassword = (String) operand.get(OP_JDBC_PASSWORD);

        OssieDocument doc;
        try {
            doc = new OssieYamlReader().read(Path.of(ossieYaml));
        } catch (IOException e) {
            throw new RuntimeException(
                    "OssieSchemaFactory: failed to read Ossie YAML at " + ossieYaml + ": " + e.getMessage(), e);
        }

        var models = doc.getEffectiveSemanticModels();
        if (models.isEmpty()) {
            throw new IllegalStateException("OssieSchemaFactory: Ossie document at " + ossieYaml
                    + " has zero semantic models — check the exporter didn't skip every cube");
        }
        var chosen = models.get(0);
        if (modelName != null) {
            chosen = models.stream()
                    .filter(m -> modelName.equals(m.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("OssieSchemaFactory: no semantic model named '"
                            + modelName + "' in " + ossieYaml + "; available: "
                            + models.stream().map(m -> m.getName()).toList()));
        }
        // Attach the JDBC warehouse as a hidden sub-schema of parentSchema. Calcite's JdbcSchema
        // constructor requires a SchemaPlus with a real parent (it walks up via
        // getParentSchema() during query planning); passing null causes NPE the moment the
        // planner touches the schema, hence the sub-schema attach trick used by other adapters.
        JdbcSchema jdbc = null;
        String hiddenName = null;
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            DataSource ds = JdbcSchema.dataSource(jdbcUrl, null, jdbcUser, jdbcPassword);
            hiddenName = "__" + name + "_jdbc";
            // Register the JdbcSchema directly under the root using SchemaPlus.add so Calcite's
            // planner can find it by name when resolving ViewTable SQL that references it. Note
            // the JdbcSchema is registered with parent=null via SchemaPlus.add — Calcite fills
            // in the parent reference when it installs the sub-schema.
            jdbc = JdbcSchema.create(parentSchema, hiddenName, ds, null, null);
            parentSchema.add(hiddenName, jdbc);
        }
        OssieSchema schema = new OssieSchema(chosen, jdbc, hiddenName);
        // Metric-view SQL qualifies dataset references as "<name>"."<dataset>" so Calcite's
        // parser resolves them across schemas. The factory's own name arg is the authoritative
        // source — Calcite hasn't installed the sub-schema yet at this point, so we can't read
        // it from parentSchema.
        schema.bindSchemaName(name);
        return schema;
    }
}
