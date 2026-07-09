/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-facing schema DTO for one Ossie model. Populated by
 * {@link OssieAiSchemaProjector} from the internal {@link org.saiku.service.ossie.OssieModelDto};
 * serialised to JSON on {@code GET /ai/ossie/schema/{connection}/{model}}.
 *
 * <p>Every field carries agent-facing metadata: sample values, cardinality hints, aggregation
 * kinds, {@link #supportedOverrides} on metrics so callers don't ask for {@code SUM(*)} on a
 * {@code COUNT(*)} metric and hit a validation error. Ready-made {@link #examples} give the
 * agent copy-paste request bodies for the common shapes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OssieAiSchema {

    private String modelId;
    private String connectionName;
    private String modelName;
    private String description;
    private String factDataset;

    /** Datasets keyed by name (lower-case). */
    private Map<String, Dataset> datasets = new LinkedHashMap<>();

    /** Metrics keyed by name. */
    private Map<String, Metric> metrics = new LinkedHashMap<>();

    /** Relationships as a flat list — order preserved from the YAML. */
    private List<Relationship> relationships = new ArrayList<>();

    /**
     * {@code lowercased-synonym → "<dataset>.<field>"}. Populated from every field's
     * {@code ai_context.synonyms} in the source YAML. Callers that receive a caller-supplied
     * field name can resolve it against this map first: an unknown name that appears here is a
     * synonym and gets rewritten to the canonical key. Empty when no field declares synonyms.
     */
    private Map<String, String> fieldAliases = new LinkedHashMap<>();

    /**
     * {@code lowercased-synonym → canonical-metric-name}. Same shape as {@link #fieldAliases}
     * but for metrics (which are model-scoped in the OSI spec, so no dataset qualifier).
     */
    private Map<String, String> metricAliases = new LinkedHashMap<>();

    /**
     * {@code lowercased-synonym → canonical-dataset-name}. Same shape as {@link #fieldAliases}
     * but for datasets.
     */
    private Map<String, String> datasetAliases = new LinkedHashMap<>();

    /**
     * JSON Schema for the request body. Kept as a raw {@link ObjectNode} because the doc is
     * verbose and already-serialised elsewhere; embedding it as-is keeps this DTO small.
     */
    private ObjectNode requestSchema;

    /** Ready-made request bodies keyed by shape name ({@code simpleGroupBy}, {@code crosstab}, ...). */
    private Map<String, Example> examples = new LinkedHashMap<>();

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String v) {
        this.modelId = v;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String v) {
        this.connectionName = v;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String v) {
        this.modelName = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = v;
    }

    public String getFactDataset() {
        return factDataset;
    }

    public void setFactDataset(String v) {
        this.factDataset = v;
    }

    public Map<String, Dataset> getDatasets() {
        return datasets;
    }

    public void setDatasets(Map<String, Dataset> v) {
        this.datasets = v == null ? new LinkedHashMap<>() : v;
    }

    public Map<String, Metric> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Metric> v) {
        this.metrics = v == null ? new LinkedHashMap<>() : v;
    }

    public List<Relationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<Relationship> v) {
        this.relationships = v == null ? new ArrayList<>() : v;
    }

    public Map<String, String> getFieldAliases() {
        return fieldAliases;
    }

    public void setFieldAliases(Map<String, String> v) {
        this.fieldAliases = v == null ? new LinkedHashMap<>() : v;
    }

    public Map<String, String> getMetricAliases() {
        return metricAliases;
    }

    public void setMetricAliases(Map<String, String> v) {
        this.metricAliases = v == null ? new LinkedHashMap<>() : v;
    }

    public Map<String, String> getDatasetAliases() {
        return datasetAliases;
    }

    public void setDatasetAliases(Map<String, String> v) {
        this.datasetAliases = v == null ? new LinkedHashMap<>() : v;
    }

    public ObjectNode getRequestSchema() {
        return requestSchema;
    }

    public void setRequestSchema(ObjectNode v) {
        this.requestSchema = v;
    }

    public Map<String, Example> getExamples() {
        return examples;
    }

    public void setExamples(Map<String, Example> v) {
        this.examples = v == null ? new LinkedHashMap<>() : v;
    }

    // ---------------- Nested types ----------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Dataset {
        private String name;
        private String source;
        private String description;
        private List<String> primaryKey = new ArrayList<>();

        /** Fields keyed by lower-case field name. */
        private Map<String, Field> fields = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String v) {
            this.source = v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            this.description = v;
        }

        public List<String> getPrimaryKey() {
            return primaryKey;
        }

        public void setPrimaryKey(List<String> v) {
            this.primaryKey = v == null ? new ArrayList<>() : v;
        }

        public Map<String, Field> getFields() {
            return fields;
        }

        public void setFields(Map<String, Field> v) {
            this.fields = v == null ? new LinkedHashMap<>() : v;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Field {
        private String name;
        /**
         * Human-readable label from Ossie {@code field.label} (spec 0.2.0.dev0). Nullable —
         * consumers fall back to {@link #name}. Surfaces "Net Revenue" alongside {@code NETREVENUE}.
         */
        private String label;

        private String type;
        private String description;
        private String unit;
        private String currency;
        private String cardinality;
        /**
         * Best-effort distinct-count estimate — populated from
         * {@code APPROX_COUNT_DISTINCT} on warehouses that support it, else null (#1405).
         */
        private Long estimatedDistinct;

        private List<String> sampleValues = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String v) {
            this.label = v;
        }

        public String getType() {
            return type;
        }

        public void setType(String v) {
            this.type = v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            this.description = v;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String v) {
            this.unit = v;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String v) {
            this.currency = v;
        }

        public String getCardinality() {
            return cardinality;
        }

        public void setCardinality(String v) {
            this.cardinality = v;
        }

        public List<String> getSampleValues() {
            return sampleValues;
        }

        public void setSampleValues(List<String> v) {
            this.sampleValues = v == null ? new ArrayList<>() : v;
        }

        public Long getEstimatedDistinct() {
            return estimatedDistinct;
        }

        public void setEstimatedDistinct(Long v) {
            this.estimatedDistinct = v;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Metric {
        private String name;
        private String displayName;
        private String description;
        private String expression;
        private String aggregationKind;
        private String unit;
        private List<String> supportedOverrides = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String v) {
            this.displayName = v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            this.description = v;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String v) {
            this.expression = v;
        }

        public String getAggregationKind() {
            return aggregationKind;
        }

        public void setAggregationKind(String v) {
            this.aggregationKind = v;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String v) {
            this.unit = v;
        }

        public List<String> getSupportedOverrides() {
            return supportedOverrides;
        }

        public void setSupportedOverrides(List<String> v) {
            this.supportedOverrides = v == null ? new ArrayList<>() : v;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Relationship {
        private String name;
        private String from;
        private String to;
        private List<String> fromColumns = new ArrayList<>();
        private List<String> toColumns = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String v) {
            this.from = v;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String v) {
            this.to = v;
        }

        public List<String> getFromColumns() {
            return fromColumns;
        }

        public void setFromColumns(List<String> v) {
            this.fromColumns = v == null ? new ArrayList<>() : v;
        }

        public List<String> getToColumns() {
            return toColumns;
        }

        public void setToColumns(List<String> v) {
            this.toColumns = v == null ? new ArrayList<>() : v;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Example {
        private String description;
        private OssieAiQueryRequest body;

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            this.description = v;
        }

        public OssieAiQueryRequest getBody() {
            return body;
        }

        public void setBody(OssieAiQueryRequest v) {
            this.body = v;
        }
    }
}
