/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Return shape for {@code POST /saiku/api/ai/query}. {@code matrix} is a
 * row-major list of cells; each cell maps the column index (as a string,
 * for JSON compactness) to a formatted value string.
 */
public class AiQueryResponse {

    public enum Status { SUCCESS, VALIDATION_ERROR, EXECUTION_ERROR }

    private String queryId;
    private Status status;
    private AiQueryMetadata metadata;
    private List<java.util.Map<String, String>> matrix = new ArrayList<>();
    private int totalRows;
    private long runtimeMs;
    /** Populated when status is VALIDATION_ERROR / EXECUTION_ERROR. */
    private String error;
    /** Optional field path on validation error, e.g. "filters[0].level". */
    private String field;
    /** Optional candidates list for validation error, e.g. valid level names. */
    private List<String> available = new ArrayList<>();

    public String getQueryId() { return queryId; }
    public void setQueryId(String v) { this.queryId = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public AiQueryMetadata getMetadata() { return metadata; }
    public void setMetadata(AiQueryMetadata v) { this.metadata = v; }
    public List<java.util.Map<String, String>> getMatrix() { return matrix; }
    public void setMatrix(List<java.util.Map<String, String>> v) { this.matrix = v == null ? new ArrayList<>() : v; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int v) { this.totalRows = v; }
    public long getRuntimeMs() { return runtimeMs; }
    public void setRuntimeMs(long v) { this.runtimeMs = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public String getField() { return field; }
    public void setField(String v) { this.field = v; }
    public List<String> getAvailable() { return available; }
    public void setAvailable(List<String> v) { this.available = v == null ? new ArrayList<>() : v; }
}
