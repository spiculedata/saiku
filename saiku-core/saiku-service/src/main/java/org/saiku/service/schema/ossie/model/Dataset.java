/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/** Ossie {@code Dataset} — a logical fact or dimension table plus its fields. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Dataset {
    private String name;
    private String source;

    @JsonProperty("primary_key")
    private List<String> primaryKey = new ArrayList<>();

    /** Ossie shape: array of arrays. Each inner array is a distinct unique-key definition. */
    @JsonProperty("unique_keys")
    private List<List<String>> uniqueKeys = new ArrayList<>();

    private String description;

    @JsonProperty("ai_context")
    private AiContext aiContext;

    private List<Field> fields = new ArrayList<>();

    @JsonProperty("custom_extensions")
    private List<CustomExtension> customExtensions = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String v) {
        this.source = v;
    }

    public List<String> getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(List<String> v) {
        this.primaryKey = v == null ? new ArrayList<>() : v;
    }

    public List<List<String>> getUniqueKeys() {
        return uniqueKeys;
    }

    public void setUniqueKeys(List<List<String>> v) {
        this.uniqueKeys = v == null ? new ArrayList<>() : v;
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

    public List<Field> getFields() {
        return fields;
    }

    public void setFields(List<Field> v) {
        this.fields = v == null ? new ArrayList<>() : v;
    }

    public List<CustomExtension> getCustomExtensions() {
        return customExtensions;
    }

    public void setCustomExtensions(List<CustomExtension> v) {
        this.customExtensions = v == null ? new ArrayList<>() : v;
    }
}
