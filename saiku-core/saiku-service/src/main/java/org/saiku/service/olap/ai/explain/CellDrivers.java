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
        CellMember rowMember = deepest(view.rowHeader(row));
        CellMember columnMember = deepestNonMeasure(view.columnHeader(column));

        Double columnTotal = total(view, column, true);
        if (columnTotal != null && columnTotal != 0d) {
            drivers.add(new ExplainDriver(
                    ExplainDriver.Kind.SHARE_OF_COLUMN,
                    rowMember == null ? null : rowMember.uniqueName(),
                    rowMember == null ? "" : rowMember.caption(),
                    "share of the column total",
                    value,
                    value / columnTotal,
                    null,
                    null));
        }

        Integer[] rank = rankInColumn(view, row, column, value);
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

        Double rowTotal = total(view, row, false);
        if (rowTotal != null && rowTotal != 0d) {
            drivers.add(new ExplainDriver(
                    ExplainDriver.Kind.SHARE_OF_ROW,
                    columnMember == null ? null : columnMember.uniqueName(),
                    columnMember == null ? "" : columnMember.caption(),
                    "share of the row total",
                    value,
                    value / rowTotal,
                    null,
                    null));
        }

        ExplainDriver previous = previousColumn(view, row, column, value);
        if (previous != null) {
            drivers.add(previous);
        }

        ExplainDriver peak = rowPeak(view, row, column, value);
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
    private static ExplainDriver previousColumn(CellsetView view, int row, int column, double value) {
        if (column <= 0) {
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

    /** The largest cell in the same row, when it isn't the cell itself. */
    private static ExplainDriver rowPeak(CellsetView view, int row, int column, double value) {
        int peak = -1;
        double peakValue = Double.NaN;
        for (int c = 0; c < view.columnCount(); c++) {
            double candidate = view.value(row, c);
            if (Double.isNaN(candidate)) {
                continue;
            }
            if (Double.isNaN(peakValue) || candidate > peakValue) {
                peakValue = candidate;
                peak = c;
            }
        }
        if (peak < 0 || peak == column) {
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

    /** Sum of a column (down) or a row (across); {@code null} when nothing numeric was found. */
    private static Double total(CellsetView view, int index, boolean downColumn) {
        int extent = downColumn ? view.rowCount() : view.columnCount();
        double sum = 0d;
        boolean any = false;
        for (int i = 0; i < extent; i++) {
            double v = downColumn ? view.value(i, index) : view.value(index, i);
            if (Double.isNaN(v)) {
                continue;
            }
            sum += v;
            any = true;
        }
        return any ? sum : null;
    }

    /** {@code [rowsWithAValue, rank]} or {@code null} when the column is empty. */
    private static Integer[] rankInColumn(CellsetView view, int row, int column, double value) {
        int withValue = 0;
        int greater = 0;
        for (int r = 0; r < view.rowCount(); r++) {
            double v = view.value(r, column);
            if (Double.isNaN(v)) {
                continue;
            }
            withValue++;
            if (v > value) {
                greater++;
            }
        }
        return withValue == 0 ? null : new Integer[] {withValue, greater + 1};
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
