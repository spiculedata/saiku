/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Ossie {@code AIContext} — additional context for AI tools attached to any Ossie element (model,
 * dataset, field, metric, relationship). The spec keeps the shape loose ("string or object") for
 * forward-compat with future keys; we pin the two fields that matter today ({@code instructions} +
 * {@code synonyms}) and let anything else round-trip through Jackson's default handling.
 *
 * <p>Direct 1:1 with Saiku's {@code saiku.semantic.description} (→ {@code instructions}) and
 * {@code saiku.semantic.synonyms} (→ {@code synonyms}) annotation keys.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class AiContext {
    private String instructions;
    private List<String> synonyms = new ArrayList<>();

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String v) {
        this.instructions = v;
    }

    public List<String> getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(List<String> v) {
        this.synonyms = v == null ? new ArrayList<>() : v;
    }

    public boolean isEmpty() {
        return (instructions == null || instructions.isBlank()) && (synonyms == null || synonyms.isEmpty());
    }
}
