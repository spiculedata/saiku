/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level Ossie document — one or more semantic models plus the spec version they conform to.
 *
 * <p>The {@code osi-schema.json} at {@code apache/ossie} requires {@code version} and {@code
 * semantic_model} at the root. We pin the version to the current Ossie draft; consumers can pick
 * up the value from the emitted YAML to route to the right parser.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"version", "semantic_model"})
public class OssieDocument {
    /**
     * Ossie spec version this document conforms to. Bump when the exporter starts emitting
     * against a newer spec — the value here MUST match one the consumer knows about, so the
     * exporter's constant is deliberately conservative.
     */
    public static final String OSSIE_SPEC_VERSION = "0.2.0.dev0";

    private String version = OSSIE_SPEC_VERSION;

    /**
     * Ossie's core schema declares {@code semantic_model} as required, so we override the
     * class-level {@code NON_EMPTY} — even a converter run that skipped every cube (e.g. a
     * Mondrian schema containing only MG-shape or virtual cubes we don't yet recognise) must
     * emit {@code semantic_model: []} rather than dropping the field.
     */
    @JsonProperty("semantic_model")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<SemanticModel> semanticModel = new ArrayList<>();

    public String getVersion() {
        return version;
    }

    public void setVersion(String v) {
        this.version = v;
    }

    public List<SemanticModel> getSemanticModel() {
        return semanticModel;
    }

    public void setSemanticModel(List<SemanticModel> v) {
        this.semanticModel = v == null ? new ArrayList<>() : v;
    }
}
