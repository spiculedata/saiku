/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
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
 * saiku#1362 — locks the {@code removeDupSelections} null-selection guard. The
 * AI Ask "edit in canvas" path re-executes an AI-built queryModel client-side;
 * those models carry levels with a NULL selection (the converter only sets one
 * when the request names members), and the dedupe pass used to NPE on
 * {@code getSelection().getMembers()}. The guard skips them — these tests pin
 * that (and that the actual dedupe still works on real selections).
 */
public class ThinQueryServiceDedupeTest {

    private static ThinMember m(String unique) {
        return new ThinMember(unique, unique, unique);
    }

    /** A hierarchy carrying one level with the given (possibly null) selection. */
    private static ThinHierarchy hier(String hierUnique, String dim, String levelName, ThinSelection sel) {
        ThinLevel lvl = new ThinLevel(levelName, levelName, sel, new ArrayList<>());
        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(levelName, lvl);
        return new ThinHierarchy(hierUnique, hierUnique, dim, levels);
    }

    private static ThinQuery modelWith(AxisLocation loc, ThinHierarchy... hiers) {
        ThinQuery tq = new ThinQuery();
        tq.setName("t");
        tq.setQueryModel(new ThinQueryModel());
        NamedList<ThinHierarchy> nl = new NamedListImpl<>();
        Collections.addAll(nl, hiers);
        tq.getQueryModel().getAxes().put(loc, new ThinAxis(loc, nl, false, new ArrayList<>()));
        return tq;
    }

    private static ThinSelection inclusion(ThinMember... members) {
        return new ThinSelection(ThinSelection.Type.INCLUSION, new ArrayList<>(List.of(members)));
    }

    private static ThinSelection selectionOf(ThinQuery tq, int hierIndex, String level) {
        return tq.getQueryModel()
                .getAxis(AxisLocation.ROWS)
                .getHierarchies()
                .get(hierIndex)
                .getLevels()
                .get(level)
                .getSelection();
    }

    @Test
    public void skipsLevelsWithNullSelection_noNpe() {
        // The regression: a level with a null selection must NOT NPE the dedupe.
        // RED before the `if (selection == null) continue` guard.
        ThinQuery tq = modelWith(AxisLocation.ROWS, hier("[Date].[Date]", "Date", "All", null));

        ThinQuery out = ThinQueryService.removeDupSelections(tq); // must not throw

        assertNotNull(out);
        assertNull("a null-selection level is left untouched", selectionOf(tq, 0, "All"));
    }

    @Test
    public void dedupesDuplicateMembersOnARealSelection() {
        // Sanity: the actual dedupe still works on a level that DOES carry a selection.
        ThinSelection sel = inclusion(m("[P].[Drink]"), m("[P].[Drink]"), m("[P].[Food]"));
        ThinQuery tq = modelWith(AxisLocation.ROWS, hier("[P].[P]", "P", "Family", sel));

        ThinQueryService.removeDupSelections(tq);

        List<String> names = selectionOf(tq, 0, "Family").getMembers().stream()
                .map(ThinMember::getUniqueName)
                .toList();
        assertEquals(List.of("[P].[Drink]", "[P].[Food]"), names);
    }

    @Test
    public void mixedNullAndRealSelections_dedupesRealLeavesNullAlone_noNpe() {
        // The real edit-in-canvas shape: one AI axis-only level (null selection)
        // alongside one with duplicate members. No NPE; the real one is deduped.
        ThinHierarchy nullLvl = hier("[Date].[Date]", "Date", "All", null);
        ThinHierarchy dupLvl = hier("[Time].[Time]", "Time", "Year", inclusion(m("[T].[1997]"), m("[T].[1997]")));
        ThinQuery tq = modelWith(AxisLocation.ROWS, nullLvl, dupLvl);

        ThinQueryService.removeDupSelections(tq); // no NPE despite the null-selection level

        assertNull(selectionOf(tq, 0, "All"));
        assertEquals(1, selectionOf(tq, 1, "Year").getMembers().size());
    }
}
