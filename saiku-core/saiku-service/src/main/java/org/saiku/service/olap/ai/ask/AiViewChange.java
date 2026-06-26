/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Output of the {@code emit_view_change} tool — instructions for the UI to change how the current
 * cellset is displayed (grid vs chart, which chart type). Returned by {@code POST
 * /saiku/api/ai/ask} when the model picks the view-change intent (e.g. "switch to a chart", "show
 * this as a bar chart").
 *
 * <p>The fields below intentionally mirror the workbench's {@code query.viewMode} +
 * {@code query.chartType} state — the client just assigns them to the store after receiving the
 * response. No query is re-executed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AiViewChange {

    /**
     * Either {@code "grid"} or {@code "chart"}. Anything else the client will reject. Mirrors the
     * workbench's {@code ViewMode}.
     */
    private String viewMode;

    /**
     * One of the chart-type ids enumerated in {@link AiViewChangeCatalog#CHART_TYPES} (kept in sync
     * with the UI's {@code CHART_TYPES} array in {@code chartTypes.ts}). Only meaningful when
     * {@link #viewMode} is {@code "chart"}.
     */
    private String chartType;

    /** Optional 1-sentence rationale shown in the drawer ("Time-series → line chart fits best."). */
    private String reason;

    public AiViewChange() {}

    public AiViewChange(String viewMode, String chartType, String reason) {
        this.viewMode = viewMode;
        this.chartType = chartType;
        this.reason = reason;
    }

    public String getViewMode() {
        return viewMode;
    }

    public void setViewMode(String v) {
        this.viewMode = v;
    }

    public String getChartType() {
        return chartType;
    }

    public void setChartType(String v) {
        this.chartType = v;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = v;
    }
}
