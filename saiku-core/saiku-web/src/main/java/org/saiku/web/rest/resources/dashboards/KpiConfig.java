/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-tile KPI configuration. Mirrors the {@code KpiConfig} TS shape on
 * the UI side. The server treats this as an opaque-ish bag — Jackson
 * round-trips the JSON and the UI renderer interprets every field.
 *
 * <p>All fields are nullable so a partially-configured KPI tile saves
 * cleanly (and the UI editor can write defaults back without forcing
 * every field present).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KpiConfig {

    /** Measure unique name, e.g. {@code [Measures].[Unit Sales]}. */
    public String measure;

    /** Cube-side caption shown under the number when no comparison is set. */
    public String measureCaption;

    /** {@code number | currency | percent | custom}. */
    public String format;

    /** Custom format pattern when {@link #format} is {@code custom}. */
    public String customFormat;

    /** {@code none | prior-period | target}. */
    public String comparison;

    /** Target value for {@code target} comparison. */
    public Double target;

    /** Time level for prior-period delta and / or sparkline. */
    public TimeLevelRef timeLevel;

    /** Show a small sparkline of the time series under the number. */
    public Boolean sparkline;

    /** Threshold bands for colour-coding the main number. */
    public Thresholds thresholds;

    /** {@code higher-is-better | lower-is-better}. */
    public String direction;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TimeLevelRef {
        public String dimension;
        public String hierarchy;
        public String level;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Thresholds {
        public Double red;
        public Double yellow;
        public Double green;
    }
}
