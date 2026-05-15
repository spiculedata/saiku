/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level body for {@code POST /saiku/api/ai/query}. Designed so an
 * agent can fill it in by reading {@code GET /ai/schema/{cubeId}} —
 * every name field must resolve against that schema.
 */
public class AiQueryRequest {

    private AiCubeRef cube;
    private List<AiMeasureSelection> measures = new ArrayList<>();
    private List<AiAxisSelection> rows = new ArrayList<>();
    private List<AiAxisSelection> columns = new ArrayList<>();
    private List<AiFilterSelection> filters = new ArrayList<>();
    /** Optional row cap. Translated into TopCount on the rows axis. <=0 means "no cap". */
    private int limit = 0;
    /** Optional: include parent totals via VISUALTOTALS on rows. */
    private boolean visualTotals = false;
    /** Optional: drop entirely-empty rows from the result (NON EMPTY on rows axis). */
    private boolean nonEmpty = true;

    public AiCubeRef getCube() { return cube; }
    public void setCube(AiCubeRef v) { this.cube = v; }
    public List<AiMeasureSelection> getMeasures() { return measures; }
    public void setMeasures(List<AiMeasureSelection> v) { this.measures = v == null ? new ArrayList<>() : v; }
    public List<AiAxisSelection> getRows() { return rows; }
    public void setRows(List<AiAxisSelection> v) { this.rows = v == null ? new ArrayList<>() : v; }
    public List<AiAxisSelection> getColumns() { return columns; }
    public void setColumns(List<AiAxisSelection> v) { this.columns = v == null ? new ArrayList<>() : v; }
    public List<AiFilterSelection> getFilters() { return filters; }
    public void setFilters(List<AiFilterSelection> v) { this.filters = v == null ? new ArrayList<>() : v; }
    public int getLimit() { return limit; }
    public void setLimit(int v) { this.limit = v; }
    public boolean isVisualTotals() { return visualTotals; }
    public void setVisualTotals(boolean v) { this.visualTotals = v; }
    public boolean isNonEmpty() { return nonEmpty; }
    public void setNonEmpty(boolean v) { this.nonEmpty = v; }
}
