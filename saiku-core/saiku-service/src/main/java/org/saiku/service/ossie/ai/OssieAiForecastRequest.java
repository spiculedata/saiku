/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire-format request for {@code POST /ai/ossie/forecast}. Mirrors the MDX-side
 * {@code AiForecastRequest}. The server executes {@link #query}, extracts one numeric metric
 * per requested measure, extends the series by {@link #horizon} steps using the requested
 * {@link #method}, and returns the projected data alongside prediction intervals.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OssieAiForecastRequest {

    private OssieAiQueryRequest query;

    /** {@code dataset.field} key of the time axis to project along. */
    private String timeAxis;

    /** Forecast method identifier (see {@code Forecasters.forMethod}). */
    private String method;

    /** How many future points to project. Defaults to 4 in the resource layer. */
    private Integer horizon;

    /** Optional prediction-interval width (e.g. 0.95 = 95% PI). */
    private Double interval;

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

    public Integer getHorizon() {
        return horizon;
    }

    public void setHorizon(Integer v) {
        this.horizon = v;
    }

    public Double getInterval() {
        return interval;
    }

    public void setInterval(Double v) {
        this.interval = v;
    }
}
