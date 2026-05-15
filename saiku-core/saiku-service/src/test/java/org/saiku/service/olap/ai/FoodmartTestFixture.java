/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * Minimal in-process FoodMart-shaped {@link AiSchema} for testing
 * {@code docs/AI-QUERY-API.md} examples. Only includes the dims /
 * hierarchies / measures the doc examples reference — not a full FoodMart
 * mirror. Extend it as new examples are added to the doc.
 *
 * <p>Purpose-built for {@link AiQueryDocsExamplesTest} (Phase 4 / saiku#10):
 * lets the test load each doc example body and assert the converter
 * produces the {@code generatedMdx} string the doc promises.
 */
final class FoodmartTestFixture {
    static final String CONNECTION = "unknown_foodmart";
    static final String CATALOG = "FoodMart";
    static final String SCHEMA = "FoodMart";
    static final String CUBE = "Sales";

    private FoodmartTestFixture() {}

    static AiCubeRef cubeRef() {
        return new AiCubeRef(CONNECTION, CATALOG, SCHEMA, CUBE);
    }

    static AiSchema directSchema() {
        AiSchema s = new AiSchema(CONNECTION + "/" + CATALOG + "/" + SCHEMA + "/" + CUBE, CUBE, "[FoodMart].[Sales]");

        s.measures.put(AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
        s.measures.put(AiSchema.key("Unit Sales"), new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]"));

        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy products = new AiSchema.Hierarchy("Products", "[Product].[Products]");
        products.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Product].[Products].[(All)]"));
        products.levels.put(
                AiSchema.key("Product Family"),
                new AiSchema.Level("Product Family", "[Product].[Products].[Product Family]"));
        product.hierarchies.put(AiSchema.key("Products"), products);
        s.dimensions.put(AiSchema.key("Product"), product);

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Time].[Time By].[(All)]"));
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        timeBy.levels.put(AiSchema.key("Day"), new AiSchema.Level("Day", "[Time].[Time By].[Day]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        s.dimensions.put(AiSchema.key("Time"), time);

        return s;
    }
}
