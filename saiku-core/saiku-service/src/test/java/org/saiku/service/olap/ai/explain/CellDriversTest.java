/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** {@link CellDrivers} — the Phase 2 findings, and the cases where there must be no finding. */
public class CellDriversTest {

    private static final CellMember SALES = CellMember.measure("[Measures].[Store Sales]", "Store Sales");
    private static final CellMember USA = CellMember.of("[Customers].[USA]", "USA");

    /** A quarter under its year — the shape olap4j hands back for a period column. */
    private static CellMember q(String year, String quarter) {
        return CellMember.under(
                "[Time].[" + year + "]", "[Time].[" + year + "].[" + quarter + "]", quarter + " " + year);
    }

    /** A year total: a top-level member, so no quarter shares its (absent) parent. */
    private static CellMember year(String year) {
        return CellMember.of("[Time].[" + year + "]", year);
    }

    private static Optional<ExplainDriver> of(List<ExplainDriver> drivers, ExplainDriver.Kind kind) {
        return drivers.stream().filter(d -> d.kind() == kind).findFirst();
    }

    @Test
    public void emptyCellHasNoDrivers() {
        FakeCellsetView view = new FakeCellsetView(new double[] {Double.NaN}).column(0, SALES);
        assertTrue(CellDrivers.analyze(view, 0, 0).isEmpty());
    }

    @Test
    public void shareRankAndRowShareComeFromTheCellset() {
        FakeCellsetView view = new FakeCellsetView(
                        new double[] {100d, 10d}, new double[] {300d, 20d}, new double[] {200d, 30d})
                .column(0, SALES)
                .column(1, CellMember.measure("[Measures].[Unit Sales]", "Unit Sales"))
                .row(0, USA)
                .row(1, CellMember.of("[Customers].[Canada]", "Canada"))
                .row(2, CellMember.of("[Customers].[Mexico]", "Mexico"));

        List<ExplainDriver> drivers = CellDrivers.analyze(view, 1, 0);

        // column 0 total = 100 + 300 + 200 = 600, so 300 is half of it
        ExplainDriver share = of(drivers, ExplainDriver.Kind.SHARE_OF_COLUMN).orElseThrow();
        assertEquals(0.5d, share.share(), 1e-9);
        assertEquals("Canada", share.caption());
        // 300 is the highest of 100/300/200
        assertEquals(
                "1 of 3 rows with a value in this column",
                of(drivers, ExplainDriver.Kind.RANK_IN_COLUMN).orElseThrow().detail());
        // row 1 total = 300 + 20 = 320
        assertEquals(
                300d / 320d,
                of(drivers, ExplainDriver.Kind.SHARE_OF_ROW).orElseThrow().share(),
                1e-9);
    }

    @Test
    public void comparesAgainstThePreviousColumnWhenTheyAreSiblings() {
        FakeCellsetView view = new FakeCellsetView(new double[] {100d, 120d})
                .column(0, q("1997", "Q1"), SALES)
                .column(1, q("1997", "Q2"), SALES)
                .row(0, USA);

        ExplainDriver delta = of(CellDrivers.analyze(view, 0, 1), ExplainDriver.Kind.PREVIOUS_COLUMN)
                .orElseThrow();

        assertEquals(20d, delta.delta(), 1e-9);
        assertEquals(0.2d, delta.deltaPct(), 1e-9);
        assertEquals("Q1 1997", delta.caption());
    }

    /** Two quarters of DIFFERENT years are not siblings, so no period-over-period claim is made. */
    @Test
    public void noPreviousColumnDriverWhenTheColumnsAreNotSiblings() {
        FakeCellsetView view = new FakeCellsetView(new double[] {100d, 120d})
                .column(0, q("1997", "Q1"), SALES)
                .column(1, q("1998", "Q1"), SALES)
                .row(0, USA);

        assertTrue(of(CellDrivers.analyze(view, 0, 1), ExplainDriver.Kind.PREVIOUS_COLUMN)
                .isEmpty());
    }

