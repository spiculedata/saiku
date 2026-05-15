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
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
        schema.measures.put(AiSchema.key("Unit Sales"), new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        timeBy.levels.put(AiSchema.key("Quarter"), new AiSchema.Level("Quarter", "[Time].[Time By].[Quarter]"));
        timeBy.levels.put(AiSchema.key("Month"), new AiSchema.Level("Month", "[Time].[Time By].[Month]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy productH = new AiSchema.Hierarchy("Product", "[Product].[Product]");
        productH.levels.put(
                AiSchema.key("Department"), new AiSchema.Level("Department", "[Product].[Product].[Department]"));
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
        axis.setMembers(Arrays.asList("[Time].[Time By].[Year].&[1997]", "[Time].[Time By].[Year].&[1998]"));
        req.setRows(Collections.singletonList(axis));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(
                tq.getMdx(),
                tq.getMdx().contains("{[Time].[Time By].[Year].&[1997], [Time].[Time By].[Year].&[1998]}"));
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
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
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

        assertTrue(
                "MDX should NOT contain NON EMPTY when nonEmpty=false: " + tq.getMdx(),
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
            assertTrue(
                    "error names available measures: " + e.getAvailable(),
                    e.getAvailable().contains("Store Sales"));
            assertTrue(
                    "error names available measures: " + e.getAvailable(),
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

    /* ----------------------- v2: filter operators ---------------------------- */

    @Test
    public void filterNotInEmitsExcept() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f =
                new AiFilterSelection("Time", "Time By", "Year", Arrays.asList("[Time].[Time By].[Year].&[1997]"));
        f.setOp("not_in");
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(
                tq.getMdx(),
                tq.getMdx().contains("Except([Time].[Time By].[Year].Members, {[Time].[Time By].[Year].&[1997]})"));
    }

    @Test
    public void filterBetweenEmitsRange() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time",
                "Time By",
                "Year",
                Arrays.asList("[Time].[Time By].[Year].&[1997]", "[Time].[Time By].[Year].&[1999]"));
        f.setOp("between");
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(
                tq.getMdx(), tq.getMdx().contains("[Time].[Time By].[Year].&[1997] : [Time].[Time By].[Year].&[1999]"));
    }

    @Test
    public void filterBetweenWithWrongMemberCountThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
        f.setOp("between");
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".members"));
        }
    }

    @Test
    public void filterDescendantsOfEmitsDescendants() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
        f.setOp("descendants_of");
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("Descendants([Time].[Time By].[Year].&[1997])"));
    }

    @Test
    public void filterUnknownOpThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
        f.setOp("nonsensical_op");
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".op"));
            assertTrue(e.getAvailable().contains("between"));
            assertTrue(e.getAvailable().contains("relative"));
        }
    }

    /* ----------------------- v3: relative time filters ----------------------- */

    @Test
    public void filterRelativeLastNDaysEmitsTail() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Month");
        f.setOp("relative");
        f.setValue("last_n_months");
        f.setN(3);
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("Tail([Time].[Time By].[Month].Members, 3)"));
    }

    @Test
    public void filterRelativeLastNDefaultsToOne() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Year");
        f.setOp("relative");
        f.setValue("last_n_years");
        // n unset → defaults to 1
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("Tail([Time].[Time By].[Year].Members, 1)"));
    }

    @Test
    public void filterRelativeYtdEmitsYtdFunction() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Month");
        f.setOp("relative");
        f.setValue("ytd");
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("Ytd()"));
    }

    @Test
    public void filterRelativePreviousPeriodEmitsTailItem() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Year");
        f.setOp("relative");
        f.setValue("previous_period");
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("Tail([Time].[Time By].[Year].Members, 2).Item(0)"));
    }

    @Test
    public void filterRelativeSamePeriodLastYearRejected() {
        // same_period_last_year was intentionally dropped from v1 — at Year level
        // it's identical to previous_period; at finer levels honest semantics
        // require a year-aware ParallelPeriod we don't yet introspect. Confirm
        // the converter refuses it rather than silently aliasing.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Year");
        f.setOp("relative");
        f.setValue("same_period_last_year");
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected validation error — same_period_last_year is not a v1 preset");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".value"));
            assertTrue(
                    "error should not list same_period_last_year",
                    !e.getAvailable().contains("same_period_last_year"));
            assertTrue(e.getAvailable().contains("previous_period"));
        }
    }

    @Test
    public void filterRelativeUnknownValueThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Year");
        f.setOp("relative");
        f.setValue("invented_preset");
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".value"));
            assertTrue(e.getAvailable().contains("last_n_days"));
            assertTrue(e.getAvailable().contains("ytd"));
        }
    }

    @Test
    public void filterRelativeMissingValueThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Year");
        f.setOp("relative");
        // value unset
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".value"));
        }
    }

    /* ---------------------- v2: order + TopCount ----------------------------- */

    @Test
    public void orderWithLimitDescEmitsTopCount() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        AiOrderBy ob = new AiOrderBy();
        ob.setBy("Store Sales");
        ob.setDirection("desc");
        req.setOrder(Collections.singletonList(ob));
        req.setLimit(5);

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(
                tq.getMdx(),
                tq.getMdx().contains("TopCount([Time].[Time By].[Year].Members, 5, [Measures].[Store Sales])"));
        assertTrue("should not also wrap in HEAD: " + tq.getMdx(), !tq.getMdx().contains("HEAD("));
    }

    @Test
    public void orderWithLimitAscEmitsBottomCount() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        AiOrderBy ob = new AiOrderBy();
        ob.setBy("Store Sales");
        ob.setDirection("asc");
        req.setOrder(Collections.singletonList(ob));
        req.setLimit(5);

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(
                tq.getMdx(),
                tq.getMdx().contains("BottomCount([Time].[Time By].[Year].Members, 5, [Measures].[Store Sales])"));
    }

    @Test
    public void orderAloneEmitsOrder() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        AiOrderBy ob = new AiOrderBy();
        ob.setBy("Store Sales");
        ob.setDirection("desc");
        req.setOrder(Collections.singletonList(ob));
        // no limit

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(
                tq.getMdx(),
                tq.getMdx().contains("Order([Time].[Time By].[Year].Members, [Measures].[Store Sales], BDESC)"));
    }

    @Test
    public void orderByUnknownMeasureThrows() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        AiOrderBy ob = new AiOrderBy();
        ob.setBy("Bogus Measure");
        req.setOrder(Collections.singletonList(ob));
        req.setLimit(5);

        try {
            converter.convert(req, schema);
            fail("expected validation error");
        } catch (AiValidationException e) {
            assertEquals("measures[].name", e.getField());
        }
    }
}
