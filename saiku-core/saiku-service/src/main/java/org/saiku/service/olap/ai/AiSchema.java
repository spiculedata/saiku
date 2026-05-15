/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed schema snapshot used by {@link AiSchemaConverter} for name
 * validation. Built once per cube (cached) from the live olap4j metadata
 * — keeps the converter pure / test-friendly by removing the olap4j
 * dependency from the conversion code.
 *
 * <p>All lookup maps key by *case-insensitive* lowercased name; lookups
 * resolve to canonical (case-preserved) names for MDX construction.
 */
public class AiSchema {

    public static class Measure {
        public final String name;
        public final String uniqueName;
        /** Optional LLM/operator-supplied display label. Validation always
         *  uses {@link #name}; {@code displayName} is presentation-only. */
        public String displayName;
        /** Optional free-text description (from olap4j Member.getDescription()
         *  or the schema-gen sidecar). Helps the LLM ground its choices. */
        public String description;

        public Measure(String name, String uniqueName) {
            this.name = name;
            this.uniqueName = uniqueName;
        }
    }

    public static class Level {
        public final String name;
        public final String uniqueName;
        public String displayName;
        public String description;
        /** Up to ~5 actual member captions at this level. Massive help for
         *  the LLM: stops it hallucinating member names like
         *  {@code "[Time].[2099]"} that don't exist. */
        public java.util.List<String> sampleMembers = new java.util.ArrayList<>();

        public Level(String name, String uniqueName) {
            this.name = name;
            this.uniqueName = uniqueName;
        }
    }

    public static class Hierarchy {
        public final String name;
        public final String uniqueName;
        public String displayName;
        public String description;
        public final Map<String, Level> levels = new LinkedHashMap<>();
        /** Reverse lookup: lower-cased {@code displayName} → canonical level key.
         *  Populated by {@link AiSchemaEnricher} so the converter can resolve
         *  agent requests that use either the canonical name or the
         *  display name. */
        public final Map<String, String> levelAliases = new LinkedHashMap<>();

        public Hierarchy(String name, String uniqueName) {
            this.name = name;
            this.uniqueName = uniqueName;
        }
    }

    public static class Dimension {
        public final String name;
        public final String uniqueName;
        public String displayName;
        public String description;
        public final Map<String, Hierarchy> hierarchies = new LinkedHashMap<>();
        /** display-name → canonical hierarchy key. */
        public final Map<String, String> hierarchyAliases = new LinkedHashMap<>();

        public Dimension(String name, String uniqueName) {
            this.name = name;
            this.uniqueName = uniqueName;
        }
    }

    private final String cubeId;
    private final String cubeName;
    private final String cubeUniqueName;
    public final Map<String, Measure> measures = new LinkedHashMap<>();
    public final Map<String, Dimension> dimensions = new LinkedHashMap<>();
    /** display-name → canonical measure key (Phase 3 enrichment alias). */
    public final Map<String, String> measureAliases = new LinkedHashMap<>();
    /** display-name → canonical dimension key (Phase 3 enrichment alias). */
    public final Map<String, String> dimensionAliases = new LinkedHashMap<>();
    /** Phase 3: LLM/schema-generator suggestions overlaid on the schema. */
    public java.util.List<AiSchemaSuggestion> suggestions = new java.util.ArrayList<>();
    /** Optional free-text cube-level description. */
    public String description;
    /** A handful of ready-made AiQueryRequest examples for this cube.
     *  Lets an LLM see a working shape before constructing its own. */
    public java.util.List<AiQueryRequest> examples = new java.util.ArrayList<>();
    /** JSON Schema (draft 2020-12) for AiQueryRequest. Embedded once
     *  per schema response so the LLM can validate its request shape
     *  without a separate round-trip. Set at construction time by
     *  {@link OlapAiCubeMetadataService}. */
    public java.util.Map<String, Object> requestSchema;

    public AiSchema(String cubeId, String cubeName, String cubeUniqueName) {
        this.cubeId = cubeId;
        this.cubeName = cubeName;
        this.cubeUniqueName = cubeUniqueName;
    }

    public String getCubeId() {
        return cubeId;
    }

    public String getCubeName() {
        return cubeName;
    }

    public String getCubeUniqueName() {
        return cubeUniqueName;
    }

    public static String key(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }
}
