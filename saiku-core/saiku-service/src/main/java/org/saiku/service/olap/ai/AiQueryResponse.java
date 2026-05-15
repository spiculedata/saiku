/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Return shape for {@code POST /saiku/api/ai/query}.
 *
 * <p>Two output formats:
 * <ul>
 *   <li><strong>records</strong> (default) — {@link #data} is a list of
 *       row maps keyed by row-axis caption(s) + each column caption.
 *       Each value is an {@link AiCell} with parsed numeric value,
 *       Mondrian-formatted display string, and an optional unit. LLM-friendly.</li>
 *   <li><strong>matrix</strong> — {@link #matrix} is a row-major list of
 *       cells; each cell maps the column index (as a string) to an
 *       {@link AiCell}. Back-compat for UI/clients that wired position-
 *       based rendering.</li>
 * </ul>
 *
 * <p>The active format is recorded in {@link #format}. Only one of
 * {@code data} / {@code matrix} is populated per response.
 */
public class AiQueryResponse {

    public enum Status {
        SUCCESS,
        VALIDATION_ERROR,
        EXECUTION_ERROR,
        PERMISSION_DENIED,
        RATE_LIMITED,
        TIMEOUT,
        WAREHOUSE_ERROR,
        CUBE_NOT_FOUND
    }

    private String queryId;
    private Status status;
    private AiQueryMetadata metadata;
    /** "records" (default) or "matrix". */
    private String format;
    /** Records-format payload: rows of caption→AiCell. */
    private List<java.util.Map<String, Object>> data = new ArrayList<>();
    /** Matrix-format payload: positional rows of colIndex→AiCell. */
    private List<java.util.Map<String, AiCell>> matrix = new ArrayList<>();

    private int totalRows;
    private long runtimeMs;
    /** Populated when status is VALIDATION_ERROR / EXECUTION_ERROR / *_ERROR. */
    private String error;
    /** Optional field path on validation error, e.g. "filters[0].level". */
    private String field;
    /** Optional candidates list for validation error, e.g. valid level names. */
    private List<String> available = new ArrayList<>();

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String v) {
        this.queryId = v;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status v) {
        this.status = v;
    }

    public AiQueryMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(AiQueryMetadata v) {
        this.metadata = v;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String v) {
        this.format = v;
    }

    public List<java.util.Map<String, Object>> getData() {
        return data;
    }

    public void setData(List<java.util.Map<String, Object>> v) {
        this.data = v == null ? new ArrayList<>() : v;
    }

    public List<java.util.Map<String, AiCell>> getMatrix() {
        return matrix;
    }

    public void setMatrix(List<java.util.Map<String, AiCell>> v) {
        this.matrix = v == null ? new ArrayList<>() : v;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int v) {
        this.totalRows = v;
    }

    public long getRuntimeMs() {
        return runtimeMs;
    }

    public void setRuntimeMs(long v) {
        this.runtimeMs = v;
    }

    public String getError() {
        return error;
    }

    public void setError(String v) {
        this.error = v;
    }

    public String getField() {
        return field;
    }

    public void setField(String v) {
        this.field = v;
    }

    public List<String> getAvailable() {
        return available;
    }

    public void setAvailable(List<String> v) {
        this.available = v == null ? new ArrayList<>() : v;
    }
}
