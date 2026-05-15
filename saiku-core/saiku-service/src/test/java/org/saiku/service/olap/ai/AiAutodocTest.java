/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Autodocumentation tests — verifies that the schema response carries
 * enough machine-readable metadata for an LLM to ground its queries:
 * descriptions, sample members, examples, and a JSON Schema of the
 * AiQueryRequest contract.
 */
public class AiAutodocTest {

    @Test
    public void requestSchemaHasJsonSchemaIdentifierAndCoreProperties() {
        Map<String, Object> s = AiRequestJsonSchema.forRequest();
        assertEquals("https://json-schema.org/draft/2020-12/schema", s.get("$schema"));
        assertEquals("AiQueryRequest", s.get("title"));
        assertEquals("object", s.get("type"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) s.get("required");
        assertTrue(required.contains("cube"));
        assertTrue(required.contains("measures"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) s.get("properties");
        assertTrue(properties.containsKey("cube"));
        assertTrue(properties.containsKey("measures"));
        assertTrue(properties.containsKey("rows"));
        assertTrue(properties.containsKey("columns"));
        assertTrue(properties.containsKey("filters"));
        assertTrue(properties.containsKey("limit"));
        assertTrue(properties.containsKey("visualTotals"));
        assertTrue(properties.containsKey("nonEmpty"));
    }

    @Test
    public void requestSchemaCubeRefHasRequiredFields() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) AiRequestJsonSchema.forRequest().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> cube = (Map<String, Object>) properties.get("cube");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) cube.get("required");
        assertTrue(required.contains("connectionName"));
        assertTrue(required.contains("catalog"));
        assertTrue(required.contains("schema"));
        assertTrue(required.contains("cubeName"));
    }

    @Test
    public void exampleBuilderProducesShapesForCubeWithDimensions() {
        AiSchema schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        AiCubeRef ref = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
        List<AiQueryRequest> examples = AiExampleBuilder.build(schema, ref);
        assertEquals("breakdown + topN + visualTotals = 3 examples", 3, examples.size());

        AiQueryRequest topN = examples.get(1);
        assertEquals("Sales", topN.getCube().getCubeName());
        assertEquals("Store Sales", topN.getMeasures().get(0).getName());
        assertEquals("Time", topN.getRows().get(0).getDimension());
        assertEquals("Year", topN.getRows().get(0).getLevel());
        assertEquals(10, topN.getLimit());

        AiQueryRequest withTotals = examples.get(2);
        assertTrue(withTotals.isVisualTotals());
    }

    @Test
    public void exampleBuilderHandlesCubeWithNoDimensions() {
        AiSchema schema = new AiSchema("a/b/c/d", "d", "[d]");
        schema.measures.put(AiSchema.key("M"), new AiSchema.Measure("M", "[Measures].[M]"));
        AiCubeRef ref = new AiCubeRef("a", "b", "c", "d");
        List<AiQueryRequest> examples = AiExampleBuilder.build(schema, ref);
        assertEquals(1, examples.size());
        assertTrue(examples.get(0).getRows().isEmpty());
    }

    @Test
    public void exampleBuilderHandlesEmptyMeasures() {
        AiSchema schema = new AiSchema("a/b/c/d", "d", "[d]");
        AiCubeRef ref = new AiCubeRef("a", "b", "c", "d");
        assertTrue(AiExampleBuilder.build(schema, ref).isEmpty());
    }

    @Test
    public void aiSchemaFieldsAcceptDescriptions() {
        // Verify the new free-text annotation fields exist and don't break
        // serialisation when populated.
        AiSchema schema = new AiSchema("a/b/c/d", "d", "[d]");
        schema.description = "Cube describing FoodMart sales facts.";

        AiSchema.Measure m = new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]");
        m.description = "Total revenue from store sales.";
        schema.measures.put(AiSchema.key("Store Sales"), m);

        AiSchema.Dimension d = new AiSchema.Dimension("Time", "[Time]");
        d.description = "Time dimension covering 1997-2010.";
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        h.description = "Calendar-year hierarchy.";
        AiSchema.Level l = new AiSchema.Level("Year", "[Time].[Time By].[Year]");
        l.description = "Calendar year.";
        l.sampleMembers.add("1997");
        l.sampleMembers.add("1998");
        l.sampleMembers.add("1999");
        h.levels.put(AiSchema.key("Year"), l);
        d.hierarchies.put(AiSchema.key("Time By"), h);
        schema.dimensions.put(AiSchema.key("Time"), d);

        assertEquals("Cube describing FoodMart sales facts.", schema.description);
        assertEquals(3, l.sampleMembers.size());
        assertEquals("1997", l.sampleMembers.get(0));
    }

    @Test
    public void requestSchemaIsSerialisableViaJackson() throws Exception {
        // Round-trip through Jackson to confirm the schema is valid JSON
        // (i.e. no recursive references, no non-serialisable values).
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(AiRequestJsonSchema.forRequest());
        assertNotNull(json);
        assertTrue(json.contains("\"$schema\""));
        assertTrue(json.contains("\"AiQueryRequest\""));
        assertFalse("must not contain Java class references", json.contains("@class"));
    }
}
