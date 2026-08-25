/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.semantic.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Measure(String name, String column, String aggregator, String datatype, String formatString) {

    @JsonCreator
    public Measure(
            @JsonProperty("name") String name,
            @JsonProperty("column") String column,
            @JsonProperty("aggregator") String aggregator,
            @JsonProperty("datatype") String datatype,
            @JsonProperty("formatString") String formatString) {
        this.name = name;
        this.column = column;
        this.aggregator = aggregator == null ? "sum" : aggregator;
        this.datatype = datatype == null ? "Numeric" : datatype;
        this.formatString = formatString;
    }
}