    /** A year total next to its own quarter is a different kind of number, not a change. */
    @Test
    public void noPreviousColumnDriverBetweenAYearTotalAndItsQuarter() {
        FakeCellsetView view = new FakeCellsetView(new double[] {1000d, 120d})
                .column(0, year("1997"), SALES)
                .column(1, q("1997", "Q1"), SALES)
                .row(0, USA);
        assertTrue(of(CellDrivers.analyze(view, 0, 1), ExplainDriver.Kind.PREVIOUS_COLUMN)
                .isEmpty());
    }

    @Test
    public void noPreviousColumnDriverWhenBothColumnsShowTheSameMember() {
        FakeCellsetView view = new FakeCellsetView(new double[] {100d, 100d})
                .column(0, q("1997", "Q1"), SALES)
                .column(1, q("1997", "Q1"), SALES)
                .row(0, USA);
        assertTrue(of(CellDrivers.analyze(view, 0, 1), ExplainDriver.Kind.PREVIOUS_COLUMN)
                .isEmpty());
    }

    @Test
    public void noPreviousColumnDriverOnTheLeftmostColumn() {
        FakeCellsetView view = new FakeCellsetView(new double[] {100d})
                .column(0, q("1997", "Q1"), SALES)
                .row(0, USA);
        assertTrue(of(CellDrivers.analyze(view, 0, 0), ExplainDriver.Kind.PREVIOUS_COLUMN)
                .isEmpty());
    }

    @Test
    public void previousColumnDriverIsSkippedWhenTheNeighbourIsEmpty() {
        FakeCellsetView view = new FakeCellsetView(new double[] {Double.NaN, 120d})
                .column(0, q("1997", "Q1"), SALES)
                .column(1, q("1997", "Q2"), SALES)
                .row(0, USA);
        assertTrue(of(CellDrivers.analyze(view, 0, 1), ExplainDriver.Kind.PREVIOUS_COLUMN)
                .isEmpty());
    }

    @Test
    public void namesTheRowPeakWhenThisCellIsNotIt() {
        FakeCellsetView view = new FakeCellsetView(new double[] {100d, 400d})
                .column(0, q("1997", "Q1"), SALES)
                .column(1, q("1997", "Q2"), SALES)
                .row(0, USA);

        ExplainDriver peak =
                of(CellDrivers.analyze(view, 0, 0), ExplainDriver.Kind.ROW_PEAK).orElseThrow();
        assertEquals("Q2 1997", peak.caption());
        assertEquals(400d, peak.value(), 1e-9);
    }

    @Test
    public void noRowPeakDriverWhenThisCellIsTheRowPeak() {
        FakeCellsetView view = new FakeCellsetView(new double[] {400d, 100d})
                .column(0, q("1997", "Q1"), SALES)
                .column(1, q("1997", "Q2"), SALES)
                .row(0, USA);
        assertTrue(
                of(CellDrivers.analyze(view, 0, 0), ExplainDriver.Kind.ROW_PEAK).isEmpty());
    }

    @Test
    public void skipsShareOfColumnWhenTheColumnAddsUpToZero() {
        FakeCellsetView view = new FakeCellsetView(new double[] {0d, 0d})
                .column(0, SALES)
                .row(0, USA)
                .row(1, CellMember.of("[Customers].[Canada]", "Canada"));
        assertTrue(of(CellDrivers.analyze(view, 0, 0), ExplainDriver.Kind.SHARE_OF_COLUMN)
                .isEmpty());
    }

    @Test
    public void aCellWithNoDeclaredCoordinateStillExplains() {
        FakeCellsetView view = new FakeCellsetView(new double[] {7d}).row(0, (CellMember) null);
        List<ExplainDriver> drivers = CellDrivers.analyze(view, 0, 0);
        // No column headers declared, so there is no measure and no member to name — the share
        // driver is emitted with an empty caption rather than the explain throwing.
        assertNull(view.measure());
        ExplainDriver share = of(drivers, ExplainDriver.Kind.SHARE_OF_COLUMN).orElseThrow();
        assertEquals(1d, share.share(), 1e-9);
        assertEquals("", share.caption());
        assertTrue(of(drivers, ExplainDriver.Kind.SHARE_OF_ROW).isEmpty());
    }
}
