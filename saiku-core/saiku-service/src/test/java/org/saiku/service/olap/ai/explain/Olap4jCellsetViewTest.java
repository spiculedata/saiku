/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.olap4j.metadata.Member;

/**
 * {@link Olap4jCellsetView} — the coordinate mapping. This is the one class that could be
 * confidently wrong (olap4j's column axis starts to the right of the row-header band, and the
 * header bands are not part of either axis), so it is tested against a stubbed cellset that
 * returns a different value for every cell.
 */
public class Olap4jCellsetViewTest {

    private static final Member SALES = Olap4jStubs.measureMember("[Measures].[Store Sales]", "Store Sales");
    private static final Member Q1 = Olap4jStubs.dimensionMember("[Time].[1997]", "[Time].[1997].[Q1]", "Q1");
    private static final Member USA = Olap4jStubs.dimensionMember("[Customers].[USA]", "USA");
    private static final Member CANADA = Olap4jStubs.dimensionMember("[Customers].[Canada]", "Canada");

    private static Olap4jCellsetView twoByTwo() {
        return new Olap4jCellsetView(Olap4jStubs.cellSet(
                List.of(List.of(Q1, SALES)),
                List.of(List.of(USA), List.of(CANADA)),
                new double[][] {{100d, 300d}, {400d, 500d}}));
    }

    @Test
    public void readsTheShapeFromTheAxisPositions() {
        Olap4jCellsetView view = twoByTwo();
        assertEquals(2, view.rowCount());
        assertEquals(1, view.columnCount());
    }

    /** The crux: data row 1 / data column 0 is the value 400, not 100 and not 500. */
    @Test
    public void mapsDataCoordinatesOntoTheRightCell() {
        Olap4jCellsetView view = twoByTwo();
        assertEquals(100d, view.value(0, 0), 1e-9);
        assertEquals(300d, view.value(1, 0), 1e-9);
        assertEquals("100.00", view.formatted(0, 0));
    }

    @Test
    public void readsTheCoordinateMembers() {
        Olap4jCellsetView view = twoByTwo();
        assertEquals(List.of(new CellMember("[Customers].[Canada]", "Canada", false, null)), view.rowHeader(1));
        assertEquals(
                List.of(
                        new CellMember("[Time].[1997].[Q1]", "Q1", false, "[Time].[1997]"),
                        new CellMember("[Measures].[Store Sales]", "Store Sales", true, null)),
                view.columnHeader(0));
        assertEquals("Store Sales", view.measure().caption());
    }

    /** A cellset read back after its connection closed: Mondrian's getCaption() throws an NPE. */
    @Test
    public void aMemberDetachedFromItsConnectionIsExplainedUnderItsName() {
        Member detachedRow = Olap4jStubs.detachedMember("[Customers].[USA]", "USA");
        Olap4jCellsetView view = new Olap4jCellsetView(
                Olap4jStubs.cellSet(List.of(List.of(SALES)), List.of(List.of(detachedRow)), new double[][] {{100d}}));

        assertEquals(List.of(new CellMember("[Customers].[USA]", "USA", false, null)), view.rowHeader(0));
    }

    @Test
    public void anEmptyCellReadsAsNaNAndNoFormatting() {
        Olap4jCellsetView view = new Olap4jCellsetView(Olap4jStubs.cellSet(
                List.of(List.of(Q1, SALES), List.of(Q1, SALES)),
                List.of(List.of(USA)),
                // one data row, two data columns
                new double[][] {{Double.NaN}, {7d}}));
        assertTrue(Double.isNaN(view.value(0, 0)));
        assertNull(view.formatted(0, 0));
        assertEquals(7d, view.value(0, 1), 1e-9);
    }

    /** A WHERE-only MDX has one axis; olap4j addresses its single row with the column position. */
    @Test
    public void handlesAOneAxisCellset() {
        Olap4jCellsetView view =
                new Olap4jCellsetView(Olap4jStubs.cellSet(List.of(List.of(SALES)), List.of(), new double[][] {{42d}}));

        assertEquals(1, view.columnCount());
        assertEquals(0, view.rowCount());
        assertEquals(42d, view.value(0, 0), 1e-9);
        assertEquals(List.of(), view.rowHeader(0));
        assertEquals("Store Sales", view.measure().caption());
    }

    @Test
    public void rejectsANullCellset() {
        IllegalArgumentException expected =
                assertThrows(IllegalArgumentException.class, () -> new Olap4jCellsetView(null));
        assertEquals("cellSet required", expected.getMessage());
    }
}
