/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validate an {@link OssieAiQueryRequest} against a projected {@link OssieAiSchema}. Throws
 * {@link OssieAiValidationException} on the first failure — matches how the MDX-side
 * {@code AiSchemaConverter} works so a single 400 comes back with a candidate list instead
 * of a wall of errors.
 *
 * <p>Checks the request body performs:
 *
 * <ol>
 *   <li>Every {@code rows[i]} + {@code columns[i]} + {@code filters[i]} + {@code sorts[i]}
 *       {@code dataset.field} exists in the schema.
 *   <li>Every {@code values[i].metric} exists in the schema's metrics map.
 *   <li>Every {@code values[i].aggregation} override is a member of the metric's
 *       {@code supportedOverrides} — closes the {@code SUM(*)} gap fuzz uncovered.
 *   <li>Every {@code sorts[i]} names either a metric OR a dataset+field (mutually exclusive).
 *   <li>Every {@code filters[i].op} is a supported operator; {@code BETWEEN}/{@code IN}
 *       require populated {@code values[]}.
 * </ol>
 *
 * <p>Value-content checks (e.g. does the filter value exist in the field's distinct set?) are
 * deliberately not R1's job: the sample-list is capped so the check would be lossy, and the
 * agent's own value-search endpoint is the right place to answer that question.
 */
public final class OssieAiValidator {

    /**
     * Supported filter operators. Kept in sync with the shelf translator's own set — a filter
     * op the translator rejects should never reach it in the first place.
     */
    private static final Set<String> FILTER_OPS =
            Set.of("EQ", "NEQ", "LT", "LTE", "GT", "GTE", "IN", "BETWEEN", "IS_NULL", "IS_NOT_NULL");

    /**
     * Validate the request against the schema. Throws on the first failure. On success returns
     * silently — the caller proceeds to build the translator input.
     */
    public void validate(OssieAiQueryRequest req, OssieAiSchema schema) {
        if (req == null) {
            throw new OssieAiValidationException("body", "request body required", List.of());
        }
        if (req.getModel() == null || req.getModel().isBlank()) {
            throw new OssieAiValidationException("model", "model is required", List.of(schema.getModelName()));
        }
        // Rows + columns share the same field-existence check; the JSON path just changes.
        for (int i = 0; i < req.getRows().size(); i++) {
            validateFieldRef(req.getRows().get(i), "rows[" + i + "]", schema);
        }
        for (int i = 0; i < req.getColumns().size(); i++) {
            validateFieldRef(req.getColumns().get(i), "columns[" + i + "]", schema);
        }
        for (int i = 0; i < req.getValues().size(); i++) {
            validateMetricRef(req.getValues().get(i), "values[" + i + "]", schema);
        }
        for (int i = 0; i < req.getFilters().size(); i++) {
            validateFilter(req.getFilters().get(i), "filters[" + i + "]", schema);
        }
        for (int i = 0; i < req.getSorts().size(); i++) {
            validateSort(req.getSorts().get(i), "sorts[" + i + "]", schema);
        }
        if (req.getLimit() != null && req.getLimit() < 1) {
            throw new OssieAiValidationException("limit", "limit must be >= 1 (or omitted)", List.of());
        }
        // Runnable-shape check: no metrics with no values shelf → still runnable (returns a raw
        // rowset), so we don't reject that. But an empty request with no rows/columns/values
        // makes no sense; refuse it early.
        if (req.getRows().isEmpty()
                && req.getColumns().isEmpty()
                && req.getValues().isEmpty()) {
            throw new OssieAiValidationException(
                    "body", "at least one of rows, columns, or values must be non-empty", List.of());
        }
    }

    private void validateFieldRef(OssieAiQueryRequest.FieldRef ref, String path, OssieAiSchema schema) {
        if (ref == null) {
            throw new OssieAiValidationException(path, "field ref must not be null", List.of());
        }
        if (ref.getDataset() == null || ref.getDataset().isBlank()) {
            throw new OssieAiValidationException(
                    path + ".dataset",
                    "dataset is required",
                    new ArrayList<>(schema.getDatasets().keySet()));
        }
        OssieAiSchema.Dataset ds = schema.getDatasets().get(ref.getDataset().toLowerCase(Locale.ROOT));
        if (ds == null) {
            throw new OssieAiValidationException(
                    path + ".dataset",
                    "unknown dataset '" + ref.getDataset() + "'",
                    new ArrayList<>(schema.getDatasets().keySet()));
        }
        if (ref.getField() == null || ref.getField().isBlank()) {
            throw new OssieAiValidationException(
                    path + ".field",
                    "field is required",
                    new ArrayList<>(ds.getFields().keySet()));
        }
        if (!ds.getFields().containsKey(ref.getField().toLowerCase(Locale.ROOT))) {
            throw new OssieAiValidationException(
                    path + ".field",
                    "unknown field '" + ref.getField() + "' on dataset '" + ds.getName() + "'",
                    new ArrayList<>(ds.getFields().keySet()));
        }
    }

    private void validateMetricRef(OssieAiQueryRequest.MetricRef ref, String path, OssieAiSchema schema) {
        if (ref == null) {
            throw new OssieAiValidationException(path, "metric ref must not be null", List.of());
        }
        if (ref.getMetric() == null || ref.getMetric().isBlank()) {
            throw new OssieAiValidationException(
                    path + ".metric",
                    "metric is required",
                    new ArrayList<>(schema.getMetrics().keySet()));
        }
        OssieAiSchema.Metric metric = schema.getMetrics().get(ref.getMetric());
        if (metric == null) {
            throw new OssieAiValidationException(
                    path + ".metric",
                    "unknown metric '" + ref.getMetric() + "'",
                    new ArrayList<>(schema.getMetrics().keySet()));
        }
        if (ref.getAggregation() != null && !ref.getAggregation().isBlank()) {
            String requested = ref.getAggregation().toUpperCase(Locale.ROOT);
            if (!metric.getSupportedOverrides().contains(requested)) {
                throw new OssieAiValidationException(
                        path + ".aggregation",
                        "aggregation '" + ref.getAggregation() + "' not supported for metric '" + metric.getName()
                                + "'",
                        metric.getSupportedOverrides());
            }
        }
    }

    private void validateFilter(OssieAiQueryRequest.FilterExpr filter, String path, OssieAiSchema schema) {
        if (filter == null) {
            throw new OssieAiValidationException(path, "filter must not be null", List.of());
        }
        if (filter.getOp() == null || !FILTER_OPS.contains(filter.getOp().toUpperCase(Locale.ROOT))) {
            throw new OssieAiValidationException(
                    path + ".op", "unsupported filter op '" + filter.getOp() + "'", new ArrayList<>(FILTER_OPS));
        }
        String op = filter.getOp().toUpperCase(Locale.ROOT);
        OssieAiQueryRequest.FieldRef fref = new OssieAiQueryRequest.FieldRef();
        fref.setDataset(filter.getDataset());
        fref.setField(filter.getField());
        validateFieldRef(fref, path, schema);
        if ("BETWEEN".equals(op) && filter.getValues().size() < 2) {
            throw new OssieAiValidationException(
                    path + ".values", "BETWEEN requires exactly two values", List.of("[lo, hi]"));
        }
        if ("IN".equals(op) && filter.getValues().isEmpty()) {
            // Allow empty IN — the translator synthesises the trivially-false predicate.
            // Not a validation error.
        }
    }

    private void validateSort(OssieAiQueryRequest.SortRef sort, String path, OssieAiSchema schema) {
        if (sort == null) {
            throw new OssieAiValidationException(path, "sort must not be null", List.of());
        }
        boolean hasMetric = sort.getMetric() != null && !sort.getMetric().isBlank();
        boolean hasField = sort.getField() != null && !sort.getField().isBlank();
        if (hasMetric == hasField) {
            throw new OssieAiValidationException(
                    path, "sort must reference EITHER a metric OR a dataset+field", List.of());
        }
        if (hasMetric && !schema.getMetrics().containsKey(sort.getMetric())) {
            throw new OssieAiValidationException(
                    path + ".metric",
                    "unknown metric '" + sort.getMetric() + "'",
                    new ArrayList<>(schema.getMetrics().keySet()));
        }
        if (hasField) {
            OssieAiQueryRequest.FieldRef fref = new OssieAiQueryRequest.FieldRef();
            fref.setDataset(sort.getDataset());
            fref.setField(sort.getField());
            validateFieldRef(fref, path, schema);
        }
        if (sort.getDirection() != null
                && !"ASC".equalsIgnoreCase(sort.getDirection())
                && !"DESC".equalsIgnoreCase(sort.getDirection())) {
            throw new OssieAiValidationException(
                    path + ".direction", "direction must be ASC or DESC (or omitted)", List.of("ASC", "DESC"));
        }
    }
}
