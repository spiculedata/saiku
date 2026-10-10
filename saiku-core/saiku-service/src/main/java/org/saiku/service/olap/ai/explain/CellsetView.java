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

    /** Caption of the single measure on the column axis, or {@code null} when there isn't one. */
    default CellMember measure() {
        if (columnCount() == 0) {
            return null;
        }
        for (int i = columnHeader(0).size() - 1; i >= 0; i--) {
            CellMember m = columnHeader(0).get(i);
            if (m.measure()) {
                return m;
            }
        }
        return null;
    }
}
