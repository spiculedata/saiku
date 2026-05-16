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
        /** Whether the measure should appear in analyst-facing UIs. Mirrors
         *  the schema-level {@code visible="false"} attribute on
         *  {@code <CalculatedMember>} (saiku#778). Defaults to true so
         *  existing snapshots and tests don't shift; hidden measures are
         *  surfaced on the wire so admin clients can opt in to seeing them. */
        public Boolean visible = true;
        /** saiku#818: alternate human-friendly names the agent can use in
         *  {@code AiQueryRequest.measures[].name}. Registered into
         *  {@link AiSchema#measureAliases} for input resolution, and also
         *  surfaced as a distinct list on the {@code /ai/schema} response. */
        public java.util.List<String> synonyms = new java.util.ArrayList<>();
        /** saiku#818: free-text unit ({@code "USD"}, {@code "hours"},
         *  {@code "count"}, {@code "percent"}). Disambiguates "is this
         *  dollars or units?" without inferring from the format string. */
        public String unit;
        /** saiku#818: ISO 4217 currency code when {@link #unit} is monetary. */
        public String currency;
        /** saiku#818: how the measure aggregates — {@code sum | count |
         *  distinct-count | non-additive}. Tells the agent whether
         *  TopCount-style ranking makes sense at the requested grain. */
        public String aggregationKind;

        public Measure(String name, String uniqueName) {
            this.name = name;
            this.uniqueName = uniqueName;
        }
    }

    /**
     * Caption + MDX unique-name pair for a single member sample. Pre-built
     * so an agent can copy {@link #uniqueName} straight into the
     * {@code filters[].members} array without having to assemble
     * {@code level.uniqueName + ".&[" + caption + "]"} itself.
     */
    public static class MemberSample {
        public final String caption;
        public final String uniqueName;
        /** saiku#818: optional free-text description for the opaque-code case
         *  (e.g. {@code "M"} → {@code "Married"}). Null when no description
         *  is available — most members have self-explanatory captions. */
        public String description;

        public MemberSample() {
            this.caption = null;
            this.uniqueName = null;
        }

        public MemberSample(String caption, String uniqueName) {
            this.caption = caption;
            this.uniqueName = uniqueName;
        }

        public String getCaption() {
            return caption;
        }

        public String getUniqueName() {
            return uniqueName;
        }

        public String getDescription() {
            return description;
        }
    }

    public static class Level {
        public final String name;
        public final String uniqueName;
        public String displayName;
        public String description;
        /** Up to ~5 actual members at this level — caption + unique name,
         *  deduped by caption. Massive help for the LLM: stops it
         *  hallucinating member names like {@code "[Time].[2099]"} that
         *  don't exist, and lets it copy-paste the unique name directly
         *  into a filter rather than constructing it. */
        public java.util.List<MemberSample> sampleMembers = new java.util.ArrayList<>();
        /** saiku#818: alternate names accepted by the converter for this level. */
        public java.util.List<String> synonyms = new java.util.ArrayList<>();
        /** saiku#818: cardinality hint — {@code low | medium | high}. Drives
         *  whether the agent should pre-filter before crossjoining. */
        public String cardinality;
        /** saiku#818: time-grain tag for time levels — {@code year |
         *  quarter | month | week | day | hour | minute}. Lets the agent map
         *  user utterances like "quarterly" directly to a level. */
        public String grain;
        /** saiku#818: pre-flight filter requirements. If any of these
         *  (hierarchy, level) pairs are missing from {@code AiQueryRequest.filters}
         *  when this level is touched, {@code AiSchemaConverter} returns a
         *  {@code VALIDATION_ERROR} 400 with the full list as {@code available}.
         *  Empty list = no requirement (the default for unannotated cubes). */
        public java.util.List<RequiredFilter> requiredFilters = new java.util.ArrayList<>();

        /** Transient build-time signal: the sample-member fetch returned at least
         *  one row without throwing, so the level is already proven queryable
         *  and {@code pruneUnqueryable} can skip the redundant probe call.
         *  Not serialised — recomputed per buildSchema. */
        public transient boolean queryableProven;

        public Level(String name, String uniqueName) {
            this.name = name;
            this.uniqueName = uniqueName;
        }
    }

    /**
     * Declares a (hierarchy, level) filter that must be present in any
     * {@code AiQueryRequest} which touches a level carrying this requirement.
     * Surfaces in the typed {@code AiSchema.Level.requiredFilters} list and
     * is enforced by {@code AiSchemaConverter.toMdx} (saiku#818). The shape
     * mirrors {@code AiAxisSelection.hierarchy} + {@code .level} so the
     * validation error envelope can point at the exact filter to add.
     */
    public static class RequiredFilter {
        public String hierarchy;
        public String level;

        public RequiredFilter() {}

        public RequiredFilter(String hierarchy, String level) {
            this.hierarchy = hierarchy;
            this.level = level;
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
    /** Canonical (resolver-matched) cube reference. Populated by
     *  {@link OlapAiCubeMetadataService#buildSchema} from the actual
     *  {@link org.saiku.olap.dto.SaikuCube} returned by the discover service,
     *  so connectionName/catalog/schema/cubeName all reflect Mondrian's
     *  canonical case even when the agent posted lowercase or mixed-case
     *  values. Used by {@link AiSchemaConverter#toSaikuCube} as the
     *  authoritative source for downstream cube lookup — closes saiku#811
     *  by removing the agent-case leak that produced "Cannot get native
     *  cube" 500s on case-mismatched cube refs.
     *
     *  <p>Nullable for test fixtures that construct AiSchema directly via
     *  the 3-arg constructor (they don't go through the metadata service).
     */
    public AiCubeRef canonicalCube;

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
        // Trim + lower-case the lookup key. Trim makes whitespace-padded
        // names ("  Product  ") match their canonical form — agents that
        // accidentally include trailing/leading whitespace shouldn't hit a
        // 400 when the name is otherwise correct. Map keys are populated
        // via this same method on insert, so both sides stay symmetric.
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
