/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.Expression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural validation of a converted Ossie model — the "validation report" half of the import
 * flow (saiku#1730).
 *
 * <p>Runs on the object graph the converter built, before anything is written to disk, and asks
 * the questions that decide whether the model will actually work in the workbench:
 *
 * <ul>
 *   <li>does every relationship point at a dataset that exists, on columns that exist
 *       (ERROR — the auto-join rule can't plan a join to a missing table)
 *   <li>does every metric's expression reference datasets / fields that exist (ERROR)
 *   <li>is any name duplicated (ERROR — the SQL aliases collide)
 *   <li>did anything degrade on the way in (WARNING — carried through from the converter, plus
 *       the ones only visible once the whole model is assembled: datasets with no source, fields
 *       with no expression, leftover liquid)
 * </ul>
 *
 * <p>Errors block the save step; warnings don't. That's the issue's scope guard — LookML
 * esoterica degrade to warnings, not hard failures.
 */
public class OssieYamlValidator {

    /** {@code "orders"."amount"} — the qualified reference form Ossie metrics use. */
    private static final Pattern QUALIFIED_REF = Pattern.compile("\"([^\"]+)\"\\s*\\.\\s*\"([^\"]+)\"");

    private static final Pattern LIQUID = Pattern.compile("\\{\\{.*?\\}\\}", Pattern.DOTALL);

    /**
     * Validate a converted document.
     *
     * @return the report — never null; a report with {@link OssieValidationReport#hasErrors()}
     *     false is what the save step accepts
     */
    public OssieValidationReport validate(OssieDocument doc) {
        List<ConversionDiagnostic> diags = new ArrayList<>();
        if (doc == null || doc.getEffectiveSemanticModels().isEmpty()) {
            diags.add(ConversionDiagnostic.error(
                    "NO_SEMANTIC_MODEL",
                    "model",
                    "Converted document contains no semantic model — nothing to import."));
            return new OssieValidationReport(diags, 0, 0, 0, 0, List.of());
        }
        if (doc.getEffectiveSemanticModels().size() > 1) {
            diags.add(ConversionDiagnostic.warning(
                    "MULTIPLE_SEMANTIC_MODELS",
                    "model",
                    "Converted document holds "
                            + doc.getEffectiveSemanticModels().size()
                            + " semantic models — only the first is registered by the importer."));
        }
        SemanticModel model = doc.getEffectiveSemanticModels().get(0);
        return validate(model);
    }

    /** Validate one semantic model. */
    public OssieValidationReport validate(SemanticModel model) {
        List<ConversionDiagnostic> diags = new ArrayList<>();
        List<Dataset> datasets = model.getDatasets() == null ? List.of() : model.getDatasets();
        List<Metric> metrics = model.getMetrics() == null ? List.of() : model.getMetrics();
        List<Relationship> relationships = model.getRelationships() == null ? List.of() : model.getRelationships();

        if (datasets.isEmpty()) {
            diags.add(ConversionDiagnostic.error(
                    "NO_DATASETS", "model", "Model has no datasets — there is nothing to query."));
        }

        // --- datasets + fields ---
        Map<String, Dataset> byName = new LinkedHashMap<>();
        Map<String, Set<String>> fieldsByDataset = new LinkedHashMap<>();
        Set<String> fieldNames = new LinkedHashSet<>();

        for (Dataset ds : datasets) {
            String name = ds.getName() == null ? "" : ds.getName();
            if (name.isBlank()) {
                diags.add(ConversionDiagnostic.error(
                        "DATASET_UNNAMED", "dataset", "A dataset has no name — it cannot be queried."));
                continue;
            }
            if (byName.putIfAbsent(name, ds) != null) {
                diags.add(ConversionDiagnostic.error(
                        "DUPLICATE_DATASET",
                        name,
                        "Dataset name is declared more than once — the model will emit duplicate table aliases."));
                continue;
            }
            String element = name;
            if (ds.getSource() == null || ds.getSource().isBlank()) {
                diags.add(
                        ConversionDiagnostic.warning(
                                "DATASET_NO_SOURCE",
                                element,
                                "Dataset has no `source` — Saiku's Ossie query path resolves dataset sources as tables in the connection's default schema."));
            }
            if (contains(ds.getSource(), "{{")) {
                diags.add(ConversionDiagnostic.warning(
                        "LIQUID_RESIDUE",
                        element,
                        "Dataset source still contains liquid templating — set it to a plain table name."));
            }

            Set<String> fieldSet = new LinkedHashSet<>();
            List<Field> fields = ds.getFields() == null ? List.of() : ds.getFields();
            for (Field f : fields) {
                String fname = f.getName() == null ? "" : f.getName();
                if (fname.isBlank()) {
                    diags.add(ConversionDiagnostic.warning("FIELD_UNNAMED", element, "A field has no name — skipped."));
                    continue;
                }
                if (!fieldSet.add(fname)) {
                    diags.add(ConversionDiagnostic.error(
                            "DUPLICATE_FIELD",
                            element + "." + fname,
                            "Field declared more than once on the same dataset."));
                }
                if (!fieldNames.add(element + "." + fname)) {
                    diags.add(
                            ConversionDiagnostic.error(
                                    "DUPLICATE_FIELD",
                                    element + "." + fname,
                                    "Field name is declared on more than one dataset — field references are not ambiguous-safe."));
                }
                if (isBlankExpression(f.getExpression())) {
                    diags.add(ConversionDiagnostic.warning(
                            "FIELD_NO_EXPRESSION",
                            element + "." + fname,
                            "Field has no SQL expression — Saiku will select a column of the same name."));
                } else if (contains(primaryExpression(f.getExpression()), "{{")) {
                    diags.add(ConversionDiagnostic.warning(
                            "LIQUID_RESIDUE",
                            element + "." + fname,
                            "Field expression still contains liquid templating — review it after import."));
                }
            }
            fieldsByDataset.put(name, fieldSet);
            if (fieldSet.isEmpty()) {
                diags.add(ConversionDiagnostic.warning(
                        "NO_FIELDS", element, "Dataset has no fields — it can be joined but not queried on its own."));
            }
            if (ds.getPrimaryKey() == null || ds.getPrimaryKey().isEmpty()) {
                diags.add(ConversionDiagnostic.info("NO_PRIMARY_KEY", element, "Dataset declares no primary key."));
            } else {
                for (String pk : ds.getPrimaryKey()) {
                    if (pk != null && !fieldSet.contains(pk)) {
                        diags.add(ConversionDiagnostic.warning(
                                "UNKNOWN_PRIMARY_KEY",
                                element,
                                "Primary key `" + pk + "` is not one of the dataset's fields."));
                    }
                }
            }
        }

        // --- metrics ---
        Set<String> metricNames = new LinkedHashSet<>();
        for (Metric m : metrics) {
            String name = m.getName() == null ? "" : m.getName();
            String element = "metric." + name;
            if (name.isBlank()) {
                diags.add(ConversionDiagnostic.error("METRIC_UNNAMED", "metric", "A metric has no name — skipped."));
                continue;
            }
            if (!metricNames.add(name)) {
                diags.add(
                        ConversionDiagnostic.error(
                                "DUPLICATE_METRIC",
                                element,
                                "Metric name is declared more than once — the result set would carry two identically aliased columns."));
            }
            if (isBlankExpression(m.getExpression())) {
                diags.add(ConversionDiagnostic.warning(
                        "METRIC_NO_EXPRESSION",
                        element,
                        "Metric has no expression — edit it in the Ossie YAML before querying."));
                continue;
            }
            String expr = primaryExpression(m.getExpression());
            if (contains(expr, "{{")) {
                diags.add(ConversionDiagnostic.warning(
                        "LIQUID_RESIDUE",
                        element,
                        "Metric expression still contains liquid templating — review it after import."));
            }
            validateMetricReferences(expr, element, byName, fieldsByDataset, diags);
        }

        // --- relationships ---
        Set<String> relNames = new LinkedHashSet<>();
        for (Relationship r : relationships) {
            String name = r.getName() == null ? "" : r.getName();
            String element = "relationship." + (name.isBlank() ? "?" : name);
            String from = r.getFrom() == null ? "" : r.getFrom();
            String to = r.getTo() == null ? "" : r.getTo();
            if (name.isBlank()) {
                diags.add(ConversionDiagnostic.warning(
                        "RELATIONSHIP_UNNAMED", element, "Relationship has no name — one will be generated on save."));
            } else if (!relNames.add(name)) {
                diags.add(ConversionDiagnostic.error(
                        "DUPLICATE_RELATIONSHIP", element, "Relationship name is declared more than once."));
            }
            boolean fromKnown = !from.isBlank() && byName.containsKey(from);
            boolean toKnown = !to.isBlank() && byName.containsKey(to);
            if (!fromKnown) {
                diags.add(ConversionDiagnostic.error(
                        "RELATIONSHIP_UNKNOWN_DATASET",
                        element,
                        "`from: " + (from.isBlank() ? "<blank>" : from)
                                + "` is not one of the model's datasets — the join can't be planned."));
            }
            if (!toKnown) {
                diags.add(ConversionDiagnostic.error(
                        "RELATIONSHIP_UNKNOWN_DATASET",
                        element,
                        "`to: " + (to.isBlank() ? "<blank>" : to)
                                + "` is not one of the model's datasets — the join can't be planned."));
            }
            List<String> fromCols = r.getFromColumns() == null ? List.of() : r.getFromColumns();
            List<String> toCols = r.getToColumns() == null ? List.of() : r.getToColumns();
            if (fromCols.isEmpty() || toCols.isEmpty()) {
                diags.add(ConversionDiagnostic.error(
                        "RELATIONSHIP_NO_COLUMNS",
                        element,
                        "Relationship has no join columns — Ossie relationships are column-to-column."));
            } else if (fromCols.size() != toCols.size()) {
                diags.add(ConversionDiagnostic.error(
                        "RELATIONSHIP_COLUMN_ARITY",
                        element,
                        "Relationship has " + fromCols.size() + " `from_columns` and " + toCols.size()
                                + " `to_columns` — a composite join needs the same count on both sides."));
            } else {
                if (fromKnown) checkColumn(from, fromCols, fieldsByDataset, element, "from", diags);
                if (toKnown) checkColumn(to, toCols, fieldsByDataset, element, "to", diags);
            }
        }

        int fieldCount = fieldsByDataset.values().stream().mapToInt(Set::size).sum();
        return new OssieValidationReport(
                diags, byName.size(), fieldCount, metricNames.size(), relationships.size(), datasetSummaries(byName));
    }

    // ------------------------------------------------------------------

    private void checkColumn(
            String dataset,
            List<String> columns,
            Map<String, Set<String>> fieldsByDataset,
            String element,
            String side,
            List<ConversionDiagnostic> diags) {
        Set<String> fields = fieldsByDataset.getOrDefault(dataset, Set.of());
        if (fields.isEmpty()) return; // already reported as NO_FIELDS
        for (String c : columns) {
            if (c == null || c.isBlank()) {
                diags.add(ConversionDiagnostic.error(
                        "RELATIONSHIP_COLUMN_ARITY", element, "A `" + side + "_columns` entry is blank."));
            } else if (!fields.contains(c)) {
                diags.add(ConversionDiagnostic.warning(
                        "RELATIONSHIP_UNKNOWN_COLUMN",
                        element,
                        "`" + side + "_columns: " + c + "` is not a field of dataset `" + dataset
                                + "` — the join will fail at query time unless the field is added."));
            }
        }
    }

    private void validateMetricReferences(
            String expr,
            String element,
            Map<String, Dataset> byName,
            Map<String, Set<String>> fieldsByDataset,
            List<ConversionDiagnostic> diags) {
        Matcher m = QUALIFIED_REF.matcher(expr);
        while (m.find()) {
            String dataset = m.group(1);
            String field = m.group(2);
            if (!byName.containsKey(dataset)) {
                diags.add(ConversionDiagnostic.error(
                        "METRIC_UNKNOWN_DATASET",
                        element,
                        "Expression references dataset \"" + dataset + "\", which isn't in the model."));
                continue;
            }
            Set<String> fields = fieldsByDataset.getOrDefault(dataset, Set.of());
            if (!fields.isEmpty() && !fields.contains(field.toLowerCase(Locale.ROOT))) {
                diags.add(ConversionDiagnostic.warning(
                        "METRIC_UNKNOWN_FIELD",
                        element,
                        "Expression references \"" + dataset + "\".\"" + field
                                + "\", which isn't a field of that dataset."));
            }
        }
    }

    private static List<OssieValidationReport.DatasetSummary> datasetSummaries(Map<String, Dataset> byName) {
        List<OssieValidationReport.DatasetSummary> out = new ArrayList<>();
        for (Map.Entry<String, Dataset> e : byName.entrySet()) {
            Dataset ds = e.getValue();
            out.add(new OssieValidationReport.DatasetSummary(
                    e.getKey(),
                    ds.getSource(),
                    ds.getFields() == null ? 0 : ds.getFields().size(),
                    ds.getPrimaryKey() == null ? 0 : ds.getPrimaryKey().size()));
        }
        return out;
    }

    private static boolean isBlankExpression(Expression e) {
        if (e == null || e.getDialects() == null || e.getDialects().isEmpty()) return true;
        return primaryExpression(e) == null;
    }

    /** First dialect expression, preferring ANSI_SQL — the dialect Saiku's SQL path emits. */
    private static String primaryExpression(Expression e) {
        if (e == null || e.getDialects() == null) return null;
        for (var d : e.getDialects()) {
            if ("ANSI_SQL".equalsIgnoreCase(d.getDialect())
                    && d.getExpression() != null
                    && !d.getExpression().isBlank()) {
                return d.getExpression();
            }
        }
        for (var d : e.getDialects()) {
            if (d.getExpression() != null && !d.getExpression().isBlank()) return d.getExpression();
        }
        return null;
    }

    private static boolean contains(String s, String needle) {
        return s != null && s.contains(needle);
    }
}
