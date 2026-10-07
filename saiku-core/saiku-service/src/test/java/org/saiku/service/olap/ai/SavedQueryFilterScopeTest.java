/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.olap4j.impl.NamedListImpl;
import org.olap4j.metadata.NamedList;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.olap.query2.ThinSelection;

/**
 * saiku#1946 — unit tests for {@link SavedQueryFilterScope}, the per-field scope that gates a bare
 * saved-query embed guest's filter overrides against the saved query's OWN authored FILTER axis.
 *
 * <p>Threat under test (CWE-863, presentation scope): with no declared-target gate on the bare
 * saved-query surface, a guest could re-point an arbitrary non-forced axis at arbitrary members, or
 * add a deeper level beside an authored rows level, surfacing rows the author never published.
 */
public class SavedQueryFilterScopeTest {

    /* --------------------------- fixtures ---------------------------- */

    private static final String HIER = "[Geography].[Geography]";
    private static final String STATE_LVL = "State";
    private static final String CITY_LVL = "City";
    private static final String CA = "[Geography].[Geography].[State].&[CA]";
    private static final String WA = "[Geography].[Geography].[State].&[WA]";
    private static final String SF = "[Geography].[Geography].[City].&[San Francisco]";
    private static final String FRESNO = "[Store].[Store].[Store Name].&[Fresno]";

    private static ThinQuery querymodel() {
        ThinQuery tq = new ThinQuery();
        tq.setName("test");
        tq.setQueryModel(new ThinQueryModel());
        return tq;
    }

    private static ThinHierarchy hier(String hierUniqueName, String hierName, String levelName, String... members) {
        ThinSelection sel = new ThinSelection(
                ThinSelection.Type.INCLUSION,
                Arrays.stream(members).map(m -> new ThinMember(m, m, m)).toList());
        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(levelName, new ThinLevel(levelName, levelName, sel, new ArrayList<>()));
        return new ThinHierarchy(hierUniqueName, hierName, hierName, levels);
    }

    private static void putAxis(ThinQueryModel model, AxisLocation loc, ThinHierarchy... hierarchies) {
        NamedList<ThinHierarchy> nl = new NamedListImpl<>();
        Collections.addAll(nl, hierarchies);
        model.getAxes().put(loc, new ThinAxis(loc, nl, false, new ArrayList<>()));
    }

    /** A saved query whose slicer publishes exactly CA + WA at State. */
    private static ThinQuery savedQueryWithAuthoredSlicer() {
        ThinQuery tq = querymodel();
        putAxis(tq.getQueryModel(), AxisLocation.FILTER, hier(HIER, "Geography", STATE_LVL, CA, WA));
        return tq;
    }

    private static AiFilterSelection filter(String dim, String hier, String level, String... members) {
        return new AiFilterSelection(dim, hier, level, new ArrayList<>(Arrays.asList(members)));
    }

    /* ------------------------------ tests ----------------------------- */

    @Test
    public void narrowsWithinTheAuthoredSlicer() {
        List<AiFilterSelection> out = SavedQueryFilterScope.narrowToAuthoredScope(
                savedQueryWithAuthoredSlicer(),
                Collections.singletonList(filter("Geography", "Geography", STATE_LVL, CA)));
        assertEquals(1, out.size());
        assertEquals(Collections.singletonList(CA), out.get(0).getMembers());
    }

    @Test
    public void dropsMembersOutsideTheAuthoredSlice() {
        // The guest names a member the author never published on the slicer — it is stripped, and the
        // surviving selection is the authored set narrowed to the in-scope member.
        List<AiFilterSelection> out = SavedQueryFilterScope.narrowToAuthoredScope(
                savedQueryWithAuthoredSlicer(),
                Collections.singletonList(filter("Geography", "Geography", STATE_LVL, CA, SF)));
        assertEquals(1, out.size());
        assertEquals(Collections.singletonList(CA), out.get(0).getMembers());
    }

