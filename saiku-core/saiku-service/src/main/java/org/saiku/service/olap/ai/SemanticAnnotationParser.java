/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses Mondrian-flavoured {@code <Annotation>} maps from the {@code saiku.semantic.*}
 * namespace into typed values that {@code AiSchema.Measure} / {@link AiSchema.Level} can
 * carry directly (saiku#818).
 *
 * <p>Single source of truth for parsing + enum validation. Used by the XML projection path
 * in {@code OlapAiCubeMetadataService.buildSchema}; the {@code .generated.json} overlay
 * carries pre-typed values via {@code AiSchemaEnrichment} so it does not go through this
 * parser.
 *
 * <p>Unknown enum values are silently dropped (logged at {@code WARN}). The contract is
 * "fail soft on the schema-author side, fail hard on the agent side" — we never 500 a
 * {@code /ai/schema} response because a schema author typo'd a value.
 */
public final class SemanticAnnotationParser {

    private static final Logger log = LoggerFactory.getLogger(SemanticAnnotationParser.class);

    /** Annotation-key prefix that gates inclusion. Keeps these distinct from any future
     *  Saiku annotation families (e.g. {@code saiku.governance.*}). */
    public static final String NAMESPACE = "saiku.semantic.";

    public static final List<String> AGGREGATION_KINDS =
            Collections.unmodifiableList(Arrays.asList("sum", "count", "distinct-count", "non-additive"));
    public static final List<String> CARDINALITIES =
            Collections.unmodifiableList(Arrays.asList("low", "medium", "high"));
    public static final List<String> GRAINS =
            Collections.unmodifiableList(Arrays.asList("year", "quarter", "month", "week", "day", "hour", "minute"));

    private static final String KEY_DESCRIPTION = NAMESPACE + "description";
    private static final String KEY_SYNONYMS = NAMESPACE + "synonyms";
    private static final String KEY_UNIT = NAMESPACE + "unit";
    private static final String KEY_CURRENCY = NAMESPACE + "currency";
    private static final String KEY_AGGREGATION_KIND = NAMESPACE + "aggregation_kind";
    private static final String KEY_CARDINALITY = NAMESPACE + "cardinality";
    private static final String KEY_GRAIN = NAMESPACE + "grain";
    private static final String KEY_REQUIRED_FILTERS = NAMESPACE + "required_filters";
    /** saiku#902. PII marker. Valid on Level + Measure. {@code true} flips the
     *  level/measure into "structurally present but opaque" mode in the
     *  agent-facing /ai/schema projection (captions, sample members, descriptions,
     *  synonyms are stripped) AND blocks drillthrough returns= referencing
     *  PII-flagged columns. */
    private static final String KEY_PII = NAMESPACE + "pii";

    private SemanticAnnotationParser() {}

    /** Parsed dimension-level annotations. saiku#818 follow-up: dimensions accept
     *  {@code description} + {@code synonyms} only (no aggregation-kind / cardinality —
     *  those live on measures and levels). */
    public static final class DimensionAnnotations {
        public String description;
        public List<String> synonyms = new ArrayList<>();
    }

    /** Parsed measure-level annotations. Fields default to {@code null} / empty list. */
    public static final class MeasureAnnotations {
        public String description;
        public List<String> synonyms = new ArrayList<>();
        public String unit;
        public String currency;
        public String aggregationKind;
        /** saiku#902 PII marker. {@code true} when the schema author tagged
         *  {@code saiku.semantic.pii=true} on the measure. */
        public boolean pii;
    }

    /** Parsed level-level annotations. Fields default to {@code null} / empty list. */
    public static final class LevelAnnotations {
        public String description;
        public List<String> synonyms = new ArrayList<>();
        public String cardinality;
        public String grain;
        public List<AiSchema.RequiredFilter> requiredFilters = new ArrayList<>();
        /** saiku#902 PII marker. {@code true} when the schema author tagged
         *  {@code saiku.semantic.pii=true} on the level. */
        public boolean pii;
    }

    public static DimensionAnnotations parseDimension(Map<String, String> annotations) {
        DimensionAnnotations out = new DimensionAnnotations();
        if (annotations == null) {
            return out;
        }
        out.description = trimToNull(annotations.get(KEY_DESCRIPTION));
        out.synonyms = parseCsvList(annotations.get(KEY_SYNONYMS));
        return out;
    }

    public static MeasureAnnotations parseMeasure(Map<String, String> annotations) {
        MeasureAnnotations out = new MeasureAnnotations();
        if (annotations == null) {
            return out;
        }
        out.description = trimToNull(annotations.get(KEY_DESCRIPTION));
        out.synonyms = parseCsvList(annotations.get(KEY_SYNONYMS));
        out.unit = trimToNull(annotations.get(KEY_UNIT));
        out.currency = trimToNull(annotations.get(KEY_CURRENCY));
        out.aggregationKind =
                validateEnum(annotations.get(KEY_AGGREGATION_KIND), AGGREGATION_KINDS, "aggregation_kind");
        out.pii = parseBoolean(annotations.get(KEY_PII));
        return out;
    }

    public static LevelAnnotations parseLevel(Map<String, String> annotations) {
        LevelAnnotations out = new LevelAnnotations();
        if (annotations == null) {
            return out;
        }
        out.description = trimToNull(annotations.get(KEY_DESCRIPTION));
        out.synonyms = parseCsvList(annotations.get(KEY_SYNONYMS));
        out.cardinality = validateEnum(annotations.get(KEY_CARDINALITY), CARDINALITIES, "cardinality");
        out.grain = validateEnum(annotations.get(KEY_GRAIN), GRAINS, "grain");
        out.requiredFilters = parseRequiredFilters(annotations.get(KEY_REQUIRED_FILTERS));
        out.pii = parseBoolean(annotations.get(KEY_PII));
        return out;
    }

    /** Spellings a schema author might reasonably use to mean "yes". */
    private static final List<String> AFFIRMATIVE = Arrays.asList("true", "yes", "y", "on", "1");

    /** Spellings that unambiguously mean "no". */
    private static final List<String> NEGATIVE = Arrays.asList("false", "no", "n", "off", "0");

    /**
     * Parse the {@code saiku.semantic.pii} flag (saiku#1849).
     *
     * <p>This used to match ONLY the literal {@code "true"}, so an author who wrote {@code pii=yes},
     * {@code pii=1} or {@code pii=on} got no masking at all — silently, with nothing logged. Those
     * are not typos; they are unambiguous ways of saying yes, and the flag strips values from AI
     * responses and blocks drillthrough {@code returns=} on the column, so the omission left
     * personal data flowing. They are now honoured.
     *
     * <p>Genuinely unrecognised values still resolve to {@code false}, preserving the deliberate
     * trade-off recorded with the original behaviour: for an analytics product, masking working data
     * on a slip is a worse everyday failure than a schema author having to correct a value. What is
     * new is that the slip no longer passes in silence — it is logged at WARN naming the value and
     * the accepted spellings, so it is discoverable rather than invisible.
     *
     * <p>Absent and blank stay {@code false}: the annotation's absence genuinely means "not PII",
     * which is the overwhelming majority of columns.
     */
    private static boolean parseBoolean(String raw) {
        if (raw == null) return false;
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return false;
        if (AFFIRMATIVE.contains(norm)) return true;
        if (NEGATIVE.contains(norm)) return false;
        log.warn(
                "Unrecognised saiku.semantic.pii value '{}' — treating the column as NOT PII. "
                        + "Use one of {} to flag it, or one of {} to clear it.",
                raw,
                AFFIRMATIVE,
                NEGATIVE);
        return false;
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static List<String> parseCsvList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String validateEnum(String raw, List<String> allowed, String field) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) return null;
        if (allowed.contains(trimmed)) {
            return trimmed;
        }
        log.warn("Ignoring unknown saiku.semantic.{} value '{}'; allowed: {}", field, trimmed, allowed);
        return null;
    }

    /**
     * Parse a comma-separated list of {@code "[Hier]/Level"} pairs. Malformed entries
     * (missing slash, empty hier or level, double slash) are skipped with a debug log
     * rather than failing the whole parse — schema-gen lint tooling can flag them.
     */
    private static List<AiSchema.RequiredFilter> parseRequiredFilters(String raw) {
        List<AiSchema.RequiredFilter> out = new ArrayList<>();
        if (raw == null) return out;
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            int slash = t.indexOf('/');
            if (slash <= 0 || slash >= t.length() - 1) {
                log.debug("Skipping malformed required_filters entry '{}' (need '[Hier]/Level' shape)", t);
                continue;
            }
            String hier = t.substring(0, slash).trim();
            String level = t.substring(slash + 1).trim();
            if (hier.isEmpty() || level.isEmpty() || level.contains("/")) {
                log.debug("Skipping malformed required_filters entry '{}' (empty side / extra slash)", t);
                continue;
            }
            out.add(new AiSchema.RequiredFilter(hier, level));
        }
        return out;
    }
}
