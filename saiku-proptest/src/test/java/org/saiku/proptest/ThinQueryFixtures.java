/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.olap4j.impl.NamedListImpl;
import org.olap4j.metadata.NamedList;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinSelection;

/** Builders for the nested {@link ThinQuery} model, shared by the query-side property tests. */
final class ThinQueryFixtures {

    private static final String HIERARCHY = "[Store]";
    private static final String LEVEL = "Store";

    private ThinQueryFixtures() {}

    /** A QUERYMODEL query with the given member names selected, in the given order, on ROWS. */
    static ThinQuery rowsQuery(SaikuCube cube, List<String> memberNames) {
        List<ThinMember> members = new ArrayList<>(memberNames.size());
        for (String n : memberNames) {
            members.add(new ThinMember(n, HIERARCHY + ".[" + n + "]", n));
        }
        ThinSelection selection = new ThinSelection(ThinSelection.Type.INCLUSION, members);

        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(LEVEL, new ThinLevel(LEVEL, LEVEL, selection, null));

        NamedList<ThinHierarchy> hierarchies = new NamedListImpl<>();
        hierarchies.add(new ThinHierarchy(HIERARCHY, LEVEL, LEVEL, levels));

        Map<ThinQueryModel.AxisLocation, ThinAxis> axes = new LinkedHashMap<>();
        axes.put(
                ThinQueryModel.AxisLocation.ROWS,
                new ThinAxis(ThinQueryModel.AxisLocation.ROWS, hierarchies, false, null));

        ThinQueryModel model = new ThinQueryModel();
        model.setAxes(axes);
        return new ThinQuery("fixture", cube, model);
    }

    /** The selected member names, in the order the query currently holds them. */
    static List<String> memberOrder(ThinQuery q) {
        List<String> out = new ArrayList<>();
        ThinAxis axis = q.getQueryModel().getAxes().get(ThinQueryModel.AxisLocation.ROWS);
        for (ThinMember m : axis.getHierarchies()
                .get(0)
                .getLevels()
                .get(LEVEL)
                .getSelection()
                .getMembers()) {
            out.add(m.getName());
        }
        return out;
    }
}
