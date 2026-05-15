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
    public void filterDescendantsOfInSlicerEmitsMemberOnly() {
        // In WHERE position, a non-leaf member is already the aggregate of
        // all its descendants — Mondrian's natural roll-up does the work.
        // Emitting Descendants(member) here hits function-overload parsing
        // edge cases on the live engine, so we collapse to just the member.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
        f.setOp("descendants_of");
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);

        assertTrue(tq.getMdx(), tq.getMdx().contains("WHERE ([Time].[Time By].[Year].&[1997])"));
        assertTrue(
                "descendants_of in slicer must NOT emit Descendants() or Aggregate(): " + tq.getMdx(),
                !tq.getMdx().contains("Descendants(") && !tq.getMdx().contains("Aggregate("));
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

    /* ---------------- v3.1: same-hierarchy axis shape rules ----------------- */

    @Test
    public void sameHierarchyRowsEmitHierarchizeNotCrossjoin() {
        // Two row axes on the same hierarchy ([Time].[Time By] at Year + Quarter)
        // must NOT crossjoin — Mondrian rejects "Tuple contains more than one
        // member of hierarchy". Emit Hierarchize({set1, set2}) instead.
        AiQueryRequest req = baseReq();
        req.setRows(Arrays.asList(
                new AiAxisSelection("Time", "Time By", "Year"), new AiAxisSelection("Time", "Time By", "Quarter")));

        ThinQuery tq = converter.convert(req, schema);

        String mdx = tq.getMdx();
        assertTrue("should emit Hierarchize for same-hierarchy rows: " + mdx, mdx.contains("Hierarchize({"));
        assertTrue("Year members present: " + mdx, mdx.contains("[Time].[Time By].[Year].Members"));
        assertTrue("Quarter members present: " + mdx, mdx.contains("[Time].[Time By].[Quarter].Members"));
        assertTrue("should NOT crossjoin two sets from the same hierarchy: " + mdx, !mdx.contains("CROSSJOIN([Time]"));
    }

    @Test
    public void mixedSameHierarchyAndDistinctHierarchyRowsGroup() {
        // Time-Year + Time-Quarter (same hierarchy) + Product-Department
        // (distinct hierarchy) → Hierarchize on the Time group, then
        // CROSSJOIN that group with the Product set.
        AiQueryRequest req = baseReq();
        req.setRows(Arrays.asList(
                new AiAxisSelection("Time", "Time By", "Year"),
                new AiAxisSelection("Time", "Time By", "Quarter"),
                new AiAxisSelection("Product", "Product", "Department")));

        ThinQuery tq = converter.convert(req, schema);

        String mdx = tq.getMdx();
        assertTrue(mdx, mdx.contains("Hierarchize({"));
        assertTrue(mdx, mdx.contains("CROSSJOIN("));
        assertTrue(mdx, mdx.contains("[Product].[Product].[Department].Members"));
    }

    @Test
    public void sameHierarchyColumnsEmitHierarchize() {
        // Same fix has to apply on the columns axis — agents mixing levels
        // there hit the identical Mondrian error.
        AiQueryRequest req = baseReq();
        req.setColumns(Arrays.asList(
                new AiAxisSelection("Time", "Time By", "Year"), new AiAxisSelection("Time", "Time By", "Quarter")));

        ThinQuery tq = converter.convert(req, schema);

        String mdx = tq.getMdx();
        assertTrue("columns should emit Hierarchize for same-hierarchy entries: " + mdx, mdx.contains("Hierarchize({"));
        assertTrue(
                "should NOT crossjoin same-hierarchy columns: " + mdx,
                !mdx.contains("CROSSJOIN(CROSSJOIN(")); // measures × col1 × col2 would double-CROSSJOIN
    }

    /* ---------------- v3.1: slicer Aggregate wrapping --------------------- */

    @Test
    public void slicerSingleInMemberNotWrapped() {
        // Single-member 'in' is a bare member — Aggregate wrap is wasted.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);
        assertTrue(tq.getMdx(), tq.getMdx().contains("WHERE ([Time].[Time By].[Year].&[1997])"));
        assertTrue(
                "single 'in' member must not be Aggregate-wrapped: " + tq.getMdx(),
                !tq.getMdx().contains("Aggregate("));
    }

    @Test
    public void slicerMultipleInMembersAsBareSet() {
        // Mondrian's slicer accepts a bare set and applies implicit
        // aggregation across its members. Wrapping with Aggregate()
        // confuses the parser ("No function matches signature
        // '{<Numeric Expression>}'") on the live engine when the leaf
        // segment is digit-only — emit the bare set form.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time",
                "Time By",
                "Year",
                Arrays.asList("[Time].[Time By].[Year].&[1997]", "[Time].[Time By].[Year].&[1998]"));
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);
        assertTrue(
                tq.getMdx(),
                tq.getMdx().contains("WHERE ({[Time].[Time By].[Year].&[1997], [Time].[Time By].[Year].&[1998]})"));
        assertTrue(
                "multi-member 'in' must not be Aggregate-wrapped: " + tq.getMdx(),
                !tq.getMdx().contains("Aggregate("));
    }

    @Test
    public void slicerNotInAsBareExcept() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]"));
        f.setOp("not_in");
        req.setFilters(Collections.singletonList(f));

        ThinQuery tq = converter.convert(req, schema);
        assertTrue(tq.getMdx(), tq.getMdx().contains("WHERE (Except("));
        assertTrue(
                "not_in slicer must not be Aggregate-wrapped: " + tq.getMdx(),
                !tq.getMdx().contains("Aggregate("));
    }

    @Test
    public void slicerBetweenEmitsBareRange() {
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
                tq.getMdx(),
                tq.getMdx().contains("WHERE ({[Time].[Time By].[Year].&[1997] : [Time].[Time By].[Year].&[1999]})"));
        assertTrue(
                "between slicer must not be Aggregate-wrapped: " + tq.getMdx(),
                !tq.getMdx().contains("Aggregate("));
    }

    @Test
    public void slicerRelativeSetEmitsBareSet() {
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
        assertTrue(tq.getMdx(), tq.getMdx().contains("WHERE (Tail([Time].[Time By].[Month].Members, 3))"));
        assertTrue(
                "relative slicer must not be Aggregate-wrapped: " + tq.getMdx(),
                !tq.getMdx().contains("Aggregate("));
    }

    @Test
    public void slicerRelativePreviousPeriodNotWrapped() {
        // previous_period resolves to a single member via .Item(0);
        // Aggregate wrap is unnecessary.
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
        assertTrue(tq.getMdx(), tq.getMdx().contains("WHERE (Tail([Time].[Time By].[Year].Members, 2).Item(0))"));
        assertTrue(
                "previous_period must not be Aggregate-wrapped: " + tq.getMdx(),
                !tq.getMdx().contains("Aggregate("));
    }

    @Test
    public void multipleFiltersOnSameHierarchyRejected() {
        // Two filters targeting one hierarchy would produce
        // WHERE (m1, m2) — Mondrian's "tuple has more than one member of
        // hierarchy". Reject up-front with a teaching error.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        req.setFilters(Arrays.asList(
                new AiFilterSelection(
                        "Time", "Time By", "Year", Collections.singletonList("[Time].[Time By].[Year].&[1997]")),
                new AiFilterSelection(
                        "Time", "Time By", "Quarter", Collections.singletonList("[Time].[Time By].[Quarter].&[Q1]"))));

        try {
            converter.convert(req, schema);
            fail("expected validation error — same-hierarchy filters illegal in slicer");
        } catch (AiValidationException e) {
            assertTrue(e.getField(), e.getField().endsWith(".hierarchy"));
            assertTrue(e.getMessage(), e.getMessage().contains("Multiple filters target hierarchy"));
        }
    }

    /* ----------------------- saiku#777: VISUALTOTALS ----------------------- */

    @Test
    public void visualTotalsTrueWrapsRowsAxisInVisualtotals() {
        AiQueryRequest req = baseReq();
        AiAxisSelection axis = new AiAxisSelection("Product", "Product", "Department");
        axis.setMembers(Arrays.asList("[Product].[Product].[Drink]", "[Product].[Product].[Food]"));
        req.setRows(Collections.singletonList(axis));
        req.setVisualTotals(true);

        ThinQuery tq = converter.convert(req, schema);
        String mdx = tq.getMdx();
        assertTrue("rows axis wrapped in VISUALTOTALS — got: " + mdx, mdx.contains("VISUALTOTALS"));
    }

    @Test
    public void visualTotalsFalseLeavesRowsAxisBare() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        req.setVisualTotals(false);

        ThinQuery tq = converter.convert(req, schema);
        String mdx = tq.getMdx();
        assertTrue("no VISUALTOTALS when flag false — got: " + mdx, !mdx.contains("VISUALTOTALS"));
    }

    /* ----------------------- saiku#775: named sets ----------------------- */

    @Test
    public void singleNamedSetEmittedAsWithClause() {
        AiQueryRequest req = baseReq();
        req.setNamedSets(Collections.singletonList(new AiNamedSet(
                "Top Products",
                "TopCount([Product].[Product].[Product Family].Members, 3, [Measures].[Store Sales])")));

        ThinQuery tq = converter.convert(req, schema);
        String mdx = tq.getMdx();
        assertNotNull(mdx);
        assertTrue("MDX begins with the WITH clause — got: " + mdx, mdx.startsWith("WITH"));
        assertTrue("named-set name appears bracketed — got: " + mdx, mdx.contains("SET [Top Products] AS (TopCount("));
        assertTrue("SELECT still emitted after WITH", mdx.contains("\nSELECT "));
    }

    @Test
    public void twoNamedSetsShareOneWithClause() {
        AiQueryRequest req = baseReq();
        req.setNamedSets(Arrays.asList(
                new AiNamedSet("First", "{[Product].[Product].[Drink]}"),
                new AiNamedSet("Second", "{[Product].[Product].[Food]}")));
        ThinQuery tq = converter.convert(req, schema);
        String mdx = tq.getMdx();
        assertEquals("only one WITH keyword for multiple SETs", 1, countOccurrences(mdx, "WITH"));
        assertTrue("first SET emitted", mdx.contains("SET [First] AS"));
        assertTrue("second SET emitted", mdx.contains("SET [Second] AS"));
    }

    @Test
    public void preBracketedNameLeftIntact() {
        AiQueryRequest req = baseReq();
        req.setNamedSets(Collections.singletonList(new AiNamedSet("[Premium]", "{[Product].[Product].[Drink]}")));
        ThinQuery tq = converter.convert(req, schema);
        // Agent passes [Premium] already bracketed; converter doesn't
        // double-bracket.
        assertTrue("name not re-bracketed — got: " + tq.getMdx(), tq.getMdx().contains("SET [Premium] AS"));
        assertTrue("no double-bracket [[", !tq.getMdx().contains("[[Premium]"));
    }

    @Test
    public void duplicateNamedSetNameRejected() {
        AiQueryRequest req = baseReq();
        req.setNamedSets(Arrays.asList(
                new AiNamedSet("dup", "{[Product].[Product].[Drink]}"),
                new AiNamedSet("dup", "{[Product].[Product].[Food]}")));
        try {
            converter.convert(req, schema);
            fail("expected AiValidationException for duplicate named-set name");
        } catch (AiValidationException e) {
            assertEquals("namedSets[1].name", e.getField());
            assertTrue(e.getMessage(), e.getMessage().contains("Duplicate named-set name"));
        }
    }

    @Test
    public void blankNamedSetNameRejected() {
        AiQueryRequest req = baseReq();
        req.setNamedSets(Collections.singletonList(new AiNamedSet("", "{[Product].[Product].[Drink]}")));
        try {
            converter.convert(req, schema);
            fail("expected validation error for blank name");
        } catch (AiValidationException e) {
            assertEquals("namedSets[0].name", e.getField());
        }
    }

    @Test
    public void blankNamedSetExpressionRejected() {
        AiQueryRequest req = baseReq();
        req.setNamedSets(Collections.singletonList(new AiNamedSet("Foo", "")));
        try {
            converter.convert(req, schema);
            fail("expected validation error for blank expression");
        } catch (AiValidationException e) {
            assertEquals("namedSets[0].expression", e.getField());
        }
    }

    private static int countOccurrences(String s, String token) {
        int n = 0;
        int from = 0;
        while ((from = s.indexOf(token, from)) != -1) {
            n++;
            from += token.length();
        }
        return n;
    }
}
