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

    /**
     * Ontology-mapping envelopes as defined by Apache Ossie's flights example. Each mapping
     * carries a nested {@code semantic_model:} — a common shape when the document originates
     * from an ontology-first modelling tool. Saiku doesn't process the ontology block itself
     * (concepts / relationships / verbalizes are out of scope for the SQL surface), but the
     * nested semantic_model is a valid Ossie model and we surface it via
     * {@link #getEffectiveSemanticModels()}.
     */
    @JsonProperty("ontology_mappings")
    private List<OntologyMapping> ontologyMappings = new ArrayList<>();

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

    public List<OntologyMapping> getOntologyMappings() {
        return ontologyMappings;
    }

    public void setOntologyMappings(List<OntologyMapping> v) {
        this.ontologyMappings = v == null ? new ArrayList<>() : v;
    }

    /**
     * Return every semantic model the document publishes — the top-level {@code semantic_model:}
     * entries plus any nested inside {@code ontology_mappings[*].semantic_model:}. Consumers
     * (discover service, YAML reader) should use this rather than {@link #getSemanticModel()}
     * so ontology-nested Ossie docs round-trip cleanly.
     */
    public List<SemanticModel> getEffectiveSemanticModels() {
        List<SemanticModel> out = new ArrayList<>(semanticModel);
        for (OntologyMapping m : ontologyMappings) {
            if (m.getSemanticModel() != null) out.add(m.getSemanticModel());
        }
        return out;
    }

    /** Envelope for one entry under {@code ontology_mappings:}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class OntologyMapping {
        private String name;
        private String description;

        @JsonProperty("semantic_model")
        private SemanticModel semanticModel;

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

        public SemanticModel getSemanticModel() {
            return semanticModel;
        }

        public void setSemanticModel(SemanticModel v) {
            this.semanticModel = v;
        }
    }
}
