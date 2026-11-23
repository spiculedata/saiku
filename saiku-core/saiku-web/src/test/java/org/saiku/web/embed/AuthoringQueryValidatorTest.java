/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.olap4j.impl.NamedListImpl;
import org.saiku.olap.query2.Parameter;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinCalculatedMember;
import org.saiku.olap.query2.ThinDetails;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinNamedSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinSelection;
import org.saiku.olap.query2.filter.ThinFilter;

/**
 * saiku#1435 — the Creator Mode trust boundary. The catalogue stands in for the
 * pinned cube: dimension {@code [Store]} with level {@code [Store].[Store]},
 * members {@code [Store].[USA]} and {@code [Store].[Canada]}, and measure
 * {@code [Measures].[Unit Sales]}.
 *
 * <p>Mostly negative by design: the interesting cases are the ones a hostile
 * host page would send.
 */
public class AuthoringQueryValidatorTest {

    private AuthoringCubeCatalogue catalogue;

    @Before
    public void setUp() {
        AuthoringCubeCatalogue.Level storeLevel = new AuthoringCubeCatalogue.Level(
                "[Store].[Store]", "Store", List.of("[Store].[USA]", "[Store].[Canada]"), false);
        AuthoringCubeCatalogue.Dimension store =
                new AuthoringCubeCatalogue.Dimension("[Store]", "Store", List.of(storeLevel));
        catalogue = new AuthoringCubeCatalogue(
                "[foodmart].[foodmart].[foodmart].[sales]",
                "Sales",
                List.of(store),
                List.of(new AuthoringCubeCatalogue.MeasureInfo("[Measures].[Unit Sales]", "Unit Sales")));
    }

    /* --------------------------- happy path --------------------------- */

    @Test
    public void accepts_a_query_built_from_the_catalogue() {
        assertTrue(verdict(okQuery()).isValid());
    }

    @Test
    public void accepts_a_query_with_no_member_selection() {
        ThinQuery q = okQuery();
        withRowSelection(q, null);
        assertTrue(verdict(q).isValid());
    }

    /* ------------------------- cube containment ------------------------- */

