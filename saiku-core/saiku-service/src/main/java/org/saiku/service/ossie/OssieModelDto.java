/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-facing view of one Ossie {@code semantic_model} — the tree the analytics workbench
 * renders in the schema browser and drags fields/metrics from.
 *
 * <p>Structurally a slimmed-down projection of {@link bi.saiku.ossie.model.SemanticModel}:
 * we drop {@code ai_context.instructions}, {@code custom_extensions.data} payloads, and any dialect other
 * than the primary ANSI SQL one so the on-wire JSON stays small. The write-side (metric
 * annotations, PII vendor extensions) still lives in the YAML — the workbench only needs enough
 * to power drag-drop + display.
 *
 * <p>Synonym maps ({@link #getFieldAliases()} etc.) are pre-computed at DTO-build time via
 * {@code bi.saiku.ossie.OssieSynonymIndex} so downstream consumers (the AI schema projector,
 * validators) don't rewalk the source tree.
 */
public class OssieModelDto {

    private String connection;
    private String name;
    private String description;
    private List<Dataset> datasets = new ArrayList<>();
    private List<Metric> metrics = new ArrayList<>();
    private List<Relationship> relationships = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> fieldAliases = new LinkedHashMap<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> metricAliases = new LinkedHashMap<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> datasetAliases = new LinkedHashMap<>();

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

    /** One Ossie dataset — a queryable table. */
    public static class Dataset {
        private String name;
        private String source;
        private String description;
        private List<Field> fields = new ArrayList<>();
        private List<String> primaryKey = new ArrayList<>();

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<CustomExtensionDto> customExtensions = new ArrayList<>();

        public List<CustomExtensionDto> getCustomExtensions() {
            return customExtensions;
        }

        public void setCustomExtensions(List<CustomExtensionDto> v) {
            this.customExtensions = v == null ? new ArrayList<>() : v;
        }

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

        // saiku#1409 well-known extensions. All null when the operator hasn't authored one.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String displayCaption;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String displayFormat;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String displayUnit;

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private boolean displayHidden;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> allowRoles = new ArrayList<>();

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> denyRoles = new ArrayList<>();

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String piiLevel;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<CustomExtensionDto> customExtensions = new ArrayList<>();

        public List<CustomExtensionDto> getCustomExtensions() {
            return customExtensions;
        }

        public void setCustomExtensions(List<CustomExtensionDto> v) {
            this.customExtensions = v == null ? new ArrayList<>() : v;
        }

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

        public String getDisplayCaption() {
            return displayCaption;
        }

        public void setDisplayCaption(String v) {
            this.displayCaption = v;
        }

        public String getDisplayFormat() {
            return displayFormat;
        }

        public void setDisplayFormat(String v) {
            this.displayFormat = v;
        }

        public String getDisplayUnit() {
            return displayUnit;
        }

        public void setDisplayUnit(String v) {
            this.displayUnit = v;
        }

        public boolean isDisplayHidden() {
            return displayHidden;
        }

        public void setDisplayHidden(boolean v) {
            this.displayHidden = v;
        }

        public List<String> getAllowRoles() {
            return allowRoles;
        }

        public void setAllowRoles(List<String> v) {
            this.allowRoles = v == null ? new ArrayList<>() : v;
        }

        public List<String> getDenyRoles() {
            return denyRoles;
        }

        public void setDenyRoles(List<String> v) {
            this.denyRoles = v == null ? new ArrayList<>() : v;
        }

        public String getPiiLevel() {
            return piiLevel;
        }

        public void setPiiLevel(String v) {
            this.piiLevel = v;
        }
    }

    /** Ossie metric — expands to an aggregate expression at query time. */
    public static class Metric {
        private String name;
        private String expression;
        private String description;
        private String aggregationKind;

        // saiku#1409 well-known extensions — same shape as Field.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String displayCaption;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String displayFormat;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String displayUnit;

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private boolean displayHidden;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> allowRoles = new ArrayList<>();

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> denyRoles = new ArrayList<>();

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<CustomExtensionDto> customExtensions = new ArrayList<>();

        public List<CustomExtensionDto> getCustomExtensions() {
            return customExtensions;
        }

        public void setCustomExtensions(List<CustomExtensionDto> v) {
            this.customExtensions = v == null ? new ArrayList<>() : v;
        }

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

        public String getDisplayCaption() {
            return displayCaption;
        }

        public void setDisplayCaption(String v) {
            this.displayCaption = v;
        }

        public String getDisplayFormat() {
            return displayFormat;
        }

        public void setDisplayFormat(String v) {
            this.displayFormat = v;
        }

        public String getDisplayUnit() {
            return displayUnit;
        }

        public void setDisplayUnit(String v) {
            this.displayUnit = v;
        }

        public boolean isDisplayHidden() {
            return displayHidden;
        }

        public void setDisplayHidden(boolean v) {
            this.displayHidden = v;
        }

        public List<String> getAllowRoles() {
            return allowRoles;
        }

        public void setAllowRoles(List<String> v) {
            this.allowRoles = v == null ? new ArrayList<>() : v;
        }

        public List<String> getDenyRoles() {
            return denyRoles;
        }

        public void setDenyRoles(List<String> v) {
            this.denyRoles = v == null ? new ArrayList<>() : v;
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
