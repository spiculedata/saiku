/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import java.util.ArrayList;
import java.util.List;

/**
 * Shelf-state payload for an Ossie query. Sent as the {@code ossieQueryModel} field on
 * {@link ThinQuery} when {@code queryType="OSSIE"} — the exact analogue of {@link ThinQueryModel}
 * on the MDX side, but flat + SQL-shaped.
 *
 * <p>The workbench populates this straight from drag-drop actions:
 *
 * <ul>
 *   <li>{@link #rows} — dimensions on the Y axis; each is a {@code (dataset, field)} pair
 *   <li>{@link #columns} — dimensions on the X axis; same shape as rows
 *   <li>{@link #values} — metrics on the Values shelf; the aggregate expressions from the Ossie
 *       model
 *   <li>{@link #filters} — WHERE-clause predicates
 *   <li>{@link #sorts} — ORDER BY entries
 * </ul>
 *
 * <p>{@link #factDataset} names the "anchor" dataset — the one every metric implicitly references.
 * The generator always includes it in {@code FROM}; other datasets get added only when a shelf
 * references them. The {@code OssieAutoJoinRule} injects the join predicates at plan time.
 */
public class OssieQueryModel {

    private String connection;
    private String model;
    private String factDataset;
    private List<FieldRef> rows = new ArrayList<>();
    private List<FieldRef> columns = new ArrayList<>();
    private List<MetricRef> values = new ArrayList<>();
    private List<FilterExpr> filters = new ArrayList<>();
    private List<SortRef> sorts = new ArrayList<>();
    private Integer limit;

    public String getConnection() {
        return connection;
    }

    public void setConnection(String connection) {
        this.connection = connection;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFactDataset() {
        return factDataset;
    }

    public void setFactDataset(String v) {
        this.factDataset = v;
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

    /** Reference to one {@code (dataset, field)} pair in an Ossie semantic model. */
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

    /** Reference to one Ossie metric by name. Expression + aggregation come from the model. */
    public static class MetricRef {
        private String metric;

        /**
         * Optional aggregation override for this metric on this shelf state. When set,
         * the SQL translator swaps the outer aggregate function in the metric's declared
         * expression (SUM/AVG/MIN/MAX/COUNT) with this one. Null leaves the metric's
         * own expression unchanged. Valid values: {@code SUM|AVG|MIN|MAX|COUNT} — anything
         * else is silently ignored (the translator falls back to the declared expression).
         */
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
     * WHERE-clause predicate over one field. Supported operators are the small set the workbench
     * builds — enough for the drag-drop UI, room to grow later.
     *
     * <ul>
     *   <li>{@code EQ} — {@code field = value}
     *   <li>{@code NEQ} — {@code field <> value}
     *   <li>{@code LT / LTE / GT / GTE} — numeric comparisons
     *   <li>{@code IN} — {@code field IN (values...)} with {@code values} used
     *   <li>{@code BETWEEN} — {@code field BETWEEN values[0] AND values[1]}
     *   <li>{@code IS_NULL / IS_NOT_NULL} — no value(s) required
     * </ul>
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

    /** ORDER BY entry. {@code direction} is {@code "ASC"} or {@code "DESC"}. */
    public static class SortRef {
        private String dataset;
        private String field;
        private String metric;
        private String direction = "ASC";

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

        public String getMetric() {
            return metric;
        }

        public void setMetric(String v) {
            this.metric = v;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String v) {
            this.direction = v;
        }
    }
}
