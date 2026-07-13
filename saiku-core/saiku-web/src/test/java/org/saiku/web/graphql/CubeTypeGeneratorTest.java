/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiSchema;

/**
 * Exercises {@link CubeTypeGenerator} sanitisation, SDL emission, and row marshalling in
 * isolation — no Spring, no live cube. Pins the DX-facing naming rules so future changes to
 * the schema-per-cube shape don't silently break codegen consumers.
 */
public class CubeTypeGeneratorTest {

    @Test
    public void screamingSnakeConvertsSpacesAndSpecialChars() {
        assertEquals("STORE_SALES", CubeTypeGenerator.screamingSnake("Store Sales"));
        assertEquals("STORE_SALES", CubeTypeGenerator.screamingSnake("store  sales"));
        assertEquals("PRODUCT_FAMILY", CubeTypeGenerator.screamingSnake("Product-Family"));
        assertEquals("Y2025", CubeTypeGenerator.screamingSnake("Y2025"));
        assertEquals("_2025", CubeTypeGenerator.screamingSnake("2025"));
    }

    @Test
    public void screamingSnakePreservesUpperCaseBoundaries() {
        assertEquals("STORE_SALES", CubeTypeGenerator.screamingSnake("StoreSales"));
    }

    @Test
    public void camelCaseHandlesSpacesUnderscoresAndDigits() {
        assertEquals("storeSales", CubeTypeGenerator.camelCase("Store Sales"));
        assertEquals("productFamily", CubeTypeGenerator.camelCase("Product-Family"));
        assertEquals("orders", CubeTypeGenerator.camelCase("Orders"));
        assertEquals("_2025", CubeTypeGenerator.camelCase("2025"));
    }

    @Test
    public void pascalCaseCapitalisesFirstLetter() {
        assertEquals("StoreSales", CubeTypeGenerator.pascalCase("Store Sales"));
        assertEquals("Sales", CubeTypeGenerator.pascalCase("sales"));
    }

    @Test
    public void sdlFragmentContainsExpectedShape() {
        AiCubeSummary summary = summary("mem", "FoodMart", "FoodMart", "Sales");
        AiSchema schema = new AiSchema("sales", "Sales", "[Sales]");
        addMeasure(schema, "Store Sales");
        addMeasure(schema, "Unit Sales");
        addLevel(schema, "Product", "Product", "Product Family");
        addLevel(schema, "Time", "Time", "Year");

        CubeTypeGenerator gen = new CubeTypeGenerator(summary, schema, "sales");
        String sdl = gen.toSdl();

        // Enums
        assertTrue(sdl.contains("enum SalesMeasure"));
        assertTrue(sdl.contains("STORE_SALES"));
        assertTrue(sdl.contains("UNIT_SALES"));
        assertTrue(sdl.contains("enum SalesLevel"));
        assertTrue(sdl.contains("PRODUCT__PRODUCT_FAMILY"));
        assertTrue(sdl.contains("TIME__YEAR"));

        // Row output — one field per measure (Float) and level (String)
        assertTrue(sdl.contains("type SalesRow"));
        assertTrue(sdl.contains("storeSales: Float"));
        assertTrue(sdl.contains("unitSales: Float"));
        assertTrue(sdl.contains(": String"));

        // Query field extension
        assertTrue(sdl.contains("extend type Query"));
        assertTrue(sdl.contains("sales("));
        assertTrue(sdl.contains("measures: [SalesMeasure!]!"));
        assertTrue(sdl.contains("rows: [SalesLevel!]"));
        assertTrue(sdl.contains("columns: [SalesLevel!]"));
        assertTrue(sdl.contains("): [SalesRow!]!"));
    }

    @Test
    public void sdlFragmentIsEmptyWhenNoMeasures() {
        // A cube with no measures is unqueryable through /ai/query, so the generator produces
        // no SDL rather than emitting an empty enum (which GraphQL rejects).
        AiCubeSummary summary = summary("mem", "FoodMart", "FoodMart", "Empty");
        AiSchema schema = new AiSchema("empty", "Empty", "[Empty]");
        CubeTypeGenerator gen = new CubeTypeGenerator(summary, schema, "empty");
        assertEquals("", gen.toSdl());
    }

