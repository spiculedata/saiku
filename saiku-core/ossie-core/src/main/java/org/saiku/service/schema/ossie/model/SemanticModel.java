/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/** Ossie {@code SemanticModel} — one cube's worth of definitions in Ossie shape. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SemanticModel {
    private String name;
    private String description;

    @JsonProperty("ai_context")
    private AiContext aiContext;

    /**
     * Ossie's core schema declares {@code datasets} as REQUIRED on every semantic model, so we
     * override the class-level {@code @JsonInclude(NON_EMPTY)} here — even an empty datasets
     * array must serialise as {@code datasets: []}, otherwise schema validation fails with
     * "required property 'datasets' not found" for cubes we couldn't map (e.g. Mondrian 4
     * MeasureGroup-shaped cubes that our first-cut converter doesn't recognise yet).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<Dataset> datasets = new ArrayList<>();

    private List<Relationship> relationships = new ArrayList<>();
    private List<Metric> metrics = new ArrayList<>();

    @JsonProperty("custom_extensions")
    private List<CustomExtension> customExtensions = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
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

    public List<Dataset> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<Dataset> v) {
        this.datasets = v == null ? new ArrayList<>() : v;
    }

    public List<Relationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<Relationship> v) {
        this.relationships = v == null ? new ArrayList<>() : v;
    }

    public List<Metric> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<Metric> v) {
        this.metrics = v == null ? new ArrayList<>() : v;
    }

    public List<CustomExtension> getCustomExtensions() {
        return customExtensions;
    }

    public void setCustomExtensions(List<CustomExtension> v) {
        this.customExtensions = v == null ? new ArrayList<>() : v;
    }
}
