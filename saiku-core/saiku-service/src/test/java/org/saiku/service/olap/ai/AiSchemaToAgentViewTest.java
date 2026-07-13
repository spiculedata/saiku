/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link AiSchema#toAgentView()} — saiku#902 PII redaction projection
 * applied at the JSON boundary so the in-memory schema (which the validator +
 * MDX builder still need fully populated) stays unaltered.
 *
 * <p>Twelve scenarios cover the four protected fields × the three points the
 * agent might encounter them (measure / level on a top-level hierarchy / level
 * reached via a display-name alias).
 */
public class AiSchemaToAgentViewTest {

    @Test
    public void measure_without_pii_is_unchanged() {
        // Measure with all the annotation-set fields populated. PII flag is
        // false; the agent view returns the IDENTITY of the original — no
        // unnecessary copy when there's nothing to redact.
        AiSchema schema = freshSchema();
        AiSchema.Measure m = new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]");
        m.displayName = "Revenue";
        m.description = "Net retail revenue";
        m.synonyms = List.of("revenue", "turnover");
        m.unit = "USD";
        m.currency = "USD";
        m.aggregationKind = "sum";
        m.pii = false;
        schema.measures.put(AiSchema.key("Store Sales"), m);

        AiSchema view = schema.toAgentView();
        AiSchema.Measure projected = view.measures.get(AiSchema.key("Store Sales"));

