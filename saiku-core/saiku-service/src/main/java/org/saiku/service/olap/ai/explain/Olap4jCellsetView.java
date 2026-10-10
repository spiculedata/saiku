/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.olap4j.Cell;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.OlapException;
import org.olap4j.Position;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Member;

/**
 * The one class in this package that talks to olap4j: adapts an executed {@link CellSet} to
 * {@link CellsetView}.
 *
 * <p>Cells are addressed through {@link Position} objects taken from the axes rather than through
 * hand-assembled coordinates, because olap4j's own coordinate list is 1-based and the two result
 * axes are offset by their header bands. Going through positions means this class never has to
 * know that, and the {@code (row, column)} it accepts is the plain "nth data row / nth data column"
 * the UI has on a right-click.
 *
 * <p>Axis order follows the rest of the codebase: {@code getAxes().get(0)} is the column axis and
 * {@code getAxes().get(1)} the row axis (see
 * {@code org.saiku.web.rest.resources.cubedesigner.CubeDesignerResource#flatten}). A result with
 * one axis only — a {@code WHERE}-only MDX — has a single implied row.
 */
public final class Olap4jCellsetView implements CellsetView {

    private final CellSet cellSet;
    private final List<Position> columnPositions;
    private final List<Position> rowPositions;

    public Olap4jCellsetView(CellSet cellSet) {
        if (cellSet == null) {
            throw new IllegalArgumentException("cellSet required");
        }
        this.cellSet = cellSet;
        List<CellSetAxis> axes = cellSet.getAxes();
        this.columnPositions = axes.isEmpty() ? List.of() : axes.get(0).getPositions();
        this.rowPositions = axes.size() > 1 ? axes.get(1).getPositions() : List.of();
    }

    @Override
    public int rowCount() {
        return rowPositions.size();
    }

    @Override
    public int columnCount() {
        return columnPositions.size();
    }

    /**
     * {@link Cell#getValue()} is an {@code Object} in olap4j — a number for a populated measure, a
     * formatted {@code String} for some text cells, {@code null} or a boxed {@code NaN} for an empty
     * one. Anything that isn't a number is reported as {@link Double#NaN} so the driver analysis
     * treats it as an empty cell instead of failing.
     */
    @Override
    public double value(int row, int column) {
        Object value = cell(row, column).getValue();
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @Override
    public String formatted(int row, int column) {
        return cell(row, column).getFormattedValue();
    }

    @Override
    public List<CellMember> rowHeader(int row) {
        return rowPositions.isEmpty() || row >= rowPositions.size() ? List.of() : members(rowPositions.get(row));
    }

    @Override
    public List<CellMember> columnHeader(int column) {
        return columnPositions.isEmpty() || column >= columnPositions.size()
                ? List.of()
                : members(columnPositions.get(column));
    }

    private Cell cell(int row, int column) {
        Position col = columnPositions.get(column);
        if (rowPositions.isEmpty()) {
            // One-axis result (a WHERE-only MDX): the single row is implied.
            return cellSet.getCell(col);
        }
        return cellSet.getCell(col, rowPositions.get(row));
    }

    private static List<CellMember> members(Position position) {
        List<Member> tuple = position.getMembers();
        if (tuple == null || tuple.isEmpty()) {
            return List.of();
        }
        List<CellMember> out = new ArrayList<>(tuple.size());
        for (Member member : tuple) {
            out.add(new CellMember(
                    member.getUniqueName(), caption(member), isMeasure(member), parentUniqueName(member)));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The parent member's unique name, or {@code null} for a root member. Carried on
     * {@link CellMember} because it is what makes "are these two periods siblings?" answerable —
     * two quarters of a year share a parent, a year total and its own quarter do not.
     */
    private static String parentUniqueName(Member member) {
        try {
            Member parent = member.getParentMember();
            return parent == null ? null : parent.getUniqueName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The member's caption, or its name when the caption is blank or unavailable. The cellset is
     * read back from the session's query context, so it can outlive the connection that produced
     * it, and Mondrian's {@code getCaption()} asks that (by then null) connection for its locale and
     * throws a {@link NullPointerException}. {@code getName()} and {@code getUniqueName()} need no
     * connection, so a detached member is still explained, just under its name.
     */
    private static String caption(Member member) {
        String caption = null;
        try {
            caption = member.getCaption();
        } catch (RuntimeException e) {
            // detached from its connection: fall back to the name below
        }
        return caption == null || caption.isBlank() ? member.getName() : caption;
    }

    /**
     * A member is a measure when its dimension's type is {@link Dimension.Type#MEASURE}. Asking the
     * dimension (rather than inspecting the unique name for {@code [Measures]}) is what keeps this
     * right for a cube whose measure dimension is named something else, and for calculated
     * measures. {@code getDimensionType} declares {@link OlapException}, so a metadata failure
     * degrades to "not a measure" — the cell is still explained, it just keeps the member on the
     * slicer side of the cell MDX.
     */
    private static boolean isMeasure(Member member) {
        try {
            Dimension dimension = member.getDimension();
            return dimension != null && dimension.getDimensionType() == Dimension.Type.MEASURE;
        } catch (OlapException | RuntimeException e) {
            return false;
        }
    }
}
