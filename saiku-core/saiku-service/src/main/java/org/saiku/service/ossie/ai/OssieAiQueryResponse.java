/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records-format response to {@code POST /ai/ossie/query}. Same shape MDX's records format
 * uses:
 *
 * <ul>
 *   <li>{@code columns[]} — one per output column, in emission order.
 *   <li>{@code records[]} — one map per row; keys are column {@code key}s; values are either
 *       strings (dimension cells) or {@link CellValue} objects (metric cells) with
 *       {@code value} + {@code formatted} + optional {@code unit}.
 *   <li>{@code meta} — total row count, truncation flag, suppression info.
 * </ul>
 *
 * <p>Matrix format is not R1 — comes in R2 as {@code ?format=matrix}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OssieAiQueryResponse {

    private String queryId;
    private Long runtime;
    private String generatedSql;
    private List<Column> columns = new ArrayList<>();
    private List<Map<String, Object>> records = new ArrayList<>();
    private Meta meta = new Meta();

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String v) {
        this.queryId = v;
    }

    public Long getRuntime() {
        return runtime;
    }

    public void setRuntime(Long v) {
        this.runtime = v;
    }

    public String getGeneratedSql() {
        return generatedSql;
    }

    public void setGeneratedSql(String v) {
        this.generatedSql = v;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void setColumns(List<Column> v) {
        this.columns = v == null ? new ArrayList<>() : v;
    }

    public List<Map<String, Object>> getRecords() {
        return records;
    }

    public void setRecords(List<Map<String, Object>> v) {
        this.records = v == null ? new ArrayList<>() : v;
    }

    public Meta getMeta() {
        return meta;
    }

    public void setMeta(Meta v) {
        this.meta = v == null ? new Meta() : v;
    }

    /** Column descriptor — the schema of one output column. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Column {
        /** The key used in each record map. Dimensions: {@code dataset.field}; metrics: metric name. */
        private String key;

        /** Human-readable label. Falls back to key when no display metadata is available. */
        private String label;

        /** {@code dimension} or {@code metric}. Agents branch on this to decide cell parsing. */
        private String type;

        /** Metric only: the aggregation function actually applied (declared or overridden). */
        private String aggregationKind;

        /** Metric only: unit hint ({@code USD}, {@code count}, {@code percent}). Copied from YAML. */
        private String unit;

        public Column() {}

        public Column(String key, String label, String type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String v) {
            this.key = v;
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
    }

    /** Cell wrapper for metric values. Dimensions render as bare strings so JSON stays flat. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CellValue {
        private Object value;
        private String formatted;
        private String unit;

        public CellValue() {}

        public CellValue(Object value, String formatted, String unit) {
            this.value = value;
            this.formatted = formatted;
            this.unit = unit;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object v) {
            this.value = v;
        }

        public String getFormatted() {
            return formatted;
        }

        public void setFormatted(String v) {
            this.formatted = v;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String v) {
            this.unit = v;
        }
    }

    /**
     * Response metadata block: rowCount, truncation, and PII/k-anonymity suppression info.
     * Suppression is a placeholder in R1 — the fields render as null/0 until R2 wires the
     * suppression logic.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        private int rowCount;
        private boolean truncated;
        private Suppressed suppressed;

        public int getRowCount() {
            return rowCount;
        }

        public void setRowCount(int v) {
            this.rowCount = v;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public void setTruncated(boolean v) {
            this.truncated = v;
        }

        public Suppressed getSuppressed() {
            return suppressed;
        }

        public void setSuppressed(Suppressed v) {
            this.suppressed = v;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Suppressed {
        private int count;
        private String reason;

        public int getCount() {
            return count;
        }

        public void setCount(int v) {
            this.count = v;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String v) {
            this.reason = v;
        }
    }

    // ---------------------------------------------------------------
    // Convenience builders — keep the resource layer readable.
    // ---------------------------------------------------------------

    public static Map<String, Object> newRecord() {
        return new LinkedHashMap<>();
    }
}
