/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Top-level {@code schema.levelAliases} contract (saiku#818 follow-up). An agent
 * scanning the schema response needs a flat overview of every level synonym in
 * the cube, the same way {@code measureAliases} and {@code dimensionAliases}
 * give it a flat overview for those layers. Level names can collide across
 * hierarchies, so the value is a List of {@code (dimension, hierarchy, level)}
 * triples rather than a single canonical key — option A from the design
 * conversation.
 */
public class AiSchemaLevelAliasesTest {

    private AiSchema buildCubeWithCollidingLevelSynonyms() {
        AiSchema s = new AiSchema("[Sales]", "Sales", "[Sales]");

        // Time/Time/Quarter declares synonym "quarterly"
        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        AiSchema.Level qTimeBy = new AiSchema.Level("Quarter", "[Time].[Time By].[Quarter]");
        qTimeBy.synonyms = Arrays.asList("quarterly", "qtr");
        timeBy.levels.put(AiSchema.key("Quarter"), qTimeBy);
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);

        // Time/Fiscal/Quarter also declares synonym "quarterly" — same synonym,
        // different level. The flat top-level map must list BOTH.
        AiSchema.Hierarchy fiscal = new AiSchema.Hierarchy("Fiscal", "[Time].[Fiscal]");
        AiSchema.Level qFiscal = new AiSchema.Level("Quarter", "[Time].[Fiscal].[Quarter]");
        qFiscal.synonyms = Arrays.asList("quarterly");
        fiscal.levels.put(AiSchema.key("Quarter"), qFiscal);
        time.hierarchies.put(AiSchema.key("Fiscal"), fiscal);

        s.dimensions.put(AiSchema.key("Time"), time);

        // A non-colliding synonym (only one level uses it) so we can distinguish
        // single-target entries from collisions in the flat map.
        AiSchema.Dimension customer = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy customers = new AiSchema.Hierarchy("Customers", "[Customer].[Customers]");
        AiSchema.Level country = new AiSchema.Level("Country", "[Customer].[Customers].[Country]");
        country.synonyms = Arrays.asList("nation");
        customers.levels.put(AiSchema.key("Country"), country);
        customer.hierarchies.put(AiSchema.key("Customers"), customers);
        s.dimensions.put(AiSchema.key("Customer"), customer);

        return s;
    }

    @Test
    public void enricher_populates_top_level_levelAliases_map() {
        AiSchema schema = buildCubeWithCollidingLevelSynonyms();
        new AiSchemaEnricher().apply(schema, new AiSchemaEnrichment());

        assertNotNull("schema.levelAliases must exist", schema.levelAliases);
        assertTrue(
                "schema.levelAliases must carry the colliding synonym", schema.levelAliases.containsKey("quarterly"));
        assertTrue(
                "schema.levelAliases must carry the single-target synonym", schema.levelAliases.containsKey("nation"));
    }

    @Test
    public void colliding_synonym_lists_all_targets_not_just_first() {
        AiSchema schema = buildCubeWithCollidingLevelSynonyms();
        new AiSchemaEnricher().apply(schema, new AiSchemaEnrichment());

        List<AiSchema.LevelAliasTarget> targets = schema.levelAliases.get("quarterly");
        assertNotNull(targets);
        assertEquals("quarterly resolves to two levels (Time By + Fiscal)", 2, targets.size());

        boolean sawTimeBy = false, sawFiscal = false;
        for (AiSchema.LevelAliasTarget t : targets) {
            if ("time by".equals(t.hierarchy)) sawTimeBy = true;
            if ("fiscal".equals(t.hierarchy)) sawFiscal = true;
            assertEquals("time", t.dimension);
            assertEquals("quarter", t.level);
        }
        assertTrue("Time/Time By/Quarter present", sawTimeBy);
        assertTrue("Time/Fiscal/Quarter present", sawFiscal);
    }

    @Test
    public void unique_synonym_yields_single_target_entry() {
        AiSchema schema = buildCubeWithCollidingLevelSynonyms();
        new AiSchemaEnricher().apply(schema, new AiSchemaEnrichment());

        List<AiSchema.LevelAliasTarget> targets = schema.levelAliases.get("nation");
        assertNotNull(targets);
        assertEquals(1, targets.size());
        AiSchema.LevelAliasTarget t = targets.get(0);
        assertEquals("customer", t.dimension);
        assertEquals("customers", t.hierarchy);
        assertEquals("country", t.level);
    }

    @Test
    public void per_hierarchy_levelAliases_still_populated_after_top_level_added() {
        // Resolution still happens against Hierarchy.levelAliases — the top-level
        // map is purely for read-side observability. Verify the per-hierarchy
        // map is unaffected.
        AiSchema schema = buildCubeWithCollidingLevelSynonyms();
        new AiSchemaEnricher().apply(schema, new AiSchemaEnrichment());

        AiSchema.Hierarchy timeBy = schema.dimensions.get("time").hierarchies.get("time by");
        assertEquals("quarter", timeBy.levelAliases.get("quarterly"));
        assertEquals("quarter", timeBy.levelAliases.get("qtr"));
    }
}
