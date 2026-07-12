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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.olap4j.impl.NamedListImpl;
import org.olap4j.metadata.NamedList;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.olap.query2.ThinSelection;

/**
 * Unit tests for {@link ThinQueryFilterMerge}. Hand-rolls a small FoodMart-
 * style {@link AiSchema} + a few {@link ThinQuery} fixtures; asserts on the
 * resulting axis structure after the merge.
 */
public class ThinQueryFilterMergeTest {

    private AiSchema schema;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time", "[Time].[Time]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time].[Year]"));
        timeBy.levels.put(AiSchema.key("Quarter"), new AiSchema.Level("Quarter", "[Time].[Time].[Quarter]"));
        time.hierarchies.put(AiSchema.key("Time"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy productH = new AiSchema.Hierarchy("Product", "[Product].[Product]");
        productH.levels.put(
                AiSchema.key("Product Family"),
                new AiSchema.Level("Product Family", "[Product].[Product].[Product Family]"));
        productH.levels.put(
                AiSchema.key("Product Department"),
                new AiSchema.Level("Product Department", "[Product].[Product].[Product Department]"));
        product.hierarchies.put(AiSchema.key("Product"), productH);
        schema.dimensions.put(AiSchema.key("Product"), product);
    }

    /* --------------------------- fixtures ---------------------------- */

    private static ThinQuery querymodel() {
        ThinQuery tq = new ThinQuery();
        tq.setName("test");
        tq.setQueryModel(new ThinQueryModel());
        return tq;
    }

    private static ThinHierarchy hierWithLevel(
            String hierUniqueName, String hierName, String dimName, String levelName, List<String> memberNames) {
        ThinSelection sel = new ThinSelection(
                ThinSelection.Type.INCLUSION,
                memberNames.stream()
                        .map(m -> new org.saiku.olap.query2.ThinMember(m, m, m))
                        .toList());
        ThinLevel lvl = new ThinLevel(levelName, levelName, sel, new ArrayList<>());
        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(levelName, lvl);
        return new ThinHierarchy(hierUniqueName, hierName, dimName, levels);
    }

    private static void putAxis(ThinQueryModel model, AxisLocation loc, ThinHierarchy... hierarchies) {
        NamedList<ThinHierarchy> nl = new NamedListImpl<>();
        Collections.addAll(nl, hierarchies);
        model.getAxes().put(loc, new ThinAxis(loc, nl, false, new ArrayList<>()));
    }

    private static AiFilterSelection filter(String dim, String hier, String level, String... members) {
        return new AiFilterSelection(dim, hier, level, new ArrayList<>(Arrays.asList(members)));
    }

    /* ------------------------------ tests ----------------------------- */

    @Test
    public void unknownHierarchyIsSkipped() {
        ThinQuery tq = querymodel();
        ThinQueryFilterMerge.apply(
                tq, Collections.singletonList(filter("Made Up", "Made Up", "Year", "[Made Up].[2026]")), schema);
        assertNull(tq.getQueryModel().getAxis(AxisLocation.FILTER));
    }

    @Test
    public void emptyMembersIsSkipped() {
        ThinQuery tq = querymodel();
        ThinQueryFilterMerge.apply(tq, Collections.singletonList(filter("Time", "Time", "Year")), schema);
        assertNull(tq.getQueryModel().getAxis(AxisLocation.FILTER));
    }

    @Test
    public void mdxModeQueryIsLeftUntouched() {
        ThinQuery tq = new ThinQuery("test", null, "SELECT [Measures].[Unit Sales] ON 0 FROM [Sales]");
        ThinQueryFilterMerge.apply(
                tq, Collections.singletonList(filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]")), schema);
        assertEquals(ThinQuery.Type.MDX, tq.getType());
        assertNull(tq.getQueryModel());
    }

    /* ---- saiku#1104: strict variant for forced RLS filters (applyReportingUnapplied) ---- */

    @Test
    public void strict_resolvableFilterAppliesWithNothingUnapplied() {
        ThinQuery tq = querymodel();
        List<AiFilterSelection> unapplied = ThinQueryFilterMerge.applyReportingUnapplied(
                tq, Collections.singletonList(filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]")), schema);
        assertTrue("a resolvable forced filter must apply cleanly", unapplied.isEmpty());
        assertNotNull("and be spliced onto the FILTER axis", tq.getQueryModel().getAxis(AxisLocation.FILTER));
    }

    @Test
    public void strict_mdxModeReportsAllUnapplied() {
        // MDX-mode saved query can't take a spliced slicer — every forced filter is unapplied, so the
        // caller MUST fail closed (this is the RLS leak we refuse).
        ThinQuery tq = new ThinQuery("test", null, "SELECT [Measures].[Unit Sales] ON 0 FROM [Sales]");
        List<AiFilterSelection> forced =
                Collections.singletonList(filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]"));
        List<AiFilterSelection> unapplied = ThinQueryFilterMerge.applyReportingUnapplied(tq, forced, schema);
        assertEquals(1, unapplied.size());
    }

    @Test
    public void strict_unknownDimensionIsReportedUnapplied() {
        ThinQuery tq = querymodel();
        List<AiFilterSelection> unapplied = ThinQueryFilterMerge.applyReportingUnapplied(
                tq, Collections.singletonList(filter("Made Up", "Made Up", "Year", "[Made Up].[2026]")), schema);
        assertEquals("an unresolvable forced filter must be reported, not silently dropped", 1, unapplied.size());
    }

    @Test
    public void strict_emptyMembersIsReportedUnapplied() {
        ThinQuery tq = querymodel();
        List<AiFilterSelection> unapplied = ThinQueryFilterMerge.applyReportingUnapplied(
                tq, Collections.singletonList(filter("Time", "Time", "Year")), schema);
        assertEquals(1, unapplied.size());
    }

    @Test
    public void strict_nullSchemaFailsClosedForAll() {
        ThinQuery tq = querymodel();
        List<AiFilterSelection> forced =
                Collections.singletonList(filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]"));
        List<AiFilterSelection> unapplied = ThinQueryFilterMerge.applyReportingUnapplied(tq, forced, null);
        assertEquals("no schema means nothing can be verified-applied — fail closed", 1, unapplied.size());
    }

    @Test
    public void strict_mixedReportsOnlyTheUnappliedOne() {
        ThinQuery tq = querymodel();
        List<AiFilterSelection> forced = Arrays.asList(
                filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]"), // resolves
                filter("Made Up", "Made Up", "Year", "[Made Up].[x]")); // does not
        List<AiFilterSelection> unapplied = ThinQueryFilterMerge.applyReportingUnapplied(tq, forced, schema);
        assertEquals(1, unapplied.size());
        assertEquals("Made Up", unapplied.get(0).getDimension());
    }

    @Test
    public void newHierarchyLandsOnFilterAxis() {
        ThinQuery tq = querymodel();
        ThinQueryFilterMerge.apply(
                tq, Collections.singletonList(filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]")), schema);

        ThinAxis fa = tq.getQueryModel().getAxis(AxisLocation.FILTER);
        assertNotNull("expected FILTER axis to be created", fa);
        assertEquals(1, fa.getHierarchies().size());
        ThinHierarchy th = fa.getHierarchies().get(0);
        assertEquals("[Time].[Time]", th.getName());
        ThinLevel lvl = th.getLevels().get("Year");
        assertNotNull(lvl);
        assertEquals(ThinSelection.Type.INCLUSION, lvl.getSelection().getType());
        assertEquals(1, lvl.getSelection().getMembers().size());
        assertEquals(
                "[Time].[Time].[Year].&[1997]",
                lvl.getSelection().getMembers().get(0).getUniqueName());
    }

    @Test
    public void hierarchyOnRowsAxisIsNarrowedInPlace() {
        ThinQuery tq = querymodel();
        ThinHierarchy rowsHier = hierWithLevel(
                "[Product].[Product]",
                "Product",
                "Product",
                "Product Family",
                Arrays.asList(
                        "[Product].[Product].[Product Family].&[Drink]",
                        "[Product].[Product].[Product Family].&[Food]",
                        "[Product].[Product].[Product Family].&[Non-Consumable]"));
        putAxis(tq.getQueryModel(), AxisLocation.ROWS, rowsHier);

        ThinQueryFilterMerge.apply(
                tq,
                Collections.singletonList(filter(
                        "Product", "Product", "Product Family", "[Product].[Product].[Product Family].&[Drink]")),
                schema);

        // FILTER axis was NOT created — the rows axis carries the slice instead.
        assertNull(
                "hierarchy already on rows; FILTER axis must stay absent",
                tq.getQueryModel().getAxis(AxisLocation.FILTER));
        ThinAxis rowsAxis = tq.getQueryModel().getAxis(AxisLocation.ROWS);
        assertEquals(1, rowsAxis.getHierarchies().size());
        ThinLevel lvl = rowsAxis.getHierarchies().get(0).getLevels().get("Product Family");
        assertNotNull(lvl);
        assertEquals(ThinSelection.Type.INCLUSION, lvl.getSelection().getType());
        assertEquals(1, lvl.getSelection().getMembers().size());
        assertEquals(
                "[Product].[Product].[Product Family].&[Drink]",
                lvl.getSelection().getMembers().get(0).getUniqueName());
    }

    @Test
    public void existingFilterAxisEntryIsReplaced() {
        ThinQuery tq = querymodel();
        ThinHierarchy existing =
                hierWithLevel("[Time].[Time]", "Time", "Time", "Year", Arrays.asList("[Time].[Time].[Year].&[1996]"));
        putAxis(tq.getQueryModel(), AxisLocation.FILTER, existing);

        ThinQueryFilterMerge.apply(
                tq,
                Collections.singletonList(
                        filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]", "[Time].[Time].[Year].&[1998]")),
                schema);

        ThinAxis fa = tq.getQueryModel().getAxis(AxisLocation.FILTER);
        // The existing axis had the hierarchy — we rewrite it in place,
        // not as a duplicate. One hierarchy entry total.
        assertEquals(1, fa.getHierarchies().size());
        ThinLevel lvl = fa.getHierarchies().get(0).getLevels().get("Year");
        assertNotNull(lvl);
        assertEquals(2, lvl.getSelection().getMembers().size());
    }

    @Test
    public void exclusionSelectionOnAxisIsOverriddenWithInclusion() {
        ThinQuery tq = querymodel();
        ThinSelection excl = new ThinSelection(
                ThinSelection.Type.EXCLUSION,
                Collections.singletonList(new org.saiku.olap.query2.ThinMember(
                        "[Product].[Product].[Product Family].&[Drink]",
                        "[Product].[Product].[Product Family].&[Drink]",
                        "Drink")));
        ThinLevel pfLevel = new ThinLevel("Product Family", "Product Family", excl, new ArrayList<>());
        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put("Product Family", pfLevel);
        ThinHierarchy axisHier = new ThinHierarchy("[Product].[Product]", "Product", "Product", levels);
        putAxis(tq.getQueryModel(), AxisLocation.ROWS, axisHier);

        ThinQueryFilterMerge.apply(
                tq,
                Collections.singletonList(
                        filter("Product", "Product", "Product Family", "[Product].[Product].[Product Family].&[Food]")),
                schema);

        ThinSelection sel = tq.getQueryModel()
                .getAxis(AxisLocation.ROWS)
                .getHierarchies()
                .get(0)
                .getLevels()
                .get("Product Family")
                .getSelection();
        assertEquals(
                "dashboard filter must replace EXCLUSION with INCLUSION", ThinSelection.Type.INCLUSION, sel.getType());
        assertEquals(1, sel.getMembers().size());
        assertEquals(
                "[Product].[Product].[Product Family].&[Food]",
                sel.getMembers().get(0).getUniqueName());
    }

    @Test
    public void multipleFiltersAppliedInOneCall() {
        ThinQuery tq = querymodel();
        ThinQueryFilterMerge.apply(
                tq,
                Arrays.asList(
                        filter("Time", "Time", "Year", "[Time].[Time].[Year].&[1997]"),
                        filter(
                                "Product",
                                "Product",
                                "Product Family",
                                "[Product].[Product].[Product Family].&[Drink]")),
                schema);

        ThinAxis fa = tq.getQueryModel().getAxis(AxisLocation.FILTER);
        assertNotNull(fa);
        assertEquals(2, fa.getHierarchies().size());
        // Order-agnostic assert — both hierarchies must be present.
        boolean sawTime = false;
        boolean sawProduct = false;
        for (ThinHierarchy h : fa.getHierarchies()) {
            if ("[Time].[Time]".equals(h.getName())) sawTime = true;
            if ("[Product].[Product]".equals(h.getName())) sawProduct = true;
        }
        assertTrue(sawTime);
        assertTrue(sawProduct);
    }

    @Test
    public void nullAndEmptyInputsAreSafe() {
        ThinQuery tq = querymodel();
        ThinQueryFilterMerge.apply(null, Collections.emptyList(), schema);
        ThinQueryFilterMerge.apply(tq, null, schema);
        ThinQueryFilterMerge.apply(tq, Collections.emptyList(), schema);
        ThinQueryFilterMerge.apply(tq, Collections.emptyList(), null);
        assertFalse(
                "no axes should have been touched", tq.getQueryModel().getAxes().containsKey(AxisLocation.FILTER));
    }
}
