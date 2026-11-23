/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** {@link CellMdx} — cell-query shape, FROM extraction, and the member-injection guard. */
public class CellMdxTest {

    private static final CellMember SALES = CellMember.measure("[Measures].[Store Sales]", "Store Sales");
    private static final CellMember USA = CellMember.of("[Customers].[USA]", "USA");
    private static final CellMember Q1 = CellMember.of("[Time].[1997].[Q1]", "Q1");

    @Test
    public void buildsCellQueryWithMeasuresOnColumnsAndTheRestAsSlicers() {
        List<CellMember> coordinate = new ArrayList<>(List.of(USA, SALES, Q1));
        String mdx = CellMdx.cellQuery("[Sales]", coordinate);
        assertEquals(
                "SELECT {[Measures].[Store Sales]} ON 0 FROM [Sales] WHERE ([Customers].[USA], [Time].[1997].[Q1])",
                mdx);
    }

    @Test
    public void keepsSeveralMeasuresInOneCellQuery() {
        CellMember units = CellMember.measure("[Measures].[Unit Sales]", "Unit Sales");
        String mdx = CellMdx.cellQuery("[Sales]", List.of(USA, SALES, units, Q1));
        assertEquals(
                "SELECT {[Measures].[Store Sales], [Measures].[Unit Sales]} ON 0 FROM [Sales] WHERE"
                        + " ([Customers].[USA], [Time].[1997].[Q1])",
                mdx);
    }

    @Test
    public void omitsWhereWhenEveryMemberIsAMeasure() {
        assertEquals(
                "SELECT {[Measures].[Store Sales]} ON 0 FROM [Sales]", CellMdx.cellQuery("[Sales]", List.of(SALES)));
    }

    @Test
    public void projectsTheCoordinateWhenThereIsNoMeasureDimension() {
        String mdx = CellMdx.cellQuery("[Sales]", List.of(USA, Q1));
        assertEquals("SELECT {[Customers].[USA], [Time].[1997].[Q1]} ON 0 FROM [Sales]", mdx);
    }

    @Test
    public void returnsNullWithoutAUsableCoordinate() {
        assertNull(CellMdx.cellQuery("[Sales]", List.of()));
        assertNull(CellMdx.cellQuery("[Sales]", List.of(CellMember.of("", "blank"))));
    }

    @Test
    public void returnsNullForAnUnusableCubeReference() {
        assertNull(CellMdx.cellQuery("Sales", List.of(SALES)));
        assertNull(CellMdx.cellQuery(null, List.of(SALES)));
    }

    /**
     * A member whose uniquename carries a bracket is dropped rather than escaped: interpolating it
     * would let a schema-supplied name change the query the server executes.
     */
    @Test
    public void dropsMembersThatAreNotBracketedPaths() {
        assertNull(CellMdx.sanitiseMember("[Customers].[USA]; DROP"));
        assertNull(CellMdx.sanitiseMember("Customers.USA"));
        assertNull(CellMdx.sanitiseMember("[Customers.USA"));
        assertNull(CellMdx.sanitiseMember(null));
        assertEquals("[Customers].[USA]", CellMdx.sanitiseMember("  [Customers].[USA]  "));
    }

    @Test
    public void dropsTheOffendingMemberAndKeepsTheQuery() {
        String mdx = CellMdx.cellQuery(
                "[Sales]", List.of(SALES, CellMember.of("[Customers].[USA]] OR 1=1 --", "evil"), USA));
        assertEquals("SELECT {[Measures].[Store Sales]} ON 0 FROM [Sales] WHERE ([Customers].[USA])", mdx);
    }

    @Test
    public void readsTheCubeOutOfAnExistingQuery() {
        assertEquals("[Sales]", CellMdx.fromOf("SELECT {[Measures].[Store Sales]} ON 0 FROM [Sales]"));
        assertEquals(
                "[FoodMart].[FoodMart].[Sales]",
                CellMdx.fromOf("SELECT NON EMPTY {[Measures].[Store Sales]} ON 0 FROM [FoodMart].[FoodMart].[Sales]"));
        assertNull(CellMdx.fromOf("SELECT {[Measures].[Store Sales]} ON 0"));
        assertNull(CellMdx.fromOf("SELECT FROM Sales WHERE 1 = 1"));
        assertNull(CellMdx.fromOf(null));
    }

    @Test
    public void acceptsAQualifiedCubeReference() {
        String mdx = CellMdx.cellQuery("[FoodMart].[FoodMart].[Sales]", List.of(SALES));
        assertTrue(mdx.endsWith("FROM [FoodMart].[FoodMart].[Sales]"));
    }
}
