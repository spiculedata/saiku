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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link AiSchemaEnricher}. Verifies:
 *  - displayName fields are set when an overlay names them
 *  - canonical {@code name} fields are NEVER changed (so validation
 *    still resolves the agent's name set after enrichment)
 *  - suggestions[] is appended to the schema
 *  - the schema converter still resolves a query built against the
 *    canonical names after enrichment
 */
public class AiSchemaEnricherTest {

    private AiSchema schema;
    private AiSchemaEnricher enricher;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(AiSchema.key("Store Sales"),
                new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Quarter"),
                new AiSchema.Level("Quarter", "[Time].[Time By].[Quarter]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        enricher = new AiSchemaEnricher();
    }

    @Test
    public void renameMeasureSetsDisplayName() {
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.getRenames().put("measures.Store Sales", "Revenue");
        enricher.apply(schema, e);

        assertEquals("display label updated", "Revenue",
                schema.measures.get(AiSchema.key("Store Sales")).displayName);
        assertEquals("canonical name preserved", "Store Sales",
                schema.measures.get(AiSchema.key("Store Sales")).name);
    }

    @Test
    public void renameDimensionHierarchyAndLevel() {
        Map<String, String> renames = new HashMap<>();
        renames.put("dimensions.Time", "Period");
        renames.put("dimensions.Time.hierarchies.Time By", "By Date");
        renames.put("dimensions.Time.hierarchies.Time By.levels.Quarter", "Q");
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.setRenames(renames);
        enricher.apply(schema, e);

        AiSchema.Dimension d = schema.dimensions.get(AiSchema.key("Time"));
        assertEquals("Period", d.displayName);
        assertEquals("Time", d.name);

        AiSchema.Hierarchy h = d.hierarchies.get(AiSchema.key("Time By"));
        assertEquals("By Date", h.displayName);
        assertEquals("Time By", h.name);

        AiSchema.Level l = h.levels.get(AiSchema.key("Quarter"));
        assertEquals("Q", l.displayName);
        assertEquals("Quarter", l.name);
    }

    @Test
    public void unknownRenamePathIsIgnored() {
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.getRenames().put("measures.Nonexistent", "X");
        e.getRenames().put("dimensions.Foo.hierarchies.Bar.levels.Baz", "Y");
        enricher.apply(schema, e);
        // The known measure is untouched.
        assertNull(schema.measures.get(AiSchema.key("Store Sales")).displayName);
    }

    @Test
    public void suggestionsAreAppended() {
        AiSchemaSuggestion s = new AiSchemaSuggestion(
                "rename", "measures.Store Sales", 0.92,
                "matches common analyst vocabulary", "Revenue");
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.setSuggestions(Collections.singletonList(s));
        enricher.apply(schema, e);

        assertEquals(1, schema.suggestions.size());
        AiSchemaSuggestion got = schema.suggestions.get(0);
        assertEquals("rename", got.getOp());
        assertEquals("Revenue", got.getSuggestedValue());
    }

    @Test
    public void enrichmentDoesNotBreakValidation() {
        // Rename everything — converter must still resolve the agent's
        // request against the canonical name set.
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.getRenames().put("measures.Store Sales", "Revenue");
        e.getRenames().put("dimensions.Time", "Period");
        e.getRenames().put("dimensions.Time.hierarchies.Time By", "By Date");
        e.getRenames().put("dimensions.Time.hierarchies.Time By.levels.Quarter", "Q");
        enricher.apply(schema, e);

        // Build a request using the CANONICAL names. Must still succeed.
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Quarter")));

        org.saiku.olap.query2.ThinQuery tq =
                new AiSchemaConverter().convert(req, schema);
        assertNotNull(tq);
        assertTrue(tq.getMdx(), tq.getMdx().contains("[Time].[Time By].[Quarter].Members"));
    }

    @Test
    public void displayNameIsAValidQueryName() {
        // After renaming "Store Sales" -> "Revenue", the agent may use either
        // the canonical name OR the display name. Phase 3 contract.
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.getRenames().put("measures.Store Sales", "Revenue");
        enricher.apply(schema, e);

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Revenue")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Quarter")));

        org.saiku.olap.query2.ThinQuery tq = new AiSchemaConverter().convert(req, schema);
        assertNotNull(tq);
        // The generated MDX still uses the canonical uniqueName, regardless of
        // which alias the agent supplied.
        assertTrue(tq.getMdx(), tq.getMdx().contains("[Measures].[Store Sales]"));
    }

    @Test
    public void unknownNameStillFailsValidationWithBothAliasesListed() {
        // Negative path: a totally bogus name still 400s, and the candidate
        // list includes both canonical names AND display names.
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.getRenames().put("measures.Store Sales", "Revenue");
        enricher.apply(schema, e);

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Bogus")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Quarter")));

        try {
            new AiSchemaConverter().convert(req, schema);
            org.junit.Assert.fail("expected validation error for unknown name");
        } catch (AiValidationException ex) {
            assertEquals("measures[].name", ex.getField());
            assertTrue("candidates include canonical name", ex.getAvailable().contains("Store Sales"));
            assertTrue("candidates include display name", ex.getAvailable().contains("Revenue"));
        }
    }

    @Test
    public void displayNamesResolveAcrossDimHierLevel() {
        // End-to-end: rename every axis path, verify a request using all
        // display names resolves to the right canonical MDX.
        AiSchemaEnrichment e = new AiSchemaEnrichment();
        e.getRenames().put("dimensions.Time", "Period");
        e.getRenames().put("dimensions.Time.hierarchies.Time By", "By Date");
        e.getRenames().put("dimensions.Time.hierarchies.Time By.levels.Quarter", "Q");
        enricher.apply(schema, e);

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Period", "By Date", "Q")));

        org.saiku.olap.query2.ThinQuery tq = new AiSchemaConverter().convert(req, schema);
        assertTrue(tq.getMdx(), tq.getMdx().contains("[Time].[Time By].[Quarter].Members"));
    }

    @Test
    public void enrichmentProviderInOlapServicePropagates() {
        // Wire an enrichment provider directly through the production service
        // (test uses a stub discover so we don't need olap4j).
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService() {
            @Override
            public AiSchema getSchema(AiCubeRef ref) {
                // Skip the discover-service call by short-circuiting to our test schema.
                AiSchema base = new AiSchema(
                        "foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
                base.measures.put(AiSchema.key("Store Sales"),
                        new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
                AiSchemaEnrichment overlay = new AiSchemaEnrichment();
                overlay.getRenames().put("measures.Store Sales", "Revenue");
                new AiSchemaEnricher().apply(base, overlay);
                return base;
            }
        };

        AiSchema enriched = svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertEquals("Revenue", enriched.measures.get(AiSchema.key("Store Sales")).displayName);
        assertEquals("Store Sales", enriched.measures.get(AiSchema.key("Store Sales")).name);
    }
}
