/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase 2 of "explain this number": turns one cell of a {@link CellsetView} into the structured
 * findings a narrative can be written from.
 *
 * <p>Everything is derived from the cellset the user is already looking at — no follow-up queries.
 * That is a deliberate ceiling, and it is the reason the findings are of the shape they are: a
 * share of the column total and the row's rank always exist (they only need the cellset), while the
 * period-over-period comparison exists only when the cellset happens to put the previous period
 * next to this one. Absent a driver, the panel says nothing about it rather than guessing — a
 * follow-up query for a mover the schema can't express would fail with a 500 the user can't act on,
 * whereas "no comparison available in this view" is a true sentence.
 *
 * <p>Every comparison is scoped to cells of the clicked cell's own measure (saiku#2192): when a
 * row or column mixes measures, the cells of another measure are not part of any total, rank or
 * peak the drivers report, and a direction left with a single same-measure cell yields no driver
 * at all.
 */
public final class CellDrivers {

    private CellDrivers() {}

    /**
     * Analyse the cell at {@code (row, column)}.
     *
     * @return the findings, most load-bearing first; empty when the cell has no numeric value
     */
    public static List<ExplainDriver> analyze(CellsetView view, int row, int column) {
        List<ExplainDriver> drivers = new ArrayList<>();
        double value = view.value(row, column);
        if (Double.isNaN(value)) {
            return drivers;
        }
        // The measure of the cell being explained, by unique name. Every cross-cell comparison
        // below is scoped to cells of the same measure (saiku#2192): a row holding Unit Sales and
        // Store Sales cells has no row total, and summing or ranking across the two would be
        // arithmetic over numbers of different kinds.
        String measure = measureOf(view, row, column);
        CellMember rowMember = deepest(view.rowHeader(row));
        CellMember columnMember = deepestNonMeasure(view.columnHeader(column));

        Sum columnTotal = total(view, column, true, measure);
        if (comparable(columnTotal) && columnTotal.total() != 0d) {
            drivers.add(new ExplainDriver(
                    ExplainDriver.Kind.SHARE_OF_COLUMN,
                    rowMember == null ? null : rowMember.uniqueName(),
                    rowMember == null ? "" : rowMember.caption(),
                    "share of the column total",
                    value,
                    value / columnTotal.total(),
                    null,
                    null));
        }

        Integer[] rank = rankInColumn(view, row, column, value, measure);
        if (rank != null) {
            drivers.add(new ExplainDriver(
                    ExplainDriver.Kind.RANK_IN_COLUMN,
                    rowMember == null ? null : rowMember.uniqueName(),
                    rowMember == null ? "" : rowMember.caption(),
                    rank[1] + " of " + rank[0] + " rows with a value in this column",
                    value,
                    null,
                    null,
                    null));
        }

        Sum rowTotal = total(view, row, false, measure);
        if (comparable(rowTotal) && rowTotal.total() != 0d) {
            drivers.add(new ExplainDriver(
                    ExplainDriver.Kind.SHARE_OF_ROW,
                    columnMember == null ? null : columnMember.uniqueName(),
                    columnMember == null ? "" : columnMember.caption(),
                    "share of the row total",
                    value,
                    value / rowTotal.total(),
                    null,
                    null));
        }

        ExplainDriver previous = previousColumn(view, row, column, value, measure);
        if (previous != null) {
            drivers.add(previous);
        }

        ExplainDriver peak = rowPeak(view, row, column, value, measure);
        if (peak != null) {
            drivers.add(peak);
        }
        return drivers;
    }

    /**
     * Compare against the column immediately to the left, but only when the two columns are
     * siblings: their leaf members must have the same parent member. Two quarters of one year
     * qualify; a year total next to a quarter does not, because the "change" between them is not a
     * change — and neither does a column that repeats the same member.
     */
    private static ExplainDriver previousColumn(CellsetView view, int row, int column, double value, String measure) {
        if (column <= 0) {
            return null;
        }
        // The neighbour must hold the same measure — a change between a Unit Sales cell and a
        // Store Sales cell is not a change, it is two different numbers (saiku#2192).
        if (!sameMeasure(view, row, column - 1, measure)) {
            return null;
        }
        CellMember here = deepestNonMeasure(view.columnHeader(column));
        CellMember before = deepestNonMeasure(view.columnHeader(column - 1));
        if (here == null || before == null) {
            return null;
        }
        if (!Objects.equals(here.parentUniqueName(), before.parentUniqueName())) {
            return null;
        }
        if (here.uniqueName().equals(before.uniqueName())) {
            return null;
        }
        double previousValue = view.value(row, column - 1);
        if (Double.isNaN(previousValue)) {
            return null;
        }
        double delta = value - previousValue;
        Double deltaPct = previousValue == 0d ? null : delta / previousValue;
        // The driver names the member it is compared AGAINST, not the cell's own member — that is
        // what the narrative's "above/below <caption> in the same row" sentence needs.
        return new ExplainDriver(
                ExplainDriver.Kind.PREVIOUS_COLUMN,
                before.uniqueName(),
                before.caption(),
                "change against the previous column of the same row",
                value,
                null,
                delta,
                deltaPct);
    }

