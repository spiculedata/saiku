/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire-format request for {@code POST /ai/ossie/anomaly}. Mirrors the MDX-side
 * {@code AiAnomalyRequest} shape; substitutes the Ossie query envelope + a
 * {@code dataset.field} time-axis reference.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OssieAiAnomalyRequest {

    /** The query whose results should be scanned for anomalies. */
    private OssieAiQueryRequest query;

    /**
     * The column key (dot-separated {@code dataset.field}) that carries the time dimension. The
     * server sorts records by this axis before running the detector so the input series is in
     * time order regardless of the SQL's ORDER BY.
     */
    private String timeAxis;

    /** Detector identifier — see {@code org.saiku.service.olap.ai.anomaly.AnomalyDetectors}. */
    private String method;

    /** Optional detector-specific threshold. Null → detector's default. */
    private Double threshold;

    public OssieAiQueryRequest getQuery() {
        return query;
    }

    public void setQuery(OssieAiQueryRequest v) {
        this.query = v;
    }

    public String getTimeAxis() {
        return timeAxis;
    }

    public void setTimeAxis(String v) {
        this.timeAxis = v;
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
}