    @Test
    public void refuses_a_hierarchy_outside_the_catalogue() {
        ThinQuery q = okQuery();
        q.getQueryModel()
                .getAxis(ThinQueryModel.AxisLocation.ROWS)
                .getHierarchies()
                .get(0)
                .setName("[NotACube].[X]");
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_level_outside_the_catalogue() {
        ThinQuery q = okQuery();
        Map<String, ThinLevel> levels = rowHierarchy(q).getLevels();
        levels.put(
                "[Store].[Nowhere]",
                new ThinLevel("[Store].[Nowhere]", "Nowhere", selection("[Store].[USA]"), new ArrayList<>()));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_level_belonging_to_another_hierarchy() {
        // The cube HAS a [Buyer] hierarchy; the query named [Store] but tried to
        // hang [Buyer] off it. Containment is checked per hierarchy, not globally.
        AuthoringCubeCatalogue.Level buyerLevel =
                new AuthoringCubeCatalogue.Level("[Buyer]", "Buyer", List.of("[Buyer].[USA]"), false);
        AuthoringCubeCatalogue.Dimension buyer =
                new AuthoringCubeCatalogue.Dimension("[Buyer]", "Buyer", List.of(buyerLevel));
        catalogue = new AuthoringCubeCatalogue(
                catalogue.cubeUniqueName(),
                catalogue.cubeCaption(),
                List.of(catalogue.dimensions().get(0), buyer),
                catalogue.measures());
        ThinQuery q = okQuery();
        rowHierarchy(q)
                .getLevels()
                .put("[Buyer]", new ThinLevel("[Buyer]", "Buyer", selection("[Buyer].[USA]"), new ArrayList<>()));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_member_outside_the_catalogue() {
        ThinQuery q = okQuery();
        withRowSelection(q, selection("[Store].[Atlantis]"));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_an_injected_member_string() {
        // A crafted "member" that is really an MDX fragment. It is not in the
        // level's member list, so it never reaches the MDX generator.
        ThinQuery q = okQuery();
        withRowSelection(
                q,
                selection(
                        "}) ON 0 FROM ([Store].[USA] WHERE 1=1) SELECT 1 FROM [Store] WHERE ([Measures].[Unit Sales]"));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_measure_outside_the_catalogue() {
        ThinQuery q = okQuery();
        q.getQueryModel().setDetails(details(measure("[Measures].[Store Cost]")));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_query_with_no_measure() {
        ThinQuery q = okQuery();
        q.getQueryModel()
                .setDetails(new ThinDetails(
                        ThinQueryModel.AxisLocation.COLUMNS, ThinDetails.Location.BOTTOM, new ArrayList<>()));
        assertFalse(verdict(q).isValid());
    }

    /* ------------------------- injection channels ------------------------- */

    @Test
    public void refuses_raw_mdx() {
        ThinQuery q = okQuery();
        q.setMdx("SELECT FROM [Sales] WHERE 1=1");
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_mdx_on_the_axis() {
        ThinQuery q = okQuery();
        q.getQueryModel()
                .getAxis(ThinQueryModel.AxisLocation.COLUMNS)
                .setMdx("WITH MEMBER X AS 1 SELECT X ON 0 FROM [Sales]");
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_mdx_on_a_level() {
        ThinQuery q = okQuery();
        rowLevel(q).setMdx("SELECT FROM [Sales]");
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_level_filter() {
        ThinQuery q = okQuery();
        rowLevel(q).addFilter(new ThinFilter());
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_sort_evaluation_literal() {
        ThinQuery q = okQuery();
        q.getQueryModel()
                .getAxis(ThinQueryModel.AxisLocation.ROWS)
                .sort(org.saiku.olap.query2.common.ThinSortableQuerySet.SortOrder.ASC, "1=1) SELECT 1 FROM (SELECT");
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_an_mdx_type_query() {
        ThinQuery q = okQuery();
        q.setType(ThinQuery.Type.MDX);
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_query_parameters() {
        ThinQuery q = okQuery();
        q.setParameter("evil", "') OR 1=1 --");
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_typed_parameters() {
        ThinQuery q = okQuery();
        q.getTypedParameters().add(new Parameter("evil", Parameter.ParameterType.SIMPLE, "1"));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_calculated_members() {
        ThinQuery q = okQuery();
        q.getQueryModel().getCalculatedMembers().add(new ThinCalculatedMember());
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_named_sets() {
        ThinQuery q = okQuery();
        q.getQueryModel().getNamedSets().add(new ThinNamedSet("evil", "1=1) SELECT 1 FROM (SELECT"));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_an_ossie_query() {
        ThinQuery q = okQuery();
        q.setQueryType("OSSIE");
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_missing_query_model() {
        ThinQuery q = okQuery();
        q.setQueryModel(null);
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_a_member_on_a_truncated_level() {
        // A level the catalogue could not enumerate is unselectable: we can't
        // vouch for a name we never saw, so we refuse rather than guess.
        AuthoringCubeCatalogue.Level hidden =
                new AuthoringCubeCatalogue.Level("[Store].[Store City]", "Store City", List.of(), true);
        AuthoringCubeCatalogue.Dimension store = new AuthoringCubeCatalogue.Dimension(
                "[Store]",
                "Store",
                List.of(hidden, catalogue.dimensions().get(0).levels.get(0)));
        catalogue = new AuthoringCubeCatalogue(
                catalogue.cubeUniqueName(), catalogue.cubeCaption(), List.of(store), catalogue.measures());

        ThinQuery q = okQuery();
        rowHierarchy(q)
                .getLevels()
                .put(
                        "[Store].[Store City]",
                        new ThinLevel(
                                "[Store].[Store City]", "Store City", selection("[Store].[Portland]"), List.of()));
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_an_oversized_member_selection() {
        ThinQuery q = okQuery();
        ThinSelection sel = rowLevel(q).getSelection();
        for (int i = 0; i <= AuthoringQueryValidator.MAX_MEMBERS_PER_SELECTION; i++) {
            ThinMember m = new ThinMember();
            m.setUniqueName("[Store].[USA]");
            sel.getMembers().add(m);
        }
        assertFalse(verdict(q).isValid());
    }

    @Test
    public void refuses_everything_when_the_catalogue_is_missing() {
        assertFalse(AuthoringQueryValidator.validate(null, okQuery()).isValid());
        assertFalse(AuthoringQueryValidator.validate(catalogue, null).isValid());
    }

    /* --------------------------- helpers ---------------------------- */

    private AuthoringQueryValidator.Result verdict(ThinQuery q) {
        return AuthoringQueryValidator.validate(catalogue, q);
    }

    /** A minimal but well-formed QUERYMODEL query over the catalogue. */
    private ThinQuery okQuery() {
        ThinQuery tq = new ThinQuery("creator-query", null);
        tq.setType(ThinQuery.Type.QUERYMODEL);
        tq.setQueryType("OLAP");

        ThinLevel storeLevel = new ThinLevel("[Store].[Store]", "Store", selection("[Store].[USA]"), new ArrayList<>());
        Map<String, ThinLevel> storeLevels = new HashMap<>();
        storeLevels.put("[Store].[Store]", storeLevel);
        ThinHierarchy storeHierarchy = new ThinHierarchy("[Store]", "Store", "[Store]", storeLevels);

        Map<String, ThinLevel> measureLevels = new HashMap<>();
        measureLevels.put(
                "[Measures].[Unit Sales]",
                new ThinLevel("[Measures].[Unit Sales]", "Unit Sales", null, new ArrayList<>()));
        ThinHierarchy measureHierarchy = new ThinHierarchy("[Measures]", "Measures", "[Measures]", measureLevels);

        Map<ThinQueryModel.AxisLocation, ThinAxis> axes = new HashMap<>();
        axes.put(
                ThinQueryModel.AxisLocation.ROWS,
                new ThinAxis(ThinQueryModel.AxisLocation.ROWS, namedList(storeHierarchy), true, null));
        axes.put(
                ThinQueryModel.AxisLocation.COLUMNS,
                new ThinAxis(ThinQueryModel.AxisLocation.COLUMNS, namedList(measureHierarchy), true, null));

        ThinQueryModel model = new ThinQueryModel();
        model.setAxes(axes);
        model.setDetails(details(measure("[Measures].[Unit Sales]")));
        tq.setQueryModel(model);
        return tq;
    }

    private static NamedListImpl<ThinHierarchy> namedList(ThinHierarchy h) {
        NamedListImpl<ThinHierarchy> list = new NamedListImpl<>();
        list.add(h);
        return list;
    }

    private static ThinMeasure measure(String uniqueName) {
        return new ThinMeasure(uniqueName, uniqueName, uniqueName, ThinMeasure.Type.EXACT);
    }

    private static ThinDetails details(ThinMeasure m) {
        return new ThinDetails(
                ThinQueryModel.AxisLocation.COLUMNS, ThinDetails.Location.BOTTOM, new ArrayList<>(List.of(m)));
    }

    private ThinHierarchy rowHierarchy(ThinQuery tq) {
        return tq.getQueryModel()
                .getAxis(ThinQueryModel.AxisLocation.ROWS)
                .getHierarchies()
                .get(0);
    }

    /**
     * ThinLevel is constructor-only, so a test that wants a different member selection swaps in a
     * rebuilt level under the same key.
     */
    private void withRowSelection(ThinQuery tq, ThinSelection selection) {
        ThinLevel old = rowLevel(tq);
        rowHierarchy(tq)
                .getLevels()
                .put(old.getName(), new ThinLevel(old.getName(), old.getCaption(), selection, new ArrayList<>()));
    }

    private ThinLevel rowLevel(ThinQuery tq) {
        return rowHierarchy(tq).getLevels().get("[Store].[Store]");
    }

    private static ThinSelection selection(String... members) {
        ThinSelection sel = new ThinSelection();
        for (String m : members) {
            ThinMember tm = new ThinMember();
            tm.setUniqueName(m);
            sel.getMembers().add(tm);
        }
        return sel;
    }
}
