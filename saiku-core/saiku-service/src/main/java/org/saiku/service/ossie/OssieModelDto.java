/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-facing view of one Ossie {@code semantic_model} — the tree the analytics workbench
 * renders in the schema browser and drags fields/metrics from.
 *
 * <p>Structurally a slimmed-down projection of {@link org.saiku.service.schema.ossie.model.SemanticModel}:
 * we drop {@code ai_context}, {@code custom_extensions.data} payloads, and any dialect other
 * than the primary ANSI SQL one so the on-wire JSON stays small. The write-side (metric
 * annotations, PII vendor extensions) still lives in the YAML — the workbench only needs enough
 * to power drag-drop + display.
 */
public class OssieModelDto {

    private String connection;
    private String name;
    private String description;
    private List<Dataset> datasets = new ArrayList<>();
    private List<Metric> metrics = new ArrayList<>();
    private List<Relationship> relationships = new ArrayList<>();

    public String getConnection() {
        return connection;
    }

    public void setConnection(String v) {
        this.connection = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = v;
    }

    public List<Dataset> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<Dataset> v) {
        this.datasets = v == null ? new ArrayList<>() : v;
    }

    public List<Metric> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<Metric> v) {
        this.metrics = v == null ? new ArrayList<>() : v;
    }

    public List<Relationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<Relationship> v) {
        this.relationships = v == null ? new ArrayList<>() : v;
    }

    /** One Ossie dataset — a queryable table. */
    public static class Dataset {
        private String name;
        private String source;
        private String description;
        private List<Field> fields = new ArrayList<>();
        private List<String> primaryKey = new ArrayList<>();

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

        public List<Field> getFields() {
            return fields;
        }

        public void setFields(List<Field> v) {
            this.fields = v == null ? new ArrayList<>() : v;
        }

        public List<String> getPrimaryKey() {
            return primaryKey;
        }

        public void setPrimaryKey(List<String> v) {
            this.primaryKey = v == null ? new ArrayList<>() : v;
        }
    }

    /** Field on a dataset. Carries the SQL expression + display metadata for the workbench. */
    public static class Field {
        private String name;
        private String expression;
        private String label;
        private String description;
        private boolean isTime;
        private boolean pii;

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String v) {
            this.expression = v;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String v) {
            this.label = v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            this.description = v;
        }

        public boolean isTime() {
            return isTime;
        }

        public void setTime(boolean v) {
            this.isTime = v;
        }

        public boolean isPii() {
            return pii;
        }

        public void setPii(boolean v) {
            this.pii = v;
        }
    }

    /** Ossie metric — expands to an aggregate expression at query time. */
    public static class Metric {
        private String name;
        private String expression;
        private String description;
        private String aggregationKind;

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String v) {
            this.expression = v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            this.description = v;
        }

        public String getAggregationKind() {
            return aggregationKind;
        }

        public void setAggregationKind(String v) {
            this.aggregationKind = v;
        }
    }

    /** Cross-dataset link. Not draggable itself, but the auto-join rule uses these at query time. */
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
}
