/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;

/** Unit tests for {@link QueryStructureSummarizer} (saiku#909). */
public class QueryStructureSummarizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiSchema schema;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeH = new AiSchema.Hierarchy("Time", "[Time].[Time]");
        timeH.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time].[Year]"));
        time.hierarchies.put(AiSchema.key("Time"), timeH);
        schema.dimensions.put(AiSchema.key("Time"), time);

        // Customer/Name is PII-flagged — the level whose member captions must never
        // reach the prompt in the clear.
        AiSchema.Dimension customer = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy customerH = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        AiSchema.Level nameLevel = new AiSchema.Level("Name", "[Customer].[Customer].[Name]");
        nameLevel.pii = true;
        customerH.levels.put(AiSchema.key("Name"), nameLevel);
        customer.hierarchies.put(AiSchema.key("Customer"), customerH);
        schema.dimensions.put(AiSchema.key("Customer"), customer);
    }

    private AiQueryRequest baseRequest() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time", "Year")));
        return req;
    }

    @Test
    public void summarize_includesMeasuresRowsAndCube_noValues() throws Exception {
        JsonNode root = MAPPER.readTree(QueryStructureSummarizer.summarize(baseRequest(), schema));
        assertEquals("Sales", root.path("cube").asText());
        assertEquals(1, root.path("measures").size());
        assertEquals("Store Sales", root.path("measures").get(0).asText());
        assertEquals(1, root.path("rows").size());
        assertEquals("Time", root.path("rows").get(0).path("dimension").asText());
        assertEquals("Year", root.path("rows").get(0).path("level").asText());
        // No aggregated values / cell data of any kind in the structure summary.
        assertFalse(root.toString().contains("value"));
    }

    @Test
    public void summarize_redactsMembersOnPiiFlaggedLevel() throws Exception {
        AiQueryRequest req = baseRequest();
        AiFilterSelection filter =
                new AiFilterSelection("Customer", "Customer", "Name", List.of("Jane Doe", "John Smith"));
        req.setFilters(List.of(filter));

        JsonNode root = MAPPER.readTree(QueryStructureSummarizer.summarize(req, schema));
        JsonNode filters = root.path("filters");
        assertEquals(1, filters.size());
        // Structure (dimension/hierarchy/level) is kept…
        assertEquals("Customer", filters.get(0).path("dimension").asText());
        assertEquals("Name", filters.get(0).path("level").asText());
        // …but the actual member captions never appear.
        JsonNode members = filters.get(0).path("members");
        assertEquals(1, members.size());
        assertEquals(QueryStructureSummarizer.REDACTED, members.get(0).asText());
        String wire = root.toString();
        assertFalse("PII caption must never leak into the prompt", wire.contains("Jane Doe"));
        assertFalse("PII caption must never leak into the prompt", wire.contains("John Smith"));
    }

    @Test
    public void summarize_keepsMembersOnNonPiiLevel() throws Exception {
        AiQueryRequest req = baseRequest();
        AiAxisSelection rowsWithMembers = new AiAxisSelection("Time", "Time", "Year");
        rowsWithMembers.setMembers(List.of("2001", "2002"));
        req.setRows(List.of(rowsWithMembers));

        JsonNode root = MAPPER.readTree(QueryStructureSummarizer.summarize(req, schema));
        JsonNode members = root.path("rows").get(0).path("members");
        assertEquals(2, members.size());
        assertEquals("2001", members.get(0).asText());
    }

    @Test
    public void summarize_resolvesMeasureSynonymToCanonicalName() throws Exception {
        schema.measureAliases.put(AiSchema.key("Sales"), AiSchema.key("Store Sales"));
        AiQueryRequest req = baseRequest();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Sales")));

        JsonNode root = MAPPER.readTree(QueryStructureSummarizer.summarize(req, schema));
        assertEquals("Store Sales", root.path("measures").get(0).asText());
    }

    @Test
    public void summarize_handlesUnresolvableNamesGracefully() throws Exception {
        AiQueryRequest req = baseRequest();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Nonexistent Measure")));

        String json = QueryStructureSummarizer.summarize(req, schema);
        JsonNode root = MAPPER.readTree(json);
        assertTrue(root.path("measures").get(0).asText().contains("Nonexistent"));
    }
}
