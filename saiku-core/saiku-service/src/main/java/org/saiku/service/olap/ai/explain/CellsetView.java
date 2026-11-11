/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.List;

/**
 * A read-only view of one executed cellset, reduced to what "explain this number" needs.
 *
 * <p>Coordinates are the olap4j ones: {@code row} counts data rows (the column-header band is NOT
 * part of the row axis) and {@code column} counts data columns (the row-header band is not part of
 * the column axis) — which is exactly what {@code CellsetTable.svelte} has on a right-click.
 *
 * <p>Everything above this interface ({@link CellMdx}, {@link CellNarrative}, the driver analysis in
 * {@link CellExplainService}) is pure logic over these primitives, so it is unit-testable without a
 * Mondrian connection. {@link Olap4jCellsetView} is the only class that touches olap4j.
 */
public interface CellsetView {

    /** Number of data rows in the row axis. */
    int rowCount();

    /** Number of data columns in the column axis. */
    int columnCount();

    /**
     * Unformatted numeric value of the cell; {@code Double.NaN} when the cell is empty (an
     * olap4j "no value" cell rather than a genuine null).
     */
    double value(int row, int column);

    /** Formatted value as the cellset rendered it (currency, percent, …), or {@code null}. */
    String formatted(int row, int column);

    /** Members identifying the row coordinate, outermost first. */
    List<CellMember> rowHeader(int row);

    /** Members identifying the column coordinate, measures last. */
    List<CellMember> columnHeader(int column);

    /** True when {@code (row, column)} addresses a cell of this cellset. */
    default boolean inBounds(int row, int column) {
        return row >= 0 && column >= 0 && row < rowCount() && column < columnCount();
    }

    /**
     * The measure of the cell at {@code (row, column)}: a measure member on that cell's own column
     * coordinate, else one on its row coordinate (a measure can sit on either axis), or
     * {@code null} when the axes declare no measure for it.
     *
     * <p>This is deliberately per cell and not per cellset (saiku#2192): with more than one
     * measure on the columns, the first column's header names the wrong measure for every other
     * column, so the coordinate of the cell being explained is the only place to ask.
     */
    default CellMember measure(int row, int column) {
        for (int i = columnHeader(column).size() - 1; i >= 0; i--) {
            CellMember m = columnHeader(column).get(i);
            if (m.measure()) {
                return m;
            }
        }
        for (int i = rowHeader(row).size() - 1; i >= 0; i--) {
            CellMember m = rowHeader(row).get(i);
            if (m.measure()) {
                return m;
            }
        }
        return null;
    }
}
