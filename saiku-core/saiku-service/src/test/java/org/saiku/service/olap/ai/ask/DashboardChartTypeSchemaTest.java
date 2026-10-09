/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Issue #1481 — the {@code emit_dashboard} tool's {@code chartType} enum used to advertise a
 * five-id "MVP set" (bar/line/pie/area/scatter) while the dashboard tile renderer has drawn every
 * palette type since the shared option builder landed, so eleven renderable chart types were
 * unreachable as an AI-built dashboard tile. These tests pin the tool schema to the canonical
 * catalog ({@link AiViewChangeCatalog}) and check the description still carries the per-id hints,
 * without which a model shown sixteen ids tends to pick the first one.
 */
public class DashboardChartTypeSchemaTest {

    private static JsonNode dashboardSchema() {
        return AbstractNlAskProvider.dashboardInputSchema(
                new ObjectMapper().createObjectNode().put("type", "object"));
    }

    private static List<String> advertisedChartTypes() {
        JsonNode schema = dashboardSchema();
        JsonNode chartType = schema.path("properties")
                .path("tiles")
                .path("items")
                .path("properties")
                .path("chartType");
        assertTrue("chartType property missing from the dashboard tile schema", chartType.has("enum"));
        List<String> ids = new ArrayList<>();
        chartType.path("enum").forEach(n -> ids.add(n.asText()));
        return ids;
    }

    @Test
    public void dashboardChartTypeEnumCoversTheWholeCatalog() {
        List<String> advertised = advertisedChartTypes();
        assertEquals(
                "every catalog id must be reachable as a dashboard tile chartType",
                AiViewChangeCatalog.CHART_TYPE_IDS,
                new java.util.HashSet<>(advertised));
    }

    @Test
    public void dashboardChartTypeEnumAdvertisesNoIdOutsideTheCatalog() {
        List<String> advertised = advertisedChartTypes();
        for (String id : advertised) {
            assertTrue(
                    "chartType '" + id + "' is advertised but absent from the canonical catalog",
                    AiViewChangeCatalog.CHART_TYPE_IDS.contains(id));
        }
    }

    @Test
    public void dashboardChartTypeDescriptionCarriesThePerIdHints() {
        String description = dashboardSchema()
                .path("properties")
                .path("tiles")
                .path("items")
                .path("properties")
                .path("chartType")
                .path("description")
                .asText();
        // One hint line per catalog entry — the same text the view-change tool gets.
        assertTrue(
                "description must enumerate the chart catalog for the model",
                description.contains(AiViewChangeCatalog.CHART_TYPES.get(0).get("hint")));
        for (java.util.Map<String, String> entry : AiViewChangeCatalog.CHART_TYPES) {
            assertTrue("missing hint for chartType '" + entry.get("id") + "'", description.contains(entry.get("hint")));
        }
    }
}
