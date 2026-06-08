/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * Body for {@code POST /saiku/api/ai/anomaly} (saiku#907).
 *
 * <pre>{@code
 * {
 *   "query": { ...AiQueryRequest... },
 *   "method": "zscore" | "mad" | "stl",
 *   "threshold": 3.0,
 *   "timeAxis": "[Time].[Time].[Month]"
 * }
 * }</pre>
 *
 * <p>{@code query} is executed through the same path {@code /ai/query} uses.
 * The numeric series is extracted along {@code timeAxis} (the unique name of a
 * level/hierarchy on the row axis, or the caption of a column header) and the
 * chosen {@code method} flags anomalous points. {@code threshold} is optional —
 * the detector's default applies when it is omitted (null) or non-positive.
 */
public class AiAnomalyRequest {

    private AiQueryRequest query;
    private String method;
    private Double threshold;
    private String timeAxis;

    public AiQueryRequest getQuery() {
        return query;
    }

    public void setQuery(AiQueryRequest v) {
        this.query = v;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String v) {
        this.method = v;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double v) {
        this.threshold = v;
    }

    public String getTimeAxis() {
        return timeAxis;
    }

    public void setTimeAxis(String v) {
        this.timeAxis = v;
    }
}
