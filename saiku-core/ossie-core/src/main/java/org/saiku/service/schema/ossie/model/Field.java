/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/** Ossie {@code Field} — row-level attribute available on a dataset for grouping / filtering. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Field {
    private String name;
    private Expression expression;
    private DimensionMeta dimension;
    private String label;
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

    public DimensionMeta getDimension() {
        return dimension;
    }

    public void setDimension(DimensionMeta v) {
        this.dimension = v;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String v) {
        this.label = v;
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