    @Test
    public void enumRoundTripsToCanonicalName() {
        AiCubeSummary summary = summary("mem", "FoodMart", "FoodMart", "Sales");
        AiSchema schema = new AiSchema("sales", "Sales", "[Sales]");
        addMeasure(schema, "Store Sales");
        addLevel(schema, "Product", "Product", "Product Family");

        CubeTypeGenerator gen = new CubeTypeGenerator(summary, schema, "sales");
        assertEquals("Store Sales", gen.measureEnumToCanonical("STORE_SALES"));
        CubeTypeGenerator.AxisRef axis = gen.levelEnumToAxis("PRODUCT__PRODUCT_FAMILY");
        assertNotNull(axis);
        assertEquals("Product", axis.dimension);
        assertEquals("Product", axis.hierarchy);
        assertEquals("Product Family", axis.level);
    }

    @Test
    public void materialiseRowMapsBackToCamelFields() {
        AiCubeSummary summary = summary("mem", "FoodMart", "FoodMart", "Sales");
        AiSchema schema = new AiSchema("sales", "Sales", "[Sales]");
        addMeasure(schema, "Store Sales");
        addLevel(schema, "Product", "Product", "Product Family");

        CubeTypeGenerator gen = new CubeTypeGenerator(summary, schema, "sales");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("Store Sales", 48836.21);
        record.put("Product Family", "Drink");

        Map<String, Object> row = gen.materialiseRow(
                record,
                List.of("Store Sales"),
                List.of(new CubeTypeGenerator.AxisRef("Product", "Product", "Product Family")));

        assertEquals(2, row.size());
        assertEquals(48836.21, row.get("storeSales"));
        assertEquals("Drink", row.get("productFamily"));
    }

    @Test
    public void materialiseRowCoercesStringNumbersToDouble() {
        AiCubeSummary summary = summary("mem", "FoodMart", "FoodMart", "Sales");
        AiSchema schema = new AiSchema("sales", "Sales", "[Sales]");
        addMeasure(schema, "Store Sales");

        CubeTypeGenerator gen = new CubeTypeGenerator(summary, schema, "sales");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("Store Sales", "48,836.21");

        Map<String, Object> row = gen.materialiseRow(record, List.of("Store Sales"), List.of());
        assertEquals(48836.21, (Double) row.get("storeSales"), 0.001);
    }

    @Test
    public void materialiseRowReturnsNullForUnknownMeasure() {
        AiCubeSummary summary = summary("mem", "FoodMart", "FoodMart", "Sales");
        AiSchema schema = new AiSchema("sales", "Sales", "[Sales]");
        addMeasure(schema, "Store Sales");

        CubeTypeGenerator gen = new CubeTypeGenerator(summary, schema, "sales");
        Map<String, Object> record = new LinkedHashMap<>();
        // Record doesn't include Store Sales at all
        Map<String, Object> row = gen.materialiseRow(record, List.of("Store Sales"), List.of());
        assertTrue(row.containsKey("storeSales"));
        assertNull(row.get("storeSales"));
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private static AiCubeSummary summary(String connection, String catalog, String schema, String cube) {
        AiCubeSummary s = new AiCubeSummary();
        s.setConnectionName(connection);
        s.setCatalog(catalog);
        s.setSchema(schema);
        s.setCubeName(cube);
        return s;
    }

    private static void addMeasure(AiSchema schema, String name) {
        AiSchema.Measure m = new AiSchema.Measure(name, "[Measures].[" + name + "]");
        schema.measures.put(name.toLowerCase(), m);
    }

    private static void addLevel(AiSchema schema, String dimensionName, String hierarchyName, String levelName) {
        AiSchema.Dimension dim = schema.dimensions.computeIfAbsent(
                dimensionName.toLowerCase(), k -> new AiSchema.Dimension(dimensionName, "[" + dimensionName + "]"));
        AiSchema.Hierarchy hier = dim.hierarchies.computeIfAbsent(
                hierarchyName.toLowerCase(),
                k -> new AiSchema.Hierarchy(hierarchyName, "[" + dimensionName + "].[" + hierarchyName + "]"));
        AiSchema.Level lvl =
                new AiSchema.Level(levelName, "[" + dimensionName + "].[" + hierarchyName + "].[" + levelName + "]");
        hier.levels.put(levelName.toLowerCase(), lvl);
    }
}
