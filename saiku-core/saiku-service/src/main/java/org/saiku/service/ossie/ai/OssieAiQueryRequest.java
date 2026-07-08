/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-format Ossie AI query request. Same shelf-state shape the workbench posts, plus the
 * AI-specific {@code model} discriminator so the resource can look up the semantic model
 * without a separate {@code connection/model} path parameter on {@code POST /ai/ossie/query}.
 *
 * <p>Mirrors {@code AiQueryRequest} structurally so the two AI surfaces read as siblings.
 * The reason we don't share a DTO with the MDX side: MDX talks measures + dimensions +
 * hierarchies + levels; Ossie talks datasets + fields + metrics. Reusing the MDX terminology
 * for the Ossie surface would confuse the agent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OssieAiQueryRequest {

    /** Ossie connection name (matches the Saiku datasource name). */
    private String connection;

    /** Semantic-model name inside the YAML. Optional — defaults to the connection's schema. */
    private String model;

    /** Row-shelf field refs. */
    private List<FieldRef> rows = new ArrayList<>();

    /** Column-shelf field refs. */
    private List<FieldRef> columns = new ArrayList<>();

    /** Values-shelf metric refs. */
    private List<MetricRef> values = new ArrayList<>();

    /** Filter predicates. */
    private List<FilterExpr> filters = new ArrayList<>();

    /** Sort ordering. */
    private List<SortRef> sorts = new ArrayList<>();

    /** Result-row cap. Null → no LIMIT. */
    private Integer limit;

    public String getConnection() {
        return connection;
    }

    public void setConnection(String v) {
        this.connection = v;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String v) {
        this.model = v;
    }

    public List<FieldRef> getRows() {
        return rows;
    }

    public void setRows(List<FieldRef> v) {
        this.rows = v == null ? new ArrayList<>() : v;
    }

    public List<FieldRef> getColumns() {
        return columns;
    }

    public void setColumns(List<FieldRef> v) {
        this.columns = v == null ? new ArrayList<>() : v;
    }

    public List<MetricRef> getValues() {
        return values;
    }

    public void setValues(List<MetricRef> v) {
        this.values = v == null ? new ArrayList<>() : v;
    }

    public List<FilterExpr> getFilters() {
        return filters;
    }

    public void setFilters(List<FilterExpr> v) {
        this.filters = v == null ? new ArrayList<>() : v;
    }

    public List<SortRef> getSorts() {
        return sorts;
    }

    public void setSorts(List<SortRef> v) {
        this.sorts = v == null ? new ArrayList<>() : v;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer v) {
        this.limit = v;
    }

    /** Reference to a field on a dataset. */
    public static class FieldRef {
        private String dataset;
        private String field;

        public String getDataset() {
            return dataset;
        }

        public void setDataset(String v) {
            this.dataset = v;
        }

        public String getField() {
            return field;
        }

        public void setField(String v) {
            this.field = v;
        }
    }

    /**
     * Reference to a metric with an optional aggregation override. The override is validated
     * against the metric's {@code supportedOverrides} — the same list the schema endpoint
     * publishes — so a caller asking for {@code SUM} on a {@code COUNT(*)} metric is rejected
     * with a typed error rather than the translator silently ignoring it.
     */
    public static class MetricRef {
        private String metric;
        private String aggregation;

        public String getMetric() {
            return metric;
        }

        public void setMetric(String v) {
            this.metric = v;
        }

        public String getAggregation() {
            return aggregation;
        }

        public void setAggregation(String v) {
            this.aggregation = v;
        }
    }

    /**
     * Filter predicate. {@code op} follows the same set the shelf translator supports
     * (EQ / NEQ / LT / LTE / GT / GTE / IN / BETWEEN / IS_NULL / IS_NOT_NULL). One value on
     * {@code value}; two-or-more values on {@code values} (IN + BETWEEN).
     */
    public static class FilterExpr {
        private String dataset;
        private String field;
        private String op;
        private String value;
        private List<String> values = new ArrayList<>();

        public String getDataset() {
            return dataset;
        }

        public void setDataset(String v) {
            this.dataset = v;
        }

        public String getField() {
            return field;
        }

        public void setField(String v) {
            this.field = v;
        }

        public String getOp() {
            return op;
        }

        public void setOp(String v) {
            this.op = v;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String v) {
            this.value = v;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> v) {
            this.values = v == null ? new ArrayList<>() : v;
        }
    }

    /** Sort on a metric OR a field. Mutually exclusive: exactly one of the two must be set. */
    public static class SortRef {
        private String metric;
        private String dataset;
        private String field;
        private String direction;

        public String getMetric() {
            return metric;
        }

        public void setMetric(String v) {
            this.metric = v;
        }

        public String getDataset() {
            return dataset;
        }

        public void setDataset(String v) {
            this.dataset = v;
        }

        public String getField() {
            return field;
        }

        public void setField(String v) {
            this.field = v;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String v) {
            this.direction = v;
        }
    }
}
