/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

/**
 * Body of {@code POST /saiku/api/ai/explain}.
 *
 * <p>{@code queryName} + {@code position} is all the client has to send: the server-side query
 * context already holds the executed cellset, so the panel opens from cached data and no re-run of
 * the user's query happens. {@code position} is the plain data coordinate of the right-clicked
 * cell — {@code row} counts data rows, {@code column} counts data columns, neither counts the
 * header bands.
 *
 * <p>{@code queryId} is accepted as an alias of {@code queryName} because the issue that
 * introduced the endpoint specified {@code {queryId, position}}; the two name the same session
 * query and setting either wins.
 */
public class CellExplainRequest {

    private String queryName;
    private CellCoordinate position;
    private boolean includeDrivers = true;
    private boolean includeNarrative = true;
    private boolean includeSql = true;

    /** Zero-based data coordinates of the cell to explain. */
    public record CellCoordinate(int row, int column) {
        public boolean isValid() {
            return row >= 0 && column >= 0;
        }
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String queryName) {
        this.queryName = queryName;
    }

    public String getQueryId() {
        return queryName;
    }

    public void setQueryId(String queryId) {
        this.queryName = queryId;
    }

    public CellCoordinate getPosition() {
        return position;
    }

    public void setPosition(CellCoordinate position) {
        this.position = position;
    }

    public boolean isIncludeDrivers() {
        return includeDrivers;
    }

    public void setIncludeDrivers(boolean includeDrivers) {
        this.includeDrivers = includeDrivers;
    }

    public boolean isIncludeNarrative() {
        return includeNarrative;
    }

    public void setIncludeNarrative(boolean includeNarrative) {
        this.includeNarrative = includeNarrative;
    }

    public boolean isIncludeSql() {
        return includeSql;
    }

    public void setIncludeSql(boolean includeSql) {
        this.includeSql = includeSql;
    }
}