    @Test
    public void dropsOverridesOnANonSlicerAxis() {
        // A hierarchy the author put on ROWS, not the slicer, is not overridable — this is the
        // presentation-scope over-exposure: re-pointing the authored rows roll-up.
        ThinQuery tq = querymodel();
        putAxis(
                tq.getQueryModel(),
                AxisLocation.ROWS,
                hier("[Store].[Store]", "Store", "Store Name", "[Store].[Store].[Store Name].&[San Jose]"));
        putAxis(tq.getQueryModel(), AxisLocation.FILTER, hier(HIER, "Geography", STATE_LVL, CA, WA));
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        tq, Collections.singletonList(filter("Store", "Store", "Store Name", FRESNO)))
                .isEmpty());
    }

    @Test
    public void dropsAFinerLevelOfAnAuthoredHierarchy() {
        // Adding a City level beside the authored State slicer would surface individual city rows
        // under a country/state roll-up the author published — dropped fail-closed.
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        savedQueryWithAuthoredSlicer(),
                        Collections.singletonList(filter("Geography", "Geography", CITY_LVL, SF)))
                .isEmpty());
    }

    @Test
    public void keepsClientOrderAndStripsDuplicates() {
        List<AiFilterSelection> out = SavedQueryFilterScope.narrowToAuthoredScope(
                savedQueryWithAuthoredSlicer(),
                Collections.singletonList(filter("Geography", "Geography", STATE_LVL, WA, CA, WA)));
        assertEquals(1, out.size());
        assertEquals(Arrays.asList(WA, CA), out.get(0).getMembers());
    }

    @Test
    public void dropIsPerOverrideNotAllOrNothing() {
        List<AiFilterSelection> out = SavedQueryFilterScope.narrowToAuthoredScope(
                savedQueryWithAuthoredSlicer(),
                Arrays.asList(
                        filter("Geography", "Geography", STATE_LVL, CA),
                        filter("Geography", "Geography", CITY_LVL, SF),
                        filter("Store", "Store", "Store Name", "[Store].[Store].[Store Name].&[Fresno]")));
        assertEquals(1, out.size());
        assertEquals("Geography", out.get(0).getHierarchy());
    }

    @Test
    public void anEntirelyOutOfScopeSelectionIsDroppedSoTheAuthoredSliceStands() {
        // An empty intersection must NOT be forwarded: an empty member list reads as "unrestricted"
        // in the merge, which is precisely the over-exposure. Dropping it leaves the authored slice.
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        savedQueryWithAuthoredSlicer(),
                        Collections.singletonList(filter("Geography", "Geography", STATE_LVL, SF)))
                .isEmpty());
    }

    @Test
    public void emptyOverrideMembersAreDropped() {
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        savedQueryWithAuthoredSlicer(),
                        Collections.singletonList(filter("Geography", "Geography", STATE_LVL)))
                .isEmpty());
    }

    @Test
    public void aNonInclusionOperatorIsDropped() {
        // not_in / between / relative can't be proven non-widening by an intersection, so we don't
        // trust them — the authored selection stands.
        AiFilterSelection notIn = filter("Geography", "Geography", STATE_LVL, CA, WA);
        notIn.setOp("not_in");
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        savedQueryWithAuthoredSlicer(), Collections.singletonList(notIn))
                .isEmpty());
    }

    @Test
    public void aPlainInOperatorIsHonoured() {
        AiFilterSelection in = filter("Geography", "Geography", STATE_LVL, CA);
        in.setOp("IN");
        List<AiFilterSelection> out = SavedQueryFilterScope.narrowToAuthoredScope(
                savedQueryWithAuthoredSlicer(), Collections.singletonList(in));
        assertEquals(1, out.size());
        assertEquals(Collections.singletonList(CA), out.get(0).getMembers());
    }

    @Test
    public void aQueryWithNoSlicerAxisAuthorisesNothing() {
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        querymodel(), Collections.singletonList(filter("Geography", "Geography", STATE_LVL, CA)))
                .isEmpty());
    }

    @Test
    public void mdxModeQueryAuthorisesNothing() {
        // No query model ⇒ no authored scope to narrow within. Fail-closed, never "allow".
        ThinQuery mdx = new ThinQuery("test", null, "SELECT [Measures].[Unit Sales] ON 0 FROM [Sales]");
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        mdx, Collections.singletonList(filter("Geography", "Geography", STATE_LVL, CA)))
                .isEmpty());
    }

    @Test
    public void anExclusionSlicerSelectionAuthorisesNothing() {
        // "Everything but X" gives us no ceiling to intersect within, so nothing is overridable.
        ThinQuery tq = querymodel();
        ThinSelection excl = new ThinSelection(ThinSelection.Type.EXCLUSION, List.of(new ThinMember(SF, SF, SF)));
        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(STATE_LVL, new ThinLevel(STATE_LVL, STATE_LVL, excl, new ArrayList<>()));
        putAxis(tq.getQueryModel(), AxisLocation.FILTER, new ThinHierarchy(HIER, "Geography", "Geography", levels));
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        tq, Collections.singletonList(filter("Geography", "Geography", STATE_LVL, CA)))
                .isEmpty());
    }

    @Test
    public void aParameterisedSlicerAuthorisesNothing() {
        ThinQuery tq = querymodel();
        ThinSelection param = new ThinSelection(ThinSelection.Type.INCLUSION, new ArrayList<>());
        param.setParameterName("stateParam");
        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(STATE_LVL, new ThinLevel(STATE_LVL, STATE_LVL, param, new ArrayList<>()));
        putAxis(tq.getQueryModel(), AxisLocation.FILTER, new ThinHierarchy(HIER, "Geography", "Geography", levels));
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        tq, Collections.singletonList(filter("Geography", "Geography", STATE_LVL, CA)))
                .isEmpty());
    }

    @Test
    public void matchesTheHierarchyByUniqueNameOrCaptionCaseInsensitively() {
        List<AiFilterSelection> byUnique = SavedQueryFilterScope.narrowToAuthoredScope(
                savedQueryWithAuthoredSlicer(),
                Collections.singletonList(filter("Geography", "  [GEOGRAPHY].[GEOGRAPHY]  ", STATE_LVL, CA)));
        assertEquals(1, byUnique.size());
        List<AiFilterSelection> byCaption = SavedQueryFilterScope.narrowToAuthoredScope(
                savedQueryWithAuthoredSlicer(),
                Collections.singletonList(filter("Geography", "geography", "state", CA)));
        assertEquals(1, byCaption.size());
    }

    @Test
    public void nullsAndEmptiesAreSafe() {
        assertTrue(
                SavedQueryFilterScope.narrowToAuthoredScope(null, Collections.singletonList(filter("G", "G", "L", "m")))
                        .isEmpty());
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(savedQueryWithAuthoredSlicer(), null)
                .isEmpty());
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(savedQueryWithAuthoredSlicer(), Collections.emptyList())
                .isEmpty());
        assertTrue(SavedQueryFilterScope.narrowToAuthoredScope(
                        savedQueryWithAuthoredSlicer(), Collections.singletonList((AiFilterSelection) null))
                .isEmpty());
    }
}
