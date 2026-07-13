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
        /** saiku#902: PII marker. {@code true} when the schema author tagged
         *  {@code saiku.semantic.pii=true} on the measure. Drives:
         *  <ul>
         *    <li>the agent-facing /ai/schema projection (caption, description,
         *        synonyms stripped — see {@link AiSchema#toAgentView()});</li>
         *    <li>drillthrough {@code returns=} refusal (the column may not be
         *        projected back).</li>
         *  </ul>
         *  The internal in-memory AiSchema keeps everything for MDX
         *  construction; redaction happens at the JSON boundary. */
        public boolean pii;

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
        /** saiku#902: PII marker. {@code true} when the schema author tagged
         *  {@code saiku.semantic.pii=true} on the level. Drives the
         *  agent-facing /ai/schema projection (see {@link AiSchema#toAgentView()})
         *  and drillthrough {@code returns=} refusal. The internal in-memory
         *  AiSchema keeps captions + sample members + uniqueName intact so
         *  MDX construction still works; redaction happens at the JSON
         *  boundary so the validator can still match agent-supplied names. */
        public boolean pii;

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
        /** saiku#818 follow-up: alternate names the agent can use in any
         *  {@code AiQueryRequest} field that names a dimension. Registered into
         *  {@link AiSchema#dimensionAliases} for input resolution and also
         *  surfaced as a distinct list on the {@code /ai/schema} response. */
        public java.util.List<String> synonyms = new java.util.ArrayList<>();

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

    /**
     * Triple identifying one level in the cube. Used as a value in
     * {@link #levelAliases} so a single synonym can resolve to multiple levels
     * across hierarchies (saiku#818 follow-up — Quarter exists in both
     * {@code Time/Time} and {@code Time/Fiscal}, "quarterly" resolves to both).
     * All three fields are canonical (lower-cased) keys, suitable for direct
     * map lookup against {@link #dimensions} / hierarchies / levels.
     */
    public static class LevelAliasTarget {
        public String dimension;
        public String hierarchy;
        public String level;

        public LevelAliasTarget() {}

        public LevelAliasTarget(String dimension, String hierarchy, String level) {
            this.dimension = dimension;
            this.hierarchy = hierarchy;
            this.level = level;
        }
    }

    public final Map<String, Measure> measures = new LinkedHashMap<>();
    public final Map<String, Dimension> dimensions = new LinkedHashMap<>();
    /** display-name → canonical measure key (Phase 3 enrichment alias). */
    public final Map<String, String> measureAliases = new LinkedHashMap<>();
    /** display-name → canonical dimension key (Phase 3 enrichment alias). */
    public final Map<String, String> dimensionAliases = new LinkedHashMap<>();
    /** saiku#818 follow-up: flat top-level view of level synonyms.
     *  Maps each synonym → list of every level it resolves to. The list lets a
     *  single synonym ("quarterly") cover same-named levels across multiple
     *  hierarchies (Time/Time/Quarter + Time/Fiscal/Quarter); the agent
     *  disambiguates using {@code dimension}/{@code hierarchy} on each target.
     *  Resolution itself still happens against
     *  {@code Hierarchy.levelAliases} (per-hierarchy scope) — this map is
     *  purely a read-side overview for the {@code /ai/schema} response. */
    public final Map<String, java.util.List<LevelAliasTarget>> levelAliases = new LinkedHashMap<>();
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

    /**
     * saiku#902. Project the schema into an agent-facing redacted view.
     *
     * <p>The in-memory schema keeps captions, sample members, descriptions, and
     * synonyms for every level + measure — including ones marked
     * {@code pii=true} — because the internal MDX builder + validator need them
     * for name resolution and member-by-caption lookup. The JSON response sent
     * to the agent, on the other hand, must NOT carry those values when the
     * schema author tagged the column PII.
     *
     * <p>This method returns a shallow-but-projection-correct copy where, for
     * every PII-flagged measure / level:
     * <ul>
     *   <li>{@code displayName}, {@code description}, {@code synonyms} are
     *       stripped.</li>
     *   <li>{@code sampleMembers} is replaced with a single
     *       {@code [REDACTED]} sentinel (the structural shape stays "this is a
     *       level with members" — preserves the agent's mental model — but no
     *       actual caption leaks).</li>
     *   <li>{@code name} and {@code uniqueName} are KEPT so the agent can still
     *       see the schema's shape; MDX referencing those names is what the
     *       drillthrough refusal (separate path) blocks.</li>
     *   <li>{@code pii=true} is set so the agent knows to treat the slot as
     *       opaque without having to grep for stripped values.</li>
     * </ul>
     *
     * <p>Aliases that point at PII levels / measures are removed from the
     * top-level alias maps so an alias like "social_security_number" can't be
     * used to bypass the strip.
     *
     * <p>Returns a new {@link AiSchema} instance; the caller's original is
     * untouched. Safe to call repeatedly.
     */
    public AiSchema toAgentView() {
        AiSchema view = new AiSchema(this.cubeId, this.cubeName, this.cubeUniqueName);
        view.description = this.description;
        view.examples = this.examples;
        view.suggestions = this.suggestions;
        view.requestSchema = this.requestSchema;

        for (Map.Entry<String, Measure> e : this.measures.entrySet()) {
            view.measures.put(e.getKey(), redactMeasure(e.getValue()));
        }
        for (Map.Entry<String, Dimension> e : this.dimensions.entrySet()) {
            view.dimensions.put(e.getKey(), redactDimension(e.getValue()));
        }
        // Strip aliases that resolve to a PII-flagged measure / level — the
        // canonical key map above has the pii flag on it, so we can decide
        // membership cheaply.
        for (Map.Entry<String, String> e : this.measureAliases.entrySet()) {
            Measure target = view.measures.get(e.getValue());
            if (target != null && !target.pii) view.measureAliases.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : this.dimensionAliases.entrySet()) {
            view.dimensionAliases.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, java.util.List<LevelAliasTarget>> e : this.levelAliases.entrySet()) {
            java.util.List<LevelAliasTarget> kept = new java.util.ArrayList<>();
            for (LevelAliasTarget t : e.getValue()) {
                if (!isPiiLevel(view, t)) kept.add(t);
            }
            if (!kept.isEmpty()) view.levelAliases.put(e.getKey(), kept);
        }
        return view;
    }

    private static Measure redactMeasure(Measure m) {
        if (!m.pii) return m;
        Measure r = new Measure(m.name, m.uniqueName);
        r.visible = m.visible;
        r.pii = true;
        r.aggregationKind = m.aggregationKind;
        // displayName / description / synonyms / unit / currency dropped.
        return r;
    }

    private static Dimension redactDimension(Dimension d) {
        Dimension copy = new Dimension(d.name, d.uniqueName);
        copy.displayName = d.displayName;
        copy.description = d.description;
        copy.synonyms = d.synonyms;
        for (Map.Entry<String, Hierarchy> e : d.hierarchies.entrySet()) {
            copy.hierarchies.put(e.getKey(), redactHierarchy(e.getValue()));
        }
        return copy;
    }

    private static Hierarchy redactHierarchy(Hierarchy h) {
        Hierarchy copy = new Hierarchy(h.name, h.uniqueName);
        copy.displayName = h.displayName;
        copy.description = h.description;
        for (Map.Entry<String, Level> e : h.levels.entrySet()) {
            copy.levels.put(e.getKey(), redactLevel(e.getValue()));
        }
        // Strip aliases that point at PII levels in this hierarchy so a
        // display-name lookup can't reach a redacted level.
        for (Map.Entry<String, String> e : h.levelAliases.entrySet()) {
            Level target = copy.levels.get(e.getValue());
            if (target != null && !target.pii) copy.levelAliases.put(e.getKey(), e.getValue());
        }
        return copy;
    }

    private static Level redactLevel(Level l) {
        if (!l.pii) return l;
        Level r = new Level(l.name, l.uniqueName);
        r.pii = true;
        r.cardinality = l.cardinality;
        r.grain = l.grain;
        r.requiredFilters = l.requiredFilters;
        // displayName / description / synonyms dropped; sampleMembers replaced
        // with a single [REDACTED] sentinel so the structural shape — "this is
        // a level with members" — survives without leaking captions.
        r.sampleMembers = java.util.List.of(new MemberSample("[REDACTED]", "[REDACTED]"));
        return r;
    }

    private static boolean isPiiLevel(AiSchema view, LevelAliasTarget t) {
        if (t == null || t.dimension == null || t.hierarchy == null || t.level == null) return false;
        Dimension d = view.dimensions.get(t.dimension);
        if (d == null) return false;
        Hierarchy h = d.hierarchies.get(t.hierarchy);
        if (h == null) return false;
        Level l = h.levels.get(t.level);
        return l != null && l.pii;
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
