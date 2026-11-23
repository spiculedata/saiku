/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hand-built {@link CellsetView} for the unit tests: a grid of values plus a row coordinate and a
 * column coordinate per index, which is everything the explain pipeline reads. {@link Double#NaN}
 * is the empty-cell marker, matching olap4j.
 *
 * <p>Each column may carry several members (parent + leaf + measure), so the "are these two
 * columns siblings" logic in {@link CellDrivers} can be exercised without an olap4j cellset. Rows
 * are given as one {@code double[]} per data row, each entry being one data column — the
 * cellset's own storage order.
 */
final class FakeCellsetView implements CellsetView {

    private final List<List<CellMember>> rowHeaders = new ArrayList<>();
    private final List<List<CellMember>> columnHeaders = new ArrayList<>();
    private final double[][] values;

    FakeCellsetView(double[]... rows) {
        this.values = rows;
    }

    /** Row coordinate: one member, the common case. */
    FakeCellsetView row(int r, CellMember member) {
        return row(r, member == null ? List.of() : List.of(member));
    }

    /** Row coordinate as a full path, outermost first. */
    FakeCellsetView row(int r, List<CellMember> members) {
        while (rowHeaders.size() <= r) {
            rowHeaders.add(List.of());
        }
        rowHeaders.set(r, List.copyOf(members));
        return this;
    }

    /** Column coordinate; columns must be declared in index order. */
    FakeCellsetView column(int c, CellMember... members) {
        columnHeaders.add(List.of(members));
        return this;
    }

    @Override
    public int rowCount() {
        return values.length;
    }

    @Override
    public int columnCount() {
        return columnHeaders.size();
    }

    @Override
    public double value(int row, int column) {
        return column < values[row].length ? values[row][column] : Double.NaN;
    }

    @Override
    public String formatted(int row, int column) {
        double v = value(row, column);
        return Double.isNaN(v) ? null : String.format(Locale.ROOT, "%,.2f", v);
    }

    @Override
    public List<CellMember> rowHeader(int row) {
        return row < rowHeaders.size() ? rowHeaders.get(row) : List.of();
    }

    @Override
    public List<CellMember> columnHeader(int column) {
        return column < columnHeaders.size() ? columnHeaders.get(column) : List.of();
    }
}
