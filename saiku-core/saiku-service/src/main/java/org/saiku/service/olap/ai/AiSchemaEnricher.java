/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.Map;

/**
 * Apply an {@link AiSchemaEnrichment} overlay to an {@link AiSchema}.
 * The enricher only sets {@code displayName} fields and copies the
 * suggestion list — canonical {@code name}/{@code uniqueName} fields are
 * never touched, so validation still resolves the agent's name set
 * exactly as before enrichment.
 */
public class AiSchemaEnricher {

    public void apply(AiSchema schema, AiSchemaEnrichment overlay) {
        if (schema == null || overlay == null) return;

        if (overlay.getRenames() != null) {
            for (Map.Entry<String, String> e : overlay.getRenames().entrySet()) {
                applyRename(schema, e.getKey(), e.getValue());
            }
        }

        if (overlay.getAnnotations() != null) {
            for (Map.Entry<String, Map<String, String>> e :
                    overlay.getAnnotations().entrySet()) {
                applyAnnotations(schema, e.getKey(), e.getValue());
            }
        }

        // After all measure/level synonyms have been merged onto typed fields,
        // register them into the existing alias maps so AiSchemaConverter resolves
        // synonyms identically to displayName aliases.
        registerSynonymAliases(schema);

        if (overlay.getSuggestions() != null && !overlay.getSuggestions().isEmpty()) {
            schema.suggestions.addAll(overlay.getSuggestions());
        }
    }

    /**
     * Apply parsed {@code saiku.semantic.*} values from the overlay onto the matching
     * measure or level identified by the slash-path. Overlay always wins on conflict —
     * a present value replaces the XML-projected one; an absent key leaves the existing
     * field untouched.
     */
    private static void applyAnnotations(AiSchema schema, String path, Map<String, String> raw) {
        if (path == null || raw == null || raw.isEmpty()) return;
        String[] parts = path.split("\\.");

        if (parts.length == 2 && "measures".equals(parts[0])) {
            AiSchema.Measure m = schema.measures.get(AiSchema.key(parts[1]));
            if (m == null) return;
            SemanticAnnotationParser.MeasureAnnotations ann = SemanticAnnotationParser.parseMeasure(raw);
            if (ann.description != null) m.description = ann.description;
            if (!ann.synonyms.isEmpty()) m.synonyms = ann.synonyms;
            if (ann.unit != null) m.unit = ann.unit;
            if (ann.currency != null) m.currency = ann.currency;
            if (ann.aggregationKind != null) m.aggregationKind = ann.aggregationKind;
            return;
        }

        if (parts.length == 2 && "dimensions".equals(parts[0])) {
            AiSchema.Dimension d = schema.dimensions.get(AiSchema.key(parts[1]));
            if (d == null) return;
            SemanticAnnotationParser.DimensionAnnotations ann = SemanticAnnotationParser.parseDimension(raw);
            if (ann.description != null) d.description = ann.description;
            if (!ann.synonyms.isEmpty()) d.synonyms = ann.synonyms;
            return;
        }

        if (parts.length == 6
                && "dimensions".equals(parts[0])
                && "hierarchies".equals(parts[2])
                && "levels".equals(parts[4])) {
            AiSchema.Dimension d = schema.dimensions.get(AiSchema.key(parts[1]));
            if (d == null) return;
            AiSchema.Hierarchy h = d.hierarchies.get(AiSchema.key(parts[3]));
            if (h == null) return;
            AiSchema.Level l = h.levels.get(AiSchema.key(parts[5]));
            if (l == null) return;
            SemanticAnnotationParser.LevelAnnotations ann = SemanticAnnotationParser.parseLevel(raw);
            if (ann.description != null) l.description = ann.description;
            if (!ann.synonyms.isEmpty()) l.synonyms = ann.synonyms;
            if (ann.cardinality != null) l.cardinality = ann.cardinality;
            if (ann.grain != null) l.grain = ann.grain;
            if (!ann.requiredFilters.isEmpty()) l.requiredFilters = ann.requiredFilters;
        }
    }