    /** The largest cell of the same measure in the same row, when it isn't the cell itself. */
    private static ExplainDriver rowPeak(CellsetView view, int row, int column, double value, String measure) {
        int peak = -1;
        double peakValue = Double.NaN;
        int comparable = 0;
        int excluded = 0;
        for (int c = 0; c < view.columnCount(); c++) {
            if (!sameMeasure(view, row, c, measure)) {
                if (!Double.isNaN(view.value(row, c))) {
                    excluded++;
                }
                continue;
            }
            double candidate = view.value(row, c);
            if (Double.isNaN(candidate)) {
                continue;
            }
            comparable++;
            if (Double.isNaN(peakValue) || candidate > peakValue) {
                peakValue = candidate;
                peak = c;
            }
        }
        if (peak < 0 || peak == column || !comparable(comparable, excluded)) {
            return null;
        }
        CellMember member = deepestNonMeasure(view.columnHeader(peak));
        return new ExplainDriver(
                ExplainDriver.Kind.ROW_PEAK,
                member == null ? null : member.uniqueName(),
                member == null ? "" : member.caption(),
                "largest cell in this row",
                peakValue,
                value == 0d ? null : peakValue / value,
                peakValue - value,
                value == 0d ? null : (peakValue - value) / Math.abs(value));
    }

    /**
     * Sum of a column (down) or a row (across), counting only cells of the same measure as the
     * explained cell; {@code null} when nothing numeric was found. Cells of another measure are
     * excluded from the sum but counted in {@code excluded}, so the caller can tell a genuine
     * total from a mixed one.
     */
    private record Sum(double total, int comparable, int excluded) {}

    private static Sum total(CellsetView view, int index, boolean downColumn, String measure) {
        int extent = downColumn ? view.rowCount() : view.columnCount();
        double sum = 0d;
        int comparable = 0;
        int excluded = 0;
        for (int i = 0; i < extent; i++) {
            int r = downColumn ? i : index;
            int c = downColumn ? index : i;
            if (!sameMeasure(view, r, c, measure)) {
                if (!Double.isNaN(view.value(r, c))) {
                    excluded++;
                }
                continue;
            }
            double v = view.value(r, c);
            if (Double.isNaN(v)) {
                continue;
            }
            sum += v;
            comparable++;
        }
        return comparable > 0 ? new Sum(sum, comparable, excluded) : null;
    }

    /** True when the restricted comparison is still a comparison — at least two cells of one measure. */
    private static boolean comparable(Sum sum) {
        return sum != null && comparable(sum.comparable(), sum.excluded());
    }

    /**
     * A driver whose comparison lost cells to the measure scoping needs two survivors to say
     * anything: a "total" of one cell or a peak with no rival is no comparison at all, and the
     * driver is dropped rather than reported — the same treatment every other absent comparison
     * gets ("no comparison available in this view"). A comparison that excluded nothing behaves
     * exactly as before, whatever its size.
     */
    private static boolean comparable(int comparable, int excluded) {
        return excluded <= 0 || comparable >= 2;
    }

    /** {@code [rowsWithAValue, rank]} or {@code null} when the column is empty. */
    private static Integer[] rankInColumn(CellsetView view, int row, int column, double value, String measure) {
        int withValue = 0;
        int greater = 0;
        int excluded = 0;
        for (int r = 0; r < view.rowCount(); r++) {
            if (!sameMeasure(view, r, column, measure)) {
                if (!Double.isNaN(view.value(r, column))) {
                    excluded++;
                }
                continue;
            }
            double v = view.value(r, column);
            if (Double.isNaN(v)) {
                continue;
            }
            withValue++;
            if (v > value) {
                greater++;
            }
        }
        if (withValue == 0 || !comparable(withValue, excluded)) {
            return null;
        }
        return new Integer[] {withValue, greater + 1};
    }

    /** The measure on the cell's own coordinate (column axis first, then row axis), or null. */
    private static String measureOf(CellsetView view, int row, int column) {
        CellMember measure = view.measure(row, column);
        return measure == null ? null : measure.uniqueName();
    }

    /**
     * True when {@code (r, c)} holds the same measure as the explained cell. A cell with no
     * measure on its coordinate only compares against other measureless cells — adding an
     * unqualified cell to a measured one would be the same cross-measure arithmetic.
     */
    private static boolean sameMeasure(CellsetView view, int r, int c, String measure) {
        return Objects.equals(measureOf(view, r, c), measure);
    }

    private static CellMember deepest(List<CellMember> header) {
        return header.isEmpty() ? null : header.get(header.size() - 1);
    }

    /** The column-axis member a driver should name: the leaf, not the measure above it. */
    private static CellMember deepestNonMeasure(List<CellMember> header) {
        for (int i = header.size() - 1; i >= 0; i--) {
            if (!header.get(i).measure()) {
                return header.get(i);
            }
        }
        return null;
    }
}