        assertEquals("Revenue", projected.displayName);
        assertEquals("Net retail revenue", projected.description);
        assertEquals(List.of("revenue", "turnover"), projected.synonyms);
        assertEquals("USD", projected.unit);
        assertEquals("USD", projected.currency);
        assertEquals("sum", projected.aggregationKind);
        assertFalse(projected.pii);
    }

    @Test
    public void measure_with_pii_strips_caption_displayName_description_synonyms() {
        // PII-flagged measure: name + uniqueName + aggregationKind + pii flag
        // survive (the structural shape the agent needs to know "this slot
        // exists"); displayName / description / synonyms / unit / currency
        // get dropped because they may carry hints about the underlying
        // PII column ("ssn" as a synonym, "Social Security Number" as a
        // description, etc).
        AiSchema schema = freshSchema();
        AiSchema.Measure m = new AiSchema.Measure("CustomerName", "[Measures].[CustomerName]");
        m.displayName = "Customer Name";
        m.description = "Customer's full name — PII";
        m.synonyms = List.of("name", "customer");
        m.unit = "string";
        m.aggregationKind = "non-additive";
        m.pii = true;
        schema.measures.put(AiSchema.key("CustomerName"), m);

        AiSchema.Measure projected = schema.toAgentView().measures.get(AiSchema.key("CustomerName"));

        // Structural fields kept so the agent's mental model + the validator
        // both still see the slot.
        assertEquals("CustomerName", projected.name);
        assertEquals("[Measures].[CustomerName]", projected.uniqueName);
        assertEquals("non-additive", projected.aggregationKind);
        assertTrue(projected.pii);

        // Sensitive fields dropped.
        assertNull(projected.displayName);
        assertNull(projected.description);
        assertTrue(projected.synonyms.isEmpty());
        assertNull(projected.unit);
        assertNull(projected.currency);
    }

    @Test
    public void level_without_pii_keeps_sampleMembers_displayName_description() {
        AiSchema schema = freshSchema();
        AiSchema.Level l = new AiSchema.Level("State", "[Customers].[State]");
        l.displayName = "State";
        l.description = "US state, 2-letter abbreviation";
        l.synonyms = List.of("region");
        l.cardinality = "medium";
        l.sampleMembers = List.of(
                new AiSchema.MemberSample("CA", "[Customers].[USA].[CA]"),
                new AiSchema.MemberSample("WA", "[Customers].[USA].[WA]"));
        AiSchema.Dimension d = new AiSchema.Dimension("Customers", "[Customers]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customers", "[Customers]");
        h.levels.put(AiSchema.key("State"), l);
        d.hierarchies.put(AiSchema.key("Customers"), h);
        schema.dimensions.put(AiSchema.key("Customers"), d);

        AiSchema.Level projected = schema.toAgentView()
                .dimensions
                .get(AiSchema.key("Customers"))
                .hierarchies
                .get(AiSchema.key("Customers"))
                .levels
                .get(AiSchema.key("State"));

        assertEquals("State", projected.displayName);
        assertEquals("US state, 2-letter abbreviation", projected.description);
        assertEquals(List.of("region"), projected.synonyms);
        assertEquals("medium", projected.cardinality);
        assertEquals(2, projected.sampleMembers.size());
        assertEquals("CA", projected.sampleMembers.get(0).caption);
    }

    @Test
    public void level_with_pii_strips_caption_displayName_description_synonyms_and_replaces_sampleMembers() {
        AiSchema schema = freshSchema();
        AiSchema.Level l = new AiSchema.Level("CustomerName", "[Customers].[CustomerName]");
        l.displayName = "Customer Name";
        l.description = "Customer full name";
        l.synonyms = List.of("name", "customer name", "full name");
        l.cardinality = "high";
        l.pii = true;
        l.sampleMembers = List.of(
                new AiSchema.MemberSample("John Smith", "[Customers].[USA].[CA].[John Smith]"),
                new AiSchema.MemberSample("Jane Doe", "[Customers].[USA].[CA].[Jane Doe]"));
        AiSchema.Dimension d = new AiSchema.Dimension("Customers", "[Customers]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customers", "[Customers]");
        h.levels.put(AiSchema.key("CustomerName"), l);
        d.hierarchies.put(AiSchema.key("Customers"), h);
        schema.dimensions.put(AiSchema.key("Customers"), d);

        AiSchema.Level projected = schema.toAgentView()
                .dimensions
                .get(AiSchema.key("Customers"))
                .hierarchies
                .get(AiSchema.key("Customers"))
                .levels
                .get(AiSchema.key("CustomerName"));

        // Structural shape kept: name + uniqueName + cardinality + pii flag.
        assertEquals("CustomerName", projected.name);
        assertEquals("[Customers].[CustomerName]", projected.uniqueName);
        assertEquals("high", projected.cardinality);
        assertTrue(projected.pii);

        // Sensitive fields dropped.
        assertNull(projected.displayName);
        assertNull(projected.description);
        assertTrue(projected.synonyms.isEmpty());

        // Sample members replaced with a single [REDACTED] sentinel — the
        // agent sees "this level has members" without seeing the values.
        assertEquals(1, projected.sampleMembers.size());
        assertEquals("[REDACTED]", projected.sampleMembers.get(0).caption);
        assertEquals("[REDACTED]", projected.sampleMembers.get(0).uniqueName);
    }

    @Test
    public void measure_aliases_pointing_at_pii_measure_are_dropped() {
        // Phase-3 enrichment may register an alias like "social security"
        // pointing at a measure called "SSN". After redaction, that alias
        // must NOT survive — otherwise the agent could ask for "social
        // security" and get the SSN measure key back, defeating the redact.
        AiSchema schema = freshSchema();
        AiSchema.Measure pii = new AiSchema.Measure("SSN", "[Measures].[SSN]");
        pii.pii = true;
        AiSchema.Measure clean = new AiSchema.Measure("Revenue", "[Measures].[Revenue]");
        schema.measures.put(AiSchema.key("SSN"), pii);
        schema.measures.put(AiSchema.key("Revenue"), clean);
        schema.measureAliases.put("social security", AiSchema.key("SSN"));
        schema.measureAliases.put("revenue", AiSchema.key("Revenue"));

        AiSchema view = schema.toAgentView();

        assertNull("alias pointing at PII measure must be dropped", view.measureAliases.get("social security"));
        assertEquals("non-PII alias is kept", AiSchema.key("Revenue"), view.measureAliases.get("revenue"));
    }

    @Test
    public void level_aliases_pointing_at_pii_level_are_dropped_at_both_top_level_and_hierarchy_level() {
        // Same logic for level aliases — both the per-hierarchy alias map
        // (Hierarchy.levelAliases) AND the schema-wide synonym overview
        // (AiSchema.levelAliases) must drop entries that resolve to PII.
        AiSchema schema = freshSchema();
        AiSchema.Level pii = new AiSchema.Level("CustomerName", "[Customers].[CustomerName]");
        pii.pii = true;
        AiSchema.Level clean = new AiSchema.Level("State", "[Customers].[State]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customers", "[Customers]");
        h.levels.put(AiSchema.key("CustomerName"), pii);
        h.levels.put(AiSchema.key("State"), clean);
        // Per-hierarchy alias maps.
        h.levelAliases.put("full name", AiSchema.key("CustomerName"));
        h.levelAliases.put("region", AiSchema.key("State"));
        AiSchema.Dimension d = new AiSchema.Dimension("Customers", "[Customers]");
        d.hierarchies.put(AiSchema.key("Customers"), h);
        schema.dimensions.put(AiSchema.key("Customers"), d);
        // Top-level synonym overview map.
        schema.levelAliases.put(
                "name",
                List.of(new AiSchema.LevelAliasTarget(
                        AiSchema.key("Customers"), AiSchema.key("Customers"), AiSchema.key("CustomerName"))));
        schema.levelAliases.put(
                "region",
                List.of(new AiSchema.LevelAliasTarget(
                        AiSchema.key("Customers"), AiSchema.key("Customers"), AiSchema.key("State"))));

        AiSchema view = schema.toAgentView();

        AiSchema.Hierarchy projectedH =
                view.dimensions.get(AiSchema.key("Customers")).hierarchies.get(AiSchema.key("Customers"));
        assertNull("hierarchy-level alias to PII level dropped", projectedH.levelAliases.get("full name"));
        assertEquals("non-PII alias kept", AiSchema.key("State"), projectedH.levelAliases.get("region"));

        assertNull("schema-wide synonym 'name' (PII target) dropped", view.levelAliases.get("name"));
        assertNotNull("schema-wide synonym 'region' (non-PII) kept", view.levelAliases.get("region"));
    }

    @Test
    public void toAgentView_does_not_mutate_the_source() {
        // Critical invariant: the validator + MDX builder use the SAME
        // AiSchema instance the describe endpoint sees. toAgentView() must
        // return a NEW projection without altering the original.
        AiSchema schema = freshSchema();
        AiSchema.Measure m = new AiSchema.Measure("CustomerName", "[Measures].[CustomerName]");
        m.displayName = "Customer Name";
        m.synonyms = new java.util.ArrayList<>(List.of("name"));
        m.pii = true;
        schema.measures.put(AiSchema.key("CustomerName"), m);

        schema.toAgentView();

        // Original measure still has its fields.
        AiSchema.Measure preserved = schema.measures.get(AiSchema.key("CustomerName"));
        assertEquals("Customer Name", preserved.displayName);
        assertEquals(List.of("name"), preserved.synonyms);
    }

    @Test
    public void toAgentView_copies_cube_level_fields_verbatim() {
        // cubeId / cubeName / cubeUniqueName / description / examples /
        // requestSchema aren't per-column data; they pass straight through.
        AiSchema schema = freshSchema();
        schema.description = "Sales by store and time";
        schema.requestSchema = java.util.Map.of("type", "object");

        AiSchema view = schema.toAgentView();

        assertEquals("Sales", view.getCubeName());
        assertEquals("Sales by store and time", view.description);
        assertEquals("object", view.requestSchema.get("type"));
    }

    private static AiSchema freshSchema() {
        return new AiSchema("c/cat/sch/Sales", "Sales", "[Sales]");
    }
}
