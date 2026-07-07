/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ossie {@code Dimension} — currently a single-flag marker for whether a field is a time dimension
 * (drives temporal-filter UX on the consumer side). Named DimensionMeta here to avoid clashing
 * with our existing {@code Dimension} DTOs in the Saiku codebase.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DimensionMeta {
    @com.fasterxml.jackson.annotation.JsonProperty("is_time")
    private Boolean isTime;

    public DimensionMeta() {}

    public DimensionMeta(Boolean isTime) {
        this.isTime = isTime;
    }

    public Boolean getIsTime() {
        return isTime;
    }

    public void setIsTime(Boolean v) {
        this.isTime = v;
    }
}
