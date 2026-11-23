/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DialectExpression;
import bi.saiku.ossie.model.DimensionMeta;
import bi.saiku.ossie.model.Expression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * dbt {@code manifest.json} → Ossie. The second spoke from saiku#1730's acceptance criteria
 * ("formats with existing upstream converters — dbt at minimum — import through the same
 * flow"): dbt is the one vendor format apache/ossie already ships a converter for, so the UI
 * flow is exercised against a manifest a real dbt project emits.
 *
 * <p>Mapping:
 *
 * <table>
 *   <caption>dbt manifest → Ossie</caption>
 *   <tr><td>{@code nodes[resource_type=model]}</td><td>{@code datasets[]}</td></tr>
 *   <tr><td>{@code sources[]}</td><td>{@code datasets[]} (prefixed with the source name)</td></tr>
 *   <tr><td>{@code columns}</td><td>{@code fields[]} (date/timestamp → time dimension)</td></tr>
 *   <tr><td>a {@code unique} test on a column</td><td>{@code dataset.primary_key}</td></tr>
 *   <tr><td>{@code metrics[]}</td><td>{@code metrics[]}</td></tr>
 *   <tr><td>dbt {@code relationships} test</td><td>reported, not converted (see below)</td></tr>
 * </table>
 *
 * <p>dbt's own templating ({@code {{ ref('orders') }}}) has no warehouse to resolve against
 * offline, so a templated metric expression is dropped with a warning rather than guessed at.
 */
public class DbtManifestConverter implements VendorModelConverter {

    private static final String DIALECT = "ANSI_SQL";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String id() {
        return "dbt";
    }

    @Override
    public String displayName() {
        return "dbt manifest";
    }

    @Override
    public String description() {
        return "A dbt project's target/manifest.json. Models and sources become datasets, columns become "
                + "fields, `unique` tests become primary keys, dbt metrics become metrics.";
    }

    @Override
    public List<String> fileExtensions() {
        return List.of(".json");
    }

    @Override
    public VendorConversionResult convert(List<VendorModelFile> files, String requestName)
            throws VendorModelConversionException {
        VendorModelFile first = files.get(0);
        JsonNode root;
        try {
            root = json.readTree(first.getContent());
        } catch (Exception e) {
            throw new VendorModelConversionException(
                    "Payload is not valid JSON — a dbt import needs the manifest itself (target/manifest.json), not a model .sql file.",
                    e);
        }
        if (root == null || !root.isObject()) {
            throw new VendorModelConversionException("Expected a dbt manifest object at the top level of the payload.");
        }

        SemanticModel model = new SemanticModel();
        List<ConversionDiagnostic> diags = new ArrayList<>();
        Map<String, Dataset> byName = new LinkedHashMap<>();

        if (root.has("nodes")) {
            JsonNode nodes = root.get("nodes");
            for (Map.Entry<String, JsonNode> entry : nodes.properties()) {
                JsonNode node = entry.getValue();
                if (!"model".equals(text(node, "resource_type"))) continue;
                if ("seed".equals(text(node, "config", "materialized"))) continue;
                Dataset ds = datasetFromNode(entry.getKey(), node, "", nodes, diags);
                if (ds != null && byName.put(ds.getName(), ds) == null) {
                    model.getDatasets().add(ds);
                } else if (ds != null) {
                    diags.add(ConversionDiagnostic.error(
                            "DUPLICATE_DATASET",
                            ds.getName(),
                            "Two manifest nodes map to the same dataset name — the later one is ignored."));
                }
            }
        }

        if (root.has("sources")) {
            JsonNode sources = root.get("sources");
            for (JsonNode source : sources) {
                String sourceName = lower(text(source, "name"));
                JsonNode tables = source.get("tables");
                if (tables == null) continue;
                for (JsonNode table : tables) {
                    String datasetName = lower(sourceName + "_" + text(table, "name"));
                    if (byName.containsKey(datasetName)) {
                        diags.add(ConversionDiagnostic.warning(
                                "DUPLICATE_SOURCE",
                                datasetName,
                                "A dbt model already claims this name — the source is not imported."));
                        continue;
                    }
                    JsonNode copy = table.deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("resource_type", "source");
                    Dataset ds = datasetFromNode(
                            sourceName + "." + text(table, "name"), copy, sourceName, root.path("nodes"), diags);
                    if (ds != null) {
                        ds.setName(datasetName);
                        byName.put(datasetName, ds);
                        model.getDatasets().add(ds);
                    }
                }
            }
        }

        if (model.getDatasets().isEmpty()) {
            throw new VendorModelConversionException(
                    "Manifest contains no `nodes` of resource_type `model` and no `sources` — nothing to import.");
        }

        model.getMetrics().addAll(metricsFrom(root, diags));
        reportRelationshipTests(root, diags);

        String modelName = modelName(requestName, root, model, diags);
        model.setName(modelName);
        model.setDescription("Imported from a dbt manifest by the Saiku vendor-model importer (saiku#1730).");

        OssieDocument doc = new OssieDocument();
        doc.getSemanticModel().add(model);
        return new VendorConversionResult(doc, modelName).addAll(diags);
    }

    // ------------------------------------------------------------------

    private Dataset datasetFromNode(
            String uniqueId, JsonNode node, String sourcePrefix, JsonNode nodesRoot, List<ConversionDiagnostic> diags) {
        String alias = lower(node.path("alias").asText(""));
        if (alias.isBlank()) {
            // Older manifests have no `alias`; the name lives in the unique_id.
            alias = lower(lastSegment(uniqueId));
        }
        if (alias.isBlank()) return null;
        String name = sourcePrefix.isBlank() ? alias : lower(sourcePrefix) + "_" + alias;

        Dataset ds = new Dataset();
        ds.setName(name);
        String description = text(node, "description");
        if (description != null && !description.isBlank()) ds.setDescription(description.trim());

        String relation = text(node, "relation_name");
        if (relation != null && !relation.isBlank()) {
            ds.setSource(relation.trim());
        } else {
            String database = text(node, "database");
            String schema = text(node, "schema");
            ds.setSource(alias);
            if (schema == null || schema.isBlank()) {
                diags.add(ConversionDiagnostic.warning(
                        "NO_SCHEMA",
                        name,
                        "Manifest node has no `schema` — the dataset points at table `" + alias
                                + "` in the connection's default schema."));
            } else if (database == null || database.isBlank()) {
                diags.add(ConversionDiagnostic.info(
                        "SCHEMA_ONLY_SOURCE", name, "Dataset source resolved to `" + schema + "." + alias + "`."));
            }
        }

        JsonNode columns = node.get("columns");
        if (columns != null) {
            for (JsonNode column : columns) {
                String columnName = lower(column.path("name").asText(""));
                if (columnName.isBlank()) continue;
                Field field = new Field();
                field.setName(columnName);
                field.setExpression(ansi(columnName));
                if (!column.path("description").asText("").isBlank()) {
                    field.setDescription(column.path("description").asText("").trim());
                }
                String dataType = lower(column.path("data_type").asText(""));
                if (dataType.contains("date") || dataType.contains("time")) {
                    field.setDimension(new DimensionMeta(Boolean.TRUE));
                }
                ds.getFields().add(field);
                if (hasUniqueTest(node, nodesRoot, columnName)) {
                    ds.getPrimaryKey().add(columnName);
                }
            }
        }
        if (ds.getFields().isEmpty()) {
            diags.add(ConversionDiagnostic.warning(
                    "NO_FIELDS",
                    name,
                    "Node has no documented columns — the dataset will not be queryable until fields are added."));
        }
        if (ds.getPrimaryKey().isEmpty()) {
            diags.add(ConversionDiagnostic.info(
                    "NO_PRIMARY_KEY", name, "No `unique` test on any column — no primary key inferred."));
        }
        return ds;
    }

    /**
     * A {@code unique} test on a column is dbt's idiom for a primary key. The test is a sibling
     * node in {@code nodes}, referenced from the model's {@code child_map} by unique id, so the
     * lookup is a map hop rather than a scan of the whole manifest (which would be O(n²) on a
     * project with thousands of nodes).
     */
    private boolean hasUniqueTest(JsonNode node, JsonNode nodesRoot, String columnName) {
        JsonNode childMap = node.path("child_map");
        if (!childMap.isObject()) return false;
        for (JsonNode ids : iterable(childMap.elements())) {
            for (JsonNode id : iterable(ids.elements())) {
                // get(), not path(): a dbt unique_id contains dots and path() would treat them as nesting.
                if (uniqueTestTargetsColumn(nodesRoot.get(id.asText()), columnName)) return true;
            }
        }
        return false;
    }

    private boolean uniqueTestTargetsColumn(JsonNode candidate, String columnName) {
        if (candidate == null || !candidate.isObject()) return false;
        JsonNode meta = candidate.path("test_metadata");
        if (meta.isMissingNode() || meta.isNull()) return false;
        if (!"unique".equals(lower(meta.path("name").asText("")))) return false;
        JsonNode kwargs = meta.path("kwargs");
        if (kwargs.isMissingNode()) return false;
        return columnName.equals(lower(kwargs.path("column_name").asText("")));
    }

    private List<Metric> metricsFrom(JsonNode root, List<ConversionDiagnostic> diags) {
        List<Metric> out = new ArrayList<>();
        JsonNode metrics = root.get("metrics");
        if (metrics == null || !metrics.isObject()) return out;
        for (JsonNode metricNode : iterable(metrics.values())) {
            String name = lower(metricNode.path("name").asText(""));
            if (name.isBlank()) continue;
            if (metricNode.path("disabled").asBoolean(false)) {
                diags.add(ConversionDiagnostic.info(
                        "METRIC_DISABLED", name, "Metric is disabled in the manifest — not imported."));
                continue;
            }
            String sql = metricExpressionSql(metricNode);
            if (sql == null) {
                diags.add(ConversionDiagnostic.warning(
                        "TEMPLATED_METRIC",
                        name,
                        "Metric expression is a dbt template (`{{ … }}`) or is absent — not imported; "
                                + "redeclare it in Ossie or add it to dbt's compiled output."));
                continue;
            }
            Metric metric = new Metric();
            metric.setName(name);
            metric.setExpression(ansi(sql));
            if (!metricNode.path("description").asText("").isBlank()) {
                metric.setDescription(metricNode.path("description").asText("").trim());
            } else if (!metricNode.path("label").asText("").isBlank()) {
                metric.setDescription(metricNode.path("label").asText().trim());
            }
            out.add(metric);
        }
        return out;
    }

    /**
     * Pull the compiled SQL off a dbt metric. Prefers the value dbt already compiled for the
     * default target; falls back to the authored expression when it's plain SQL.
     */
    private String metricExpressionSql(JsonNode metricNode) {
        JsonNode compiled = metricNode.path("compiled_code");
        if (compiled.isTextual() && !compiled.asText().isBlank()) {
            return stripLiquid(compiled.asText());
        }
        JsonNode expr = metricNode.path("expression");
        if (expr.isTextual() && !expr.asText().isBlank()) return stripLiquid(expr.asText());
        if (expr.isObject()) {
            JsonNode sql = expr.path("sql");
            if (sql.isTextual() && !sql.asText().isBlank()) return stripLiquid(sql.asText());
        }
        JsonNode direct = metricNode.path("sql");
        if (direct.isTextual() && !direct.asText().isBlank()) return stripLiquid(direct.asText());
        return null;
    }

    /**
     * dbt expresses joins as {@code relationships} tests, and both sides are
     * {@code ref('model')} / {@code source('src','table')} strings with the {@code to:} side
     * usually a composite expression. There is no warehouse to resolve those against offline,
     * so rather than invent a column we report each one and leave relationships to be declared
     * in Ossie (or added by hand in the YAML) after import.
     */
    private void reportRelationshipTests(JsonNode root, List<ConversionDiagnostic> diags) {
        JsonNode nodes = root.path("nodes");
        if (!nodes.isObject()) return;
        for (JsonNode test : iterable(nodes.elements())) {
            if (!"test".equals(text(test, "resource_type"))) continue;
            JsonNode meta = test.path("test_metadata");
            if (!"relationships".equals(lower(meta.path("name").asText("")))) continue;
            String field = lower(meta.path("kwargs").path("field_name").asText(""));
            diags.add(
                    ConversionDiagnostic.info(
                            "RELATIONSHIP_TEST_SKIPPED",
                            lower(test.path("name")
                                    .asText(test.path("unique_id").asText("test"))),
                            "dbt `relationships` test on " + (field.isBlank() ? "a column" : field)
                                    + " references another model through ref()/source() — declare the relationship in Ossie after import."));
        }
    }

    // ------------------------------------------------------------------

    private static String lastSegment(String uniqueId) {
        int dot = uniqueId.lastIndexOf('.');
        return dot < 0 ? uniqueId : uniqueId.substring(dot + 1);
    }

    private static String stripLiquid(String sql) {
        if (sql.contains("{{")) {
            // A leftover template means the string was never compiled for a target.
            return null;
        }
        return sql.trim().isEmpty() ? null : sql.trim();
    }

    private String modelName(String requestName, JsonNode root, SemanticModel model, List<ConversionDiagnostic> diags) {
        if (requestName != null && !requestName.isBlank()) return requestName.trim();
        String project = text(root, "metadata", "project_name");
        if (project == null || project.isBlank()) {
            project = text(root, "metadata", "project_id");
        }
        if (project != null && !project.isBlank()) {
            diags.add(ConversionDiagnostic.info(
                    "MODEL_NAME_DERIVED", "model", "Model name taken from the manifest's project metadata."));
            return project.trim();
        }
        diags.add(ConversionDiagnostic.info(
                "MODEL_NAME_DERIVED", "model", "Model name taken from the first imported dataset."));
        return model.getDatasets().get(0).getName();
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String text(JsonNode node, String group, String field) {
        JsonNode v = node.path(group).path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Iterable<JsonNode> iterable(Iterator<JsonNode> it) {
        return () -> it;
    }

    private static Expression ansi(String expression) {
        Expression e = new Expression();
        DialectExpression d = new DialectExpression();
        d.setDialect(DIALECT);
        d.setExpression(expression);
        e.getDialects().add(d);
        return e;
    }
}
