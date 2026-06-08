/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * Body for {@code POST /saiku/api/ai/forecast} (saiku#908).
 *
 * <pre>{@code
 * {
 *   "query": { ...AiQueryRequest... },
 *   "method": "ets" | "arima" | "prophet",
 *   "horizon": 12,
 *   "timeAxis": "[Time].[Time].[Month]",
 *   "confidence": 0.95
 * }
 * }</pre>
 *
 * <p>{@code query} is executed through the same path {@code /ai/query} uses; the
 * numeric measure series are extracted in time order and each is projected
 * {@code horizon} steps by the chosen {@code method}. {@code confidence} is the
 * prediction-interval level (default 0.95); {@code horizon} defaults to 6.
 */
public class AiForecastRequest {

    private AiQueryRequest query;
    private String method;
    private Integer horizon;
    private String timeAxis;
    private Double confidence;

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

    public Integer getHorizon() {
        return horizon;
    }

    public void setHorizon(Integer v) {
        this.horizon = v;
    }

    public String getTimeAxis() {
        return timeAxis;
    }

    public void setTimeAxis(String v) {
        this.timeAxis = v;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double v) {
        this.confidence = v;
    }
}
