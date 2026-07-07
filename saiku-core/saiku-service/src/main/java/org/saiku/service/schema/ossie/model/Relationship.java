/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Ossie {@code Relationship} — many-to-one foreign-key link between two datasets. The order of
 * {@code from_columns} must correspond to {@code to_columns} for composite keys; the exporter
 * enforces that when it builds relationships from Mondrian {@code <DimensionUsage foreignKey="…">}
 * or {@code MeasureGroup} link declarations.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Relationship {
    private String name;

    /** The dataset on the many side of the relationship (e.g. the fact table). */
    private String from;

    /** The dataset on the one side (e.g. the dimension table). */
    private String to;

    @JsonProperty("from_columns")
    private List<String> fromColumns = new ArrayList<>();

    @JsonProperty("to_columns")
    private List<String> toColumns = new ArrayList<>();

    @JsonProperty("ai_context")
    private AiContext aiContext;

    @JsonProperty("custom_extensions")
    private List<CustomExtension> customExtensions = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String v) {
        this.from = v;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String v) {
        this.to = v;
    }

    public List<String> getFromColumns() {
        return fromColumns;
    }

    public void setFromColumns(List<String> v) {
        this.fromColumns = v == null ? new ArrayList<>() : v;
    }

    public List<String> getToColumns() {
        return toColumns;
    }

    public void setToColumns(List<String> v) {
        this.toColumns = v == null ? new ArrayList<>() : v;
    }

    public AiContext getAiContext() {
        return aiContext == null || aiContext.isEmpty() ? null : aiContext;
    }

    public void setAiContext(AiContext v) {
        this.aiContext = v;
    }

    public List<CustomExtension> getCustomExtensions() {
        return customExtensions;
    }

    public void setCustomExtensions(List<CustomExtension> v) {
        this.customExtensions = v == null ? new ArrayList<>() : v;
    }
}
