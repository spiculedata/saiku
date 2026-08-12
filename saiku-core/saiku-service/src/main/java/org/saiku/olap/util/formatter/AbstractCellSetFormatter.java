/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.olap.util.formatter;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.olap4j.Cell;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.Position;
import org.olap4j.impl.CoordinateIterator;
import org.olap4j.impl.Olap4jUtil;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.Property;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.Matrix;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.util.SaikuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private base class holding the byte-identical scaffolding shared by
 * the three {@link ICellSetFormatter} implementations — {@link CellSetFormatter},
 * {@link HierarchicalCellSetFormatter} and {@link FlattenedCellSetFormatter}.
 *
 * <p>Lifted here as part of issue #1163 (behaviour-preserving refactor): the
 * nested {@code AxisInfo}/{@code AxisOrdinalInfo} descriptors, the static
 * {@link #cellIter(int[], CellSet)} iterator, the {@code matrix} field, the
 * {@link #format(CellSet)} driver, {@link #computeAxisInfo(CellSetAxis)}, the
 * common two-axis {@link #formatPage}, and the shared
 * {@link #populateCell(Matrix, Cell, java.util.List, int, int)} per-data-cell
 * value block (including the three exception-swallow sites, in the exact
 * {@link CellPropertyExtractor}-then-{@link Olap4jUtil#parseFormattedCellValue}
 * overwrite order).
 *
 * <p>{@link #populateAxis} stays abstract: the three subclasses legitimately
 * differ there (members[] clearing, expanded scoping, levelindex emission,
 * same-nulling, parent-walk presence, setter order) and those bodies are
 * preserved verbatim in each subclass.
 *
 * <p>The shape of the matrix output is contractually frozen — it feeds query
 * results to the UI and the AI Query API and is pinned by
 * {@code CellSetFormatterCharacterizationTest}.
 */
abstract class AbstractCellSetFormatter implements ICellSetFormatter {

    private static final Logger log = LoggerFactory.getLogger(AbstractCellSetFormatter.class);

    /**
     * Description of an axis.
     */
    protected static class AxisInfo {
        final List<AxisOrdinalInfo> ordinalInfos;

        /**
         * Creates an AxisInfo.
         *
         * @param ordinalCount
         *            Number of hierarchies on this axis
         */
        AxisInfo(final int ordinalCount) {
            ordinalInfos = new ArrayList<>(ordinalCount);
            for (int i = 0; i < ordinalCount; i++) {
                ordinalInfos.add(new AxisOrdinalInfo());
            }
        }

        /**
         * Returns the number of matrix columns required by this axis. The sum of the width of the hierarchies on this
         * axis.
         *
         * @return Width of axis
         */
        public int getWidth() {
            int width = 0;
            for (final AxisOrdinalInfo info : ordinalInfos) {
                width += info.getWidth();
            }
            return width;
        }
    }

    /**
     * Description of a particular hierarchy mapped to an axis.
     */
    protected static class AxisOrdinalInfo {
        private final List<Integer> depths = new ArrayList<>();
        private final Map<Integer, Level> depthLevel = new HashMap<>();

        public int getWidth() {
            return depths.size();
        }

        public List<Integer> getDepths() {
            return depths;
        }

        public Level getLevel(Integer depth) {
            return depthLevel.get(depth);
        }

        public void addLevel(Integer depth, Level level) {
            depthLevel.put(depth, level);
        }
    }

    /**
     * Returns an iterator over cells in a result.
     */
    protected static Iterable<Cell> cellIter(final int[] pageCoords, final CellSet cellSet) {
        return new Iterable<Cell>() {
            public Iterator<Cell> iterator() {
                final int[] axisDimensions = new int[cellSet.getAxes().size() - pageCoords.length];
                assert pageCoords.length <= axisDimensions.length;
                for (int i = 0; i < axisDimensions.length; i++) {
                    final CellSetAxis axis = cellSet.getAxes().get(i);
                    axisDimensions[i] = axis.getPositions().size();
                }
                final CoordinateIterator coordIter = new CoordinateIterator(axisDimensions, true);
                return new Iterator<Cell>() {
                    public boolean hasNext() {
                        return coordIter.hasNext();
                    }

                    public Cell next() {
                        final int[] ints = coordIter.next();
                        final AbstractList<Integer> intList = new AbstractList<Integer>() {
                            @Override
                            public Integer get(final int index) {
                                return index < ints.length ? ints[index] : pageCoords[index - ints.length];
                            }

                            @Override
                            public int size() {
                                return pageCoords.length + ints.length;
                            }
                        };
                        return cellSet.getCell(intList);
                    }

                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    protected Matrix matrix;

    public Matrix format(final CellSet cellSet) {
        // Compute how many rows are required to display the columns axis.
        final CellSetAxis columnsAxis;
        if (cellSet.getAxes().size() > 0) {
            columnsAxis = cellSet.getAxes().get(0);
        } else {
            columnsAxis = null;
        }
        final AxisInfo columnsAxisInfo = computeAxisInfo(columnsAxis);

        // Compute how many columns are required to display the rows axis.
        final CellSetAxis rowsAxis;
        if (cellSet.getAxes().size() > 1) {
            rowsAxis = cellSet.getAxes().get(1);
        } else {
            rowsAxis = null;
        }
        final AxisInfo rowsAxisInfo = computeAxisInfo(rowsAxis);

        if (cellSet.getAxes().size() > 2) {
            final int[] dimensions = new int[cellSet.getAxes().size() - 2];
            for (int i = 2; i < cellSet.getAxes().size(); i++) {
                final CellSetAxis cellSetAxis = cellSet.getAxes().get(i);
                dimensions[i - 2] = cellSetAxis.getPositions().size();
            }
            for (final int[] pageCoords : CoordinateIterator.iterate(dimensions)) {
                matrix = formatPage(cellSet, pageCoords, columnsAxis, columnsAxisInfo, rowsAxis, rowsAxisInfo);
            }
        } else {
            matrix = formatPage(cellSet, new int[] {}, columnsAxis, columnsAxisInfo, rowsAxis, rowsAxisInfo);
        }

        return matrix;
    }

    /**
     * Computes a description of an axis.
     *
     * @param axis
     *            Axis
     * @return Description of axis
     */
    protected AxisInfo computeAxisInfo(final CellSetAxis axis) {
        if (axis == null) {
            return new AxisInfo(0);
        }
        final AxisInfo axisInfo =
                new AxisInfo(axis.getAxisMetaData().getHierarchies().size());
        int p = -1;
        for (final Position position : axis.getPositions()) {
            ++p;
            int k = -1;
            for (final Member member : position.getMembers()) {
                ++k;
                final AxisOrdinalInfo axisOrdinalInfo = axisInfo.ordinalInfos.get(k);
                if (!axisOrdinalInfo.getDepths().contains(member.getDepth())) {
                    axisOrdinalInfo.getDepths().add(member.getDepth());
                    axisOrdinalInfo.addLevel(member.getDepth(), member.getLevel());
                    Collections.sort(axisOrdinalInfo.depths);
                }
            }
        }
        return axisInfo;
    }

    /**
     * Formats a two-dimensional page. This is the common implementation shared
     * by {@link CellSetFormatter} and {@link HierarchicalCellSetFormatter};
     * {@link FlattenedCellSetFormatter} overrides it wholesale to apply its
     * ignorex/ignorey row/column collapsing before delegating each surviving
     * data cell to {@link #populateCell}.
     *
     * @param cellSet
     *            Cell set
     * @param pageCoords
     *            Coordinates of page [page, chapter, section, ...]
     * @param columnsAxis
     *            Columns axis
     * @param columnsAxisInfo
     *            Description of columns axis
     * @param rowsAxis
     *            Rows axis
     * @param rowsAxisInfo
     *            Description of rows axis
     */
    protected Matrix formatPage(
            final CellSet cellSet,
            final int[] pageCoords,
            final CellSetAxis columnsAxis,
            final AxisInfo columnsAxisInfo,
            final CellSetAxis rowsAxis,
            final AxisInfo rowsAxisInfo) {

        // Figure out the dimensions of the blank rectangle in the top left
        // corner.
        final int yOffset = columnsAxisInfo.getWidth();
        final int xOffsset = rowsAxisInfo.getWidth();

        // Populate a string matrix
        final Matrix matrix = new Matrix(
                xOffsset + (columnsAxis == null ? 1 : columnsAxis.getPositions().size()),
                yOffset + (rowsAxis == null ? 1 : rowsAxis.getPositions().size()));

        // Populate corner
        List<Level> levels = new ArrayList<>();
        if (rowsAxis != null && rowsAxis.getPositions().size() > 0) {
            Position p = rowsAxis.getPositions().get(0);
            for (int m = 0; m < p.getMembers().size(); m++) {
                AxisOrdinalInfo a = rowsAxisInfo.ordinalInfos.get(m);
                for (Integer depth : a.getDepths()) {
                    levels.add(a.getLevel(depth));
                }
            }
            for (int x = 0; x < xOffsset; x++) {
                Level xLevel = levels.get(x);
                String s = xLevel.getCaption();
                for (int y = 0; y < yOffset; y++) {
                    final MemberCell memberInfo = new MemberCell(false, x > 0);
                    if (y == yOffset - 1) {
                        memberInfo.setRawValue(s);
                        memberInfo.setFormattedValue(s);
                        memberInfo.setProperty("__headertype", "row_header_header");
                        memberInfo.setProperty("levelindex", "" + levels.indexOf(xLevel));
                        memberInfo.setHierarchy(xLevel.getHierarchy().getUniqueName());
                        memberInfo.setParentDimension(xLevel.getDimension().getName());
                        memberInfo.setLevel(xLevel.getUniqueName());
                    }
                    matrix.set(x, y, memberInfo);
                }
            }
        }
        // Populate matrix with cells representing axes
        // noinspection SuspiciousNameCombination
        populateAxis(matrix, columnsAxis, columnsAxisInfo, true, xOffsset);
        populateAxis(matrix, rowsAxis, rowsAxisInfo, false, yOffset);

        // Populate cell values
        for (final Cell cell : cellIter(pageCoords, cellSet)) {
            final List<Integer> coordList = cell.getCoordinateList();
            int x = xOffsset;
            if (coordList.size() > 0) x += coordList.get(0);
            int y = yOffset;
            if (coordList.size() > 1) y += coordList.get(1);
            populateCell(matrix, cell, coordList, x, y);
        }
        return matrix;
    }

    /**
     * Builds a {@link DataCell} for a single olap4j data cell and writes it to
     * {@code matrix} at (x, y). This block is byte-identical across all three
     * formatters and is lifted here verbatim (issue #1163), preserving the
     * exact {@link CellPropertyExtractor}-then-{@link Olap4jUtil#parseFormattedCellValue}
     * property overwrite order.
     *
     * <p>The three {@code catch} blocks below are deliberate silent swallows
     * carried over from the original code: their control flow is UNCHANGED
     * (catch-and-absorb, never rethrow — the value falls through exactly as
     * before). The only addition in #1163 is a WARN log of the exception (a
     * generic message that never includes cell business values) so the
     * previously-silent failures are at least observable.
     *
     * @param matrix
     *            Matrix to populate
     * @param cell
     *            The olap4j cell
     * @param coordList
     *            The cell's coordinate list
     * @param x
     *            Target matrix x coordinate
     * @param y
     *            Target matrix y coordinate
     */
    protected void populateCell(
            final Matrix matrix, final Cell cell, final List<Integer> coordList, final int x, final int y) {
        final DataCell cellInfo = new DataCell(true, false, coordList);
        cellInfo.setCoordinates(cell.getCoordinateList());
        // saiku#773: surface the full set of olap4j StandardCellProperty
        // values (format string, fore/back colour, font flags, action
        // type, error text, etc.) Mondrian computes for this cell so
        // the REST/Arrow consumers can read them.
        cellInfo.setProperties(CellPropertyExtractor.extract(cell));

        if (cell.getValue() != null) {
            try {
                cellInfo.setRawNumber(cell.getDoubleValue());
            } catch (Exception e1) {
                // #1163: previously a silent swallow. Control flow is
                // unchanged — rawNumber simply stays unset and the cell
                // falls through exactly as before; we only log so the
                // failure is observable. No cell values are logged.
                log.warn("Could not read numeric cell value; leaving rawNumber unset", e1);
            }
        }
        String cellValue = cell.getFormattedValue(); // First try to get a
        // formatted value

        if (cellValue == null || cellValue.equals("null")) { // $NON-NLS-1$
            cellValue = ""; // $NON-NLS-1$
        }
        if (cellValue.length() < 1) {
            final Object value = cell.getValue();
            if (value == null || value.equals("null")) // $NON-NLS-1$
            cellValue = ""; // $NON-NLS-1$
            else {
                try {
                    // Design note: the fallback format is a global default; a per-query /
                    // per-execution format would be more correct if ever needed.
                    DecimalFormat myFormatter =
                            new DecimalFormat(SaikuProperties.formatDefautNumberFormat); // $NON-NLS-1$
                    DecimalFormatSymbols dfs = new DecimalFormatSymbols(SaikuProperties.locale);
                    myFormatter.setDecimalFormatSymbols(dfs);
                    cellValue = myFormatter.format(cell.getValue());
                } catch (Exception e) {
                    // #1163: previously a silent swallow (// TODO: handle
                    // exception). Control flow is unchanged — cellValue keeps
                    // its prior value and the cell falls through exactly as
                    // before; we only log so the failure is observable. No
                    // cell values are logged.
                    log.warn("Could not apply default number format to cell value", e);
                }
            }
            // the raw value
        }

        // Format string is relevant for Excel export
        // xmla cells can throw an error on this
        try {

            String formatString = (String) cell.getPropertyValue(Property.StandardCellProperty.FORMAT_STRING);
            if (formatString != null && !formatString.startsWith("|")) {
                cellInfo.setFormatString(formatString);
            } else {
                formatString = formatString.substring(1, formatString.length());
                cellInfo.setFormatString(formatString.substring(0, formatString.indexOf("|")));
            }
        } catch (Exception e) {
            // #1163: previously a silent swallow (// we tried). Control flow is
            // unchanged — formatString simply stays unset (or whatever was set
            // before the throw) and the cell falls through exactly as before;
            // we only log so the failure is observable. No cell values are
            // logged.
            log.warn("Could not derive format string for cell", e);
        }

        Map<String, String> cellProperties = new HashMap<>();
        String val = Olap4jUtil.parseFormattedCellValue(cellValue, cellProperties);
        if (!cellProperties.isEmpty()) {
            cellInfo.setProperties(cellProperties);
        }
        cellInfo.setFormattedValue(val);
        matrix.set(x, y, cellInfo);
    }

    /**
     * Populates cells in the matrix corresponding to a particular axis. The
     * three implementations legitimately differ (see class javadoc); each
     * subclass supplies its body verbatim.
     *
     * @param matrix
     *            Matrix to populate
     * @param axis
     *            Axis
     * @param axisInfo
     *            Description of axis
     * @param isColumns
     *            True if columns, false if rows
     * @param offset
     *            Ordinal of first cell to populate in matrix
     */
    protected abstract void populateAxis(
            final Matrix matrix,
            final CellSetAxis axis,
            final AxisInfo axisInfo,
            final boolean isColumns,
            final int offset);
}
