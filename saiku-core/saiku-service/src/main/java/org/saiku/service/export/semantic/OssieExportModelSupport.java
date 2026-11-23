/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.saiku.service.ossie.OssieModelDto;

/**
 * Shared heuristics the BI-tool exporters ({@link TableauTdsExporter}, {@link
 * SupersetDatasetYamlExporter}) both need to bridge a gap in {@link OssieModelDto}: a {@code
 * Metric} doesn't carry an explicit owning-dataset reference — only a SQL {@code expression} like
 * {@code SUM("fact_pharma"."NETREVENUE")}. Both exporters need to know which dataset/table a
 * metric "belongs to" (Tableau's calculated field lives in one datasource's column list; a
 * Superset dataset is a single table and its metrics are scoped to it), so this class recovers
 * that link by scanning the expression for a {@code "dataset"."field"} / {@code dataset.field}
 * reference that matches a real dataset in the model.
 *
 * <p>This is a best-effort heuristic, not a schema fact — an expression referencing two datasets
 * (e.g. a ratio across a join) attributes the metric to whichever qualified reference appears
 * first. Operators authoring cross-dataset metrics for BI-tool export should keep the primary
 * driving dataset first in the expression, or edit the emitted file by hand; this mirrors the
 * "fact dataset" heuristic {@code AiOssieResource#listModels} already uses for a similar
 * best-guess purpose.
 */
final class OssieExportModelSupport {

    private static final Pattern QUALIFIED_REF =
            Pattern.compile("\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*\\.\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?");

    private OssieExportModelSupport() {}

    /** Fact-dataset heuristic shared with {@code AiOssieResource#listModels}: first dataset whose name starts with "fact", else the first dataset declared. */
    static OssieModelDto.Dataset factDataset(OssieModelDto model) {
        for (OssieModelDto.Dataset ds : model.getDatasets()) {
            if (ds.getName() != null
                    && ds.getName().toLowerCase(java.util.Locale.ROOT).startsWith("fact")) {
                return ds;
            }
        }
        return model.getDatasets().isEmpty() ? null : model.getDatasets().get(0);
    }

    /**
     * Best-guess dataset a metric's expression is scoped to: the first {@code dataset.field}
     * reference in the expression that names a real dataset in the model, else the fact dataset.
     */
    static OssieModelDto.Dataset resolveMetricDataset(OssieModelDto model, OssieModelDto.Metric metric) {
        String expr = metric.getExpression();
        if (expr != null) {
            Matcher m = QUALIFIED_REF.matcher(expr);
            while (m.find()) {
                String candidate = m.group(1);
                for (OssieModelDto.Dataset ds : model.getDatasets()) {
                    if (ds.getName() != null && ds.getName().equalsIgnoreCase(candidate)) {
                        return ds;
                    }
                }
            }
        }
        return factDataset(model);
    }

    /** Find a field on a dataset by name, case-insensitively. Null if absent. */
    static OssieModelDto.Field findField(OssieModelDto.Dataset ds, String fieldName) {
        if (ds == null || fieldName == null) return null;
        for (OssieModelDto.Field f : ds.getFields()) {
            if (fieldName.equalsIgnoreCase(f.getName())) return f;
        }
        return null;
    }

    /** A short, filesystem/identifier-safe slug derived from a model or dataset name. */
    static String slug(String name) {
        if (name == null || name.isBlank()) return "model";
        String s = name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        return s.isBlank() ? "model" : s;
    }
}
