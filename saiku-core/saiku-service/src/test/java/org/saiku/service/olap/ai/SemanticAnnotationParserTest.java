/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.SemanticAnnotationParser.LevelAnnotations;
import org.saiku.service.olap.ai.SemanticAnnotationParser.MeasureAnnotations;

/**
 * Pure-unit tests for {@link SemanticAnnotationParser}. Drives the contract for
 * saiku#818: turn a Mondrian-flavoured {@code Map<String, String>} of
 * {@code saiku.semantic.*} annotations into typed values that downstream
 * {@code AiSchema.Measure} / {@link AiSchema.Level} can carry directly.
 */
public class SemanticAnnotationParserTest {

    @Test
    public void parseMeasure_populates_description_synonyms_unit_currency_aggregationKind() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("saiku.semantic.description", "Net retail revenue in USD.");
        raw.put("saiku.semantic.synonyms", "revenue, turnover , top-line");
        raw.put("saiku.semantic.unit", "USD");
        raw.put("saiku.semantic.currency", "USD");
        raw.put("saiku.semantic.aggregation_kind", "sum");

        MeasureAnnotations parsed = SemanticAnnotationParser.parseMeasure(raw);

        assertEquals("Net retail revenue in USD.", parsed.description);
        assertEquals(List.of("revenue", "turnover", "top-line"), parsed.synonyms);
        assertEquals("USD", parsed.unit);
        assertEquals("USD", parsed.currency);
        assertEquals("sum", parsed.aggregationKind);
    }

    @Test
    public void parseMeasure_unknown_aggregationKind_is_dropped() {
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.aggregation_kind", "totally-bogus");
        MeasureAnnotations parsed = SemanticAnnotationParser.parseMeasure(raw);
        // Enum validation: unknown values are silently dropped (parser logs WARN).
        // Schema-gen tooling can lint separately; we never 500 a /ai/schema
        // call because a schema author typo'd a value.
        assertNull(parsed.aggregationKind);
    }

    @Test
    public void parseMeasure_empty_synonyms_string_yields_empty_list() {
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.synonyms", "   ");
        MeasureAnnotations parsed = SemanticAnnotationParser.parseMeasure(raw);
        assertTrue("whitespace-only synonyms collapses to empty", parsed.synonyms.isEmpty());
    }

    @Test
    public void parseMeasure_empty_map_returns_defaults() {
        MeasureAnnotations parsed = SemanticAnnotationParser.parseMeasure(new HashMap<>());
        assertNull(parsed.description);
        assertNull(parsed.unit);
        assertNull(parsed.currency);
        assertNull(parsed.aggregationKind);
        assertTrue(parsed.synonyms.isEmpty());
    }

    @Test
    public void parseMeasure_null_map_returns_defaults_without_throwing() {
        MeasureAnnotations parsed = SemanticAnnotationParser.parseMeasure(null);
        assertNotNull(parsed);
        assertNull(parsed.description);
        assertTrue(parsed.synonyms.isEmpty());
    }

    @Test
    public void parseMeasure_ignores_keys_outside_saiku_semantic_namespace() {
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.description", "official");
        raw.put("mondrian.internal.something", "internal value");
        raw.put("saiku.governance.owner", "team-x"); // future namespace, must not leak
        MeasureAnnotations parsed = SemanticAnnotationParser.parseMeasure(raw);
        assertEquals("official", parsed.description);
    }

    @Test
    public void parseLevel_populates_description_synonyms_cardinality_grain() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("saiku.semantic.description", "Calendar quarter; aggregates 3 months.");
        raw.put("saiku.semantic.synonyms", "fiscal Q,quarterly");
        raw.put("saiku.semantic.cardinality", "low");
        raw.put("saiku.semantic.grain", "quarter");

        LevelAnnotations parsed = SemanticAnnotationParser.parseLevel(raw);

        assertEquals("Calendar quarter; aggregates 3 months.", parsed.description);
        assertEquals(List.of("fiscal Q", "quarterly"), parsed.synonyms);
        assertEquals("low", parsed.cardinality);
        assertEquals("quarter", parsed.grain);
        assertTrue(parsed.requiredFilters.isEmpty());
    }

    @Test
    public void parseLevel_unknown_grain_or_cardinality_is_dropped() {
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.cardinality", "ENORMOUS");
        raw.put("saiku.semantic.grain", "fortnight");
        LevelAnnotations parsed = SemanticAnnotationParser.parseLevel(raw);
        assertNull(parsed.cardinality);
        assertNull(parsed.grain);
    }

    @Test
    public void parseLevel_required_filters_parses_hierarchy_slash_level_pairs() {
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.required_filters", "[Time].[Time By]/Year, [Customer]/Country");
        LevelAnnotations parsed = SemanticAnnotationParser.parseLevel(raw);
        assertEquals(2, parsed.requiredFilters.size());

        AiSchema.RequiredFilter first = parsed.requiredFilters.get(0);
        assertEquals("[Time].[Time By]", first.hierarchy);
        assertEquals("Year", first.level);

        AiSchema.RequiredFilter second = parsed.requiredFilters.get(1);
        assertEquals("[Customer]", second.hierarchy);
        assertEquals("Country", second.level);
    }

    @Test
    public void parseLevel_required_filters_skips_malformed_entries() {
        Map<String, String> raw = new HashMap<>();
        // Missing slash → drop; trailing comma → drop empty; double slash → drop.
        raw.put("saiku.semantic.required_filters", "Time-no-slash, [Time]/Year, , [Bad]//");
        LevelAnnotations parsed = SemanticAnnotationParser.parseLevel(raw);
        assertEquals(1, parsed.requiredFilters.size());
        assertEquals("[Time]", parsed.requiredFilters.get(0).hierarchy);
        assertEquals("Year", parsed.requiredFilters.get(0).level);
    }

    @Test
    public void allowed_enum_values_are_exposed_for_schema_gen_lint_tooling() {
        // Public contract: callers (schema-gen lint, docs generators) need
        // programmatic access to the allowed enum values.
        assertTrue(SemanticAnnotationParser.AGGREGATION_KINDS.contains("sum"));
        assertTrue(SemanticAnnotationParser.AGGREGATION_KINDS.contains("distinct-count"));
        assertTrue(SemanticAnnotationParser.CARDINALITIES.contains("medium"));
        assertTrue(SemanticAnnotationParser.GRAINS.contains("quarter"));
        assertTrue(SemanticAnnotationParser.GRAINS.contains("week"));
    }

    /* ---------------------- saiku#902 PII annotation ---------------------- */

    @Test
    public void parseMeasure_pii_true_is_honoured() {
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.pii", "true");
        MeasureAnnotations parsed = SemanticAnnotationParser.parseMeasure(raw);
        assertTrue(parsed.pii);
    }

    @Test
    public void parseLevel_pii_true_is_honoured() {
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.pii", "true");
        LevelAnnotations parsed = SemanticAnnotationParser.parseLevel(raw);
        assertTrue(parsed.pii);
    }

    @Test
    public void parseMeasure_pii_accepts_unambiguous_affirmatives() {
        // saiku#1849: these are not typos, they are ways of saying yes. Reading them as false meant
        // the column was never masked — no redaction in /ai/schema, no drillthrough returns= block.
        for (String yes : new String[] {"true", "TRUE", " True ", "yes", "YES", "y", "on", "1"}) {
            Map<String, String> raw = new HashMap<>();
            raw.put("saiku.semantic.pii", yes);
            assertEquals(
                    "pii must be honoured for value: '" + yes + "'",
                    true,
                    SemanticAnnotationParser.parseMeasure(raw).pii);
            assertEquals(true, SemanticAnnotationParser.parseLevel(raw).pii);
        }
    }

    @Test
    public void parseMeasure_pii_false_is_the_conservative_default_on_garbage() {
        // saiku#1849 narrows this list. The original trade-off stands and is deliberate: for an
        // analytics product, masking working data on a slip is a worse everyday failure than a
        // schema author having to correct a value, so an unrecognised value still means "not PII".
        //
        // But "yes" / "1" / "on" were never slips — they are unambiguous ways of saying yes, and
        // silently reading them as false left personal data flowing with nothing logged. They are
        // now honoured (see parseMeasure_pii_accepts_unambiguous_affirmatives). Genuinely
        // unrecognised values still resolve to false, and now log a WARN naming the value, so the
        // slip is discoverable instead of invisible.
        for (String bogus : new String[] {"false", "no", "0", "off", "TRUE_BUT_NOT_QUITE", "", null}) {
            Map<String, String> raw = new HashMap<>();
            raw.put("saiku.semantic.pii", bogus);
            assertEquals(
                    "pii must default false for value: '" + bogus + "'",
                    false,
                    SemanticAnnotationParser.parseMeasure(raw).pii);
            assertEquals(false, SemanticAnnotationParser.parseLevel(raw).pii);
        }
    }

    @Test
    public void parseMeasure_pii_true_case_insensitive_and_trimmed() {
        // Schema files come from human hands; tolerate whitespace + case.
        for (String trueish : new String[] {"true", "TRUE", "True", " true ", "tRuE"}) {
            Map<String, String> raw = new HashMap<>();
            raw.put("saiku.semantic.pii", trueish);
            assertTrue("pii must accept: '" + trueish + "'", SemanticAnnotationParser.parseMeasure(raw).pii);
        }
    }

    @Test
    public void parseMeasure_pii_absent_defaults_false() {
        // Existing schemas without the annotation must stay unchanged —
        // the default is "no PII protection until you opt in".
        Map<String, String> raw = new HashMap<>();
        raw.put("saiku.semantic.description", "ordinary measure");
        assertEquals(false, SemanticAnnotationParser.parseMeasure(raw).pii);
    }
}
