/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.query2.ThinQuery;

/**
 * Pure unit tests for {@link AiSchemaConverter} — no olap4j, no Mondrian.
 * Builds a hand-rolled {@link AiSchema} per test and asserts on the MDX
 * the converter emits and the validation errors it raises.
 */
public class AiSchemaConverterTest {

    private AiSchema schema;
    private AiSchemaConverter converter;

    @Before
    public void setUp() {
        // FoodMart Sales-ish: two measures, two dims (Time -> [Time By] -> Year/Quarter/Month,
        // Product -> single hierarchy -> Department).
        schema = new AiSchema(
                "foodmart/FoodMart/FoodMart/Sales",
                "Sales",
                "[FoodMart].[Sales]");
        schema.measures.put(AiSchema.key("Store Sales"),
                new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
        schema.measures.put(AiSchema.key("Unit Sales"),
                new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"),
                new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        timeBy.levels.put(AiSchema.key("Quarter"),
                new AiSchema.Level("Quarter", "[Time].[Time By].[Quarter]"));
        timeBy.levels.put(AiSchema.key("Month"),
                new AiSchema.Level("Month", "[Time].[Time By].[Month]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy productH = new AiSchema.Hierarchy("Product", "[Product].[Product]");
        productH.levels.put(AiSchema.key("Department"),
                new AiSchema.Level("Department", "[Product].[Product].[Department]"));
        product.hierarchies.put(AiSchema.key("Product"), productH);
        schema.dimensions.put(AiSchema.key("Product"), product);

        converter = new AiSchemaConverter();
    }

    private AiQueryRequest baseReq() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        return req;
    }

    /* ------------------------------ happy path ------------------------------ */

    @Test
    public void simpleQueryEmitsValidMdx() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));

        ThinQuery tq = converter.convert(req, schema);

        assertNotNull(tq);
        assertEquals(ThinQuery.Type.MDX, tq.getType());
        assertNotNull("query name must be assigned", tq.getName());
        String mdx = tq.getMdx();
        assertTrue(mdx, mdx.contains("[Measures].[Store Sales]"));
        assertTrue(mdx, mdx.contains("[Time].[Time By].[Year].Members"));
        assertTrue(mdx, mdx.contains("FROM [Sales]"));
        assertTrue(mdx, mdx.contains("ON COLUMNS"));
        assertTrue(mdx, mdx.contains("ON ROWS"));
    }

    @Test
    public void hierarchyOmittedDefaultsToSoleHierarchy() {
        AiQueryRequest req = baseReq();
        AiAxisSelection axis = new AiAxisSelection();
        axis.setDimension("Product");
        axis.setLevel("Department"); // hierarchy null — Product has only one
        req.setRows(Collections.singletonList(axis));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("[Product].[Product].[Department].Members"));
    }

    @Test
    public void explicitMembersOverrideAllMembers() {
        AiQueryRequest req = baseReq();
        AiAxisSelection axis = new AiAxisSelection("Time", "Time By", "Year");
        axis.setMembers(Arrays.asList(
                "[Time].[Time By].[Year].&[1997]",
                "[Time].[Time By].[Year].&[1998]"));
        req.setRows(Collections.singletonList(axis));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("{[Time].[Time By].[Year].&[1997], [Time].[Time By].[Year].&[1998]}"));
    }

    @Test
    public void multipleRowsCrossJoin() {
        AiQueryRequest req = baseReq();
        req.setRows(Arrays.asList(
                new AiAxisSelection("Time", "Time By", "Year"),
                new AiAxisSelection("Product", "Product", "Department")));

        ThinQuery tq = converter.convert(req, schema);

        String mdx = tq.getMdx();
        assertTrue(mdx, mdx.contains("CROSSJOIN("));
        assertTrue(mdx, mdx.contains("[Time].[Time By].[Year].Members"));
        assertTrue(mdx, mdx.contains("[Product].[Product].[Department].Members"));
    }

    @Test
    public void filterBecomesWhereSlicer() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection("Time", "Time By", "Year",
                Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        String mdx = tq.getMdx();
        assertTrue(mdx, mdx.contains("WHERE ([Time].[Time By].[Year].&[1997])"));
    }

    @Test
    public void limitWrapsRowsInHead() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        req.setLimit(50);

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("HEAD([Time].[Time By].[Year].Members, 50)"));
    }

    @Test
    public void nonEmptyTogglesNonEmptyKeyword() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        req.setNonEmpty(false);

        ThinQuery tq = converter.convert(req, schema);

        assertTrue("MDX should NOT contain NON EMPTY when nonEmpty=false: " + tq.getMdx(),
                !tq.getMdx().contains("NON EMPTY"));
    }

    /* ----------------------------- validation -------------------------------- */

    @Test
    public void unknownMeasureThrows() {
        AiQueryRequest req = baseReq();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Bogus Measure")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertEquals("measures[].name", e.getField());
            assertTrue("error names available measures: " + e.getAvailable(),
                    e.getAvailable().contains("Store Sales"));
            assertTrue("error names available measures: " + e.getAvailable(),
                    e.getAvailable().contains("Unit Sales"));
        }
    }

    @Test
    public void unknownDimensionThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Nonsense", "x", "y")));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".dimension"));
            assertTrue(e.getAvailable().contains("Time"));
            assertTrue(e.getAvailable().contains("Product"));
        }
    }

    @Test
    public void unknownHierarchyThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Other Hierarchy", "Year")));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".hierarchy"));
            assertTrue(e.getAvailable().contains("Time By"));
        }
    }

    @Test
    public void unknownLevelThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Decade")));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".level"));
            assertTrue(e.getAvailable().contains("Year"));
            assertTrue(e.getAvailable().contains("Quarter"));
        }
    }

    @Test
    public void emptyMeasuresThrows() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertEquals("measures", e.getField());
        }
    }

    @Test
    public void filterWithoutMembersThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Year");
        // no members
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".members"));
        }
    }
}
