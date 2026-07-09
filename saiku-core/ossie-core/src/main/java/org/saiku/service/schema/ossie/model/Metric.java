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
 * Ossie {@code Metric} — a quantifiable measure defined as an aggregate expression on fields from
 * one or more datasets. Saiku's exporter emits both an ANSI_SQL dialect (best-effort from the
 * Mondrian {@code aggregator} + {@code column} attributes) AND an MDX dialect (verbatim
 * {@code <Measure>} unique name / calculated-member expression).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Metric {
    private String name;
    private Expression expression;
    private String description;

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

    public Expression getExpression() {
        return expression;
    }

    public void setExpression(Expression v) {
        this.expression = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = v;
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
