/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Overlay merge precedence for {@code saiku.semantic.*} fields (saiku#818). The
 * overlay carries a {@code Map<path, Map<key, value>>} bag at the same slash-path
 * shape as {@code renames}. Each inner map is parsed by
 * {@link SemanticAnnotationParser} and merged onto the matching {@code AiSchema.Measure}
 * / {@link AiSchema.Level}: overlay always wins over the XML-projected value.
 */
public class AiSchemaEnricherAnnotationsTest {

    private static AiSchema baseSchema() {
        AiSchema s = new AiSchema("[Sales]", "Sales", "[Sales]");
        AiSchema.Measure storeSales = new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]");
        storeSales.description = "From-XML description";
        storeSales.unit = "From-XML unit";
        s.measures.put(AiSchema.key("Store Sales"), storeSales);

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        AiSchema.Level quarter = new AiSchema.Level("Quarter", "[Time].[Time By].[Quarter]");
        quarter.cardinality = "high"; // XML-set; overlay should win below
        timeBy.levels.put(AiSchema.key("Quarter"), quarter);
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        s.dimensions.put(AiSchema.key("Time"), time);
        return s;
    }

    @Test
    public void overlay_description_replaces_xml_description_on_measure() {
        AiSchema schema = baseSchema();
        AiSchemaEnrichment overlay = new AiSchemaEnrichment();
        Map<String, String> ann = new LinkedHashMap<>();
        ann.put("saiku.semantic.description", "From-overlay description");
        overlay.getAnnotations().put("measures.Store Sales", ann);

        new AiSchemaEnricher().apply(schema, overlay);

        AiSchema.Measure m = schema.measures.get(AiSchema.key("Store Sales"));
        assertEquals("From-overlay description", m.description);
        // Non-overridden XML field must survive untouched.
        assertEquals("From-XML unit", m.unit);
    }

    @Test
    public void overlay_cardinality_replaces_xml_cardinality_on_level() {
        AiSchema schema = baseSchema();
        AiSchemaEnrichment overlay = new AiSchemaEnrichment();
        Map<String, String> ann = new HashMap<>();
        ann.put("saiku.semantic.cardinality", "low");
        ann.put("saiku.semantic.grain", "quarter");
        overlay.getAnnotations().put("dimensions.Time.hierarchies.Time By.levels.Quarter", ann);

        new AiSchemaEnricher().apply(schema, overlay);

        AiSchema.Level l = schema.dimensions
                .get(AiSchema.key("Time"))
                .hierarchies
                .get(AiSchema.key("Time By"))
                .levels
                .get(AiSchema.key("Quarter"));
        assertEquals("low", l.cardinality);
        assertEquals("quarter", l.grain);
    }

    @Test
    public void overlay_synonyms_appear_in_measure_aliases_for_resolution() {
        AiSchema schema = baseSchema();
        AiSchemaEnrichment overlay = new AiSchemaEnrichment();
        Map<String, String> ann = new HashMap<>();
        ann.put("saiku.semantic.synonyms", "revenue, turnover");
        overlay.getAnnotations().put("measures.Store Sales", ann);

        new AiSchemaEnricher().apply(schema, overlay);

        AiSchema.Measure m = schema.measures.get(AiSchema.key("Store Sales"));
        assertTrue(m.synonyms.contains("revenue"));
        assertTrue(m.synonyms.contains("turnover"));
        // And both alternate names route to the canonical key in the alias map.
        assertEquals(AiSchema.key("Store Sales"), schema.measureAliases.get(AiSchema.key("revenue")));
        assertEquals(AiSchema.key("Store Sales"), schema.measureAliases.get(AiSchema.key("turnover")));
    }

    @Test
    public void overlay_with_no_annotations_block_leaves_schema_unchanged() {
        AiSchema schema = baseSchema();
        new AiSchemaEnricher().apply(schema, new AiSchemaEnrichment());

        AiSchema.Measure m = schema.measures.get(AiSchema.key("Store Sales"));
        assertEquals("From-XML description", m.description);
        assertEquals("From-XML unit", m.unit);
    }

    @Test
    public void overlay_for_missing_path_is_a_silent_noop() {
        AiSchema schema = baseSchema();
        AiSchemaEnrichment overlay = new AiSchemaEnrichment();
        Map<String, String> ann = new HashMap<>();
        ann.put("saiku.semantic.description", "ignored");
        overlay.getAnnotations().put("measures.NonExistent", ann);

        // Must not throw — bad slash paths produced by stale sidecars just no-op.
        new AiSchemaEnricher().apply(schema, overlay);

        AiSchema.Measure m = schema.measures.get(AiSchema.key("Store Sales"));
        assertNotNull(m);
        assertEquals("From-XML description", m.description);
    }
}