    /**
     * Register every synonym on every measure / level into the appropriate alias map.
     * Idempotent: re-registering the same (synonym → canonical) is a no-op. First write
     * wins on cross-measure collisions — the second registration is logged downstream
     * by callers that care.
     */
    /** Package-visible so {@code OlapAiCubeMetadataService.buildSchema} can register
     *  XML-sourced synonyms even when no Phase-3 overlay is configured (saiku#818). */
    static void registerSynonymAliases(AiSchema schema) {
        for (Map.Entry<String, AiSchema.Measure> me : schema.measures.entrySet()) {
            String canonical = me.getKey();
            for (String syn : me.getValue().synonyms) {
                String synKey = AiSchema.key(syn);
                schema.measureAliases.putIfAbsent(synKey, canonical);
            }
        }
        for (Map.Entry<String, AiSchema.Dimension> de : schema.dimensions.entrySet()) {
            String dimCanonical = de.getKey();
            AiSchema.Dimension d = de.getValue();
            // saiku#818 follow-up: register dim synonyms too.
            for (String syn : d.synonyms) {
                schema.dimensionAliases.putIfAbsent(AiSchema.key(syn), dimCanonical);
            }
            for (Map.Entry<String, AiSchema.Hierarchy> he : d.hierarchies.entrySet()) {
                String hierCanonical = he.getKey();
                AiSchema.Hierarchy h = he.getValue();
                for (Map.Entry<String, AiSchema.Level> le : h.levels.entrySet()) {
                    String levelCanonical = le.getKey();
                    for (String syn : le.getValue().synonyms) {
                        String synKey = AiSchema.key(syn);
                        h.levelAliases.putIfAbsent(synKey, levelCanonical);
                        // saiku#818 follow-up: also surface a flat top-level view
                        // so an agent gets the same overview for levels that
                        // measureAliases/dimensionAliases already give. Same-named
                        // levels across hierarchies (Time/Time/Quarter +
                        // Time/Fiscal/Quarter) append rather than silently dropping.
                        java.util.List<AiSchema.LevelAliasTarget> targets =
                                schema.levelAliases.computeIfAbsent(synKey, k -> new java.util.ArrayList<>());
                        // De-dupe — if the same synonym→target tuple appears via
                        // both XML annotation and overlay, only register once.
                        boolean exists = false;
                        for (AiSchema.LevelAliasTarget t : targets) {
                            if (dimCanonical.equals(t.dimension)
                                    && hierCanonical.equals(t.hierarchy)
                                    && levelCanonical.equals(t.level)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            targets.add(new AiSchema.LevelAliasTarget(dimCanonical, hierCanonical, levelCanonical));
                        }
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private static void applyRename(AiSchema schema, String path, String displayName) {
        if (path == null || displayName == null) return;
        String[] parts = path.split("\\.");
        if (parts.length == 0) return;

        if (parts.length == 2 && "measures".equals(parts[0])) {
            String canonicalKey = AiSchema.key(parts[1]);
            AiSchema.Measure m = schema.measures.get(canonicalKey);
            if (m != null) {
                m.displayName = displayName;
                schema.measureAliases.put(AiSchema.key(displayName), canonicalKey);
            }
            return;
        }

        if (parts.length >= 2 && "dimensions".equals(parts[0])) {
            String dimKey = AiSchema.key(parts[1]);
            AiSchema.Dimension d = schema.dimensions.get(dimKey);
            if (d == null) return;
            if (parts.length == 2) {
                d.displayName = displayName;
                schema.dimensionAliases.put(AiSchema.key(displayName), dimKey);
                return;
            }
            if (parts.length >= 4 && "hierarchies".equals(parts[2])) {
                String hierKey = AiSchema.key(parts[3]);
                AiSchema.Hierarchy h = d.hierarchies.get(hierKey);
                if (h == null) return;
                if (parts.length == 4) {
                    h.displayName = displayName;
                    d.hierarchyAliases.put(AiSchema.key(displayName), hierKey);
                    return;
                }
                if (parts.length == 6 && "levels".equals(parts[4])) {
                    String levelKey = AiSchema.key(parts[5]);
                    AiSchema.Level l = h.levels.get(levelKey);
                    if (l != null) {
                        l.displayName = displayName;
                        h.levelAliases.put(AiSchema.key(displayName), levelKey);
                    }
                }
            }
        }
    }
}
