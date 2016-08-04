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
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.Property;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.Matrix;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.util.SaikuProperties;


public class CellSetFormatter implements ICellSetFormatter {
  /**
   * Description of an axis.
   */
  private static class AxisInfo {
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
  private static class AxisOrdinalInfo {
    private final List<Integer> depths = new ArrayList<>();
    private final Map<Integer,Level> depthLevel = new HashMap<>();

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
  private static Iterable<Cell> cellIter(final int[] pageCoords, final CellSet cellSet) {
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

  private Matrix matrix;

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
  private AxisInfo computeAxisInfo(final CellSetAxis axis) {
    if (axis == null) {
      return new AxisInfo(0);
    }
    final AxisInfo axisInfo = new AxisInfo(axis.getAxisMetaData().getHierarchies().size());
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
   * Formats a two-dimensional page.
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
  private Matrix formatPage(final CellSet cellSet, final int[] pageCoords, final CellSetAxis columnsAxis,
                            final AxisInfo columnsAxisInfo, final CellSetAxis rowsAxis, final AxisInfo rowsAxisInfo) {

    // Figure out the dimensions of the blank rectangle in the top left
    // corner.
    final int yOffset = columnsAxisInfo.getWidth();
    final int xOffsset = rowsAxisInfo.getWidth();

    // Populate a string matrix
    final Matrix matrix = new Matrix(xOffsset + (columnsAxis == null ? 1 : columnsAxis.getPositions().size()),
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
          if (y == yOffset-1) {
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
      if (coordList.size() > 0)
        x += coordList.get(0);
      int y = yOffset;
      if (coordList.size() > 1)
        y += coordList.get(1);
      final DataCell cellInfo = new DataCell(true, false, coordList);
      cellInfo.setCoordinates(cell.getCoordinateList());

      if (cell.getValue() != null) {
        try {
          cellInfo.setRawNumber(cell.getDoubleValue());
        } catch (Exception e1) {
        }
      }
      String cellValue = cell.getFormattedValue(); // First try to get a
      // formatted value

      if (cellValue == null || cellValue.equals("null")) { //$NON-NLS-1$
        cellValue =""; //$NON-NLS-1$
      }
      if ( cellValue.length() < 1) {
        final Object value =  cell.getValue();
        if (value == null  || value.equals("null")) //$NON-NLS-1$
          cellValue = ""; //$NON-NLS-1$
        else {
          try {
            // TODO this needs to become query / execution specific
            DecimalFormat myFormatter = new DecimalFormat(SaikuProperties.formatDefautNumberFormat); //$NON-NLS-1$
            DecimalFormatSymbols dfs = new DecimalFormatSymbols(SaikuProperties.locale);
            myFormatter.setDecimalFormatSymbols(dfs);
            cellValue = myFormatter.format(cell.getValue());
          }
          catch (Exception e) {
            // TODO: handle exception
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
        // we tried
      }

      Map<String, String> cellProperties = new HashMap<>();
      String val = Olap4jUtil.parseFormattedCellValue(cellValue, cellProperties);
      if (!cellProperties.isEmpty()) {
        cellInfo.setProperties(cellProperties);
      }
      cellInfo.setFormattedValue(val);
      matrix.set(x, y, cellInfo);
    }
    return matrix;

  }

  /**
   * Populates cells in the matrix corresponding to a particular axis.
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
  private void populateAxis(final Matrix matrix, final CellSetAxis axis, final AxisInfo axisInfo,
                            final boolean isColumns, final int offset) {

    if (axis == null)
      return;
    final Member[] prevMembers = new Member[axisInfo.getWidth()];
    final MemberCell[] prevMemberInfo = new MemberCell[axisInfo.getWidth()];
    final Member[] members = new Member[axisInfo.getWidth()];

    for (int i = 0; i < axis.getPositions().size(); i++) {
      final int x = offset + i;
      final Position position = axis.getPositions().get(i);
      int yOffset = 0;
      final List<Member> memberList = position.getMembers();
      final Map<Hierarchy,List<Integer>> lvls = new HashMap<>();
      for (int j = 0; j < memberList.size(); j++) {
        Member member = memberList.get(j);
        final AxisOrdinalInfo ordinalInfo = axisInfo.ordinalInfos.get(j);
        List<Integer> depths = ordinalInfo.depths;
        Collections.sort(depths);
        lvls.put(member.getHierarchy(), depths);
        if (ordinalInfo.getDepths().size() > 0 && member.getDepth() < ordinalInfo.getDepths().get(0))
          break;
        final int y = yOffset + ordinalInfo.depths.indexOf(member.getDepth());
        members[y] = member;
        yOffset += ordinalInfo.getWidth();
      }

      boolean expanded = false;
      boolean same = true;
      for (int y = 0; y < members.length; y++) {
        final MemberCell memberInfo = new MemberCell();
        final Member member = members[y];
        expanded = false;
        int index = memberList.indexOf(member);
        if (index >= 0) {
          final AxisOrdinalInfo ordinalInfo = axisInfo.ordinalInfos.get(index);
          int depth_i = ordinalInfo.getDepths().indexOf(member.getDepth());
          if (depth_i > 0) {
            expanded = true;
          }
        }
        memberInfo.setExpanded(expanded);
        same = same && i > 0 && Olap4jUtil.equal(prevMembers[y], member);


        if (member != null) {
          if (lvls != null && lvls.get(member.getHierarchy()) != null) {
            memberInfo.setProperty("levelindex", "" + lvls.get(member.getHierarchy()).indexOf(member.getLevel().getDepth()));
          }
          if (x - 1 == offset)
            memberInfo.setLastRow(true);

          matrix.setOffset(offset);
          memberInfo.setRawValue(member.getUniqueName());
          memberInfo.setFormattedValue(member.getCaption()); // First try to get a formatted value
          memberInfo.setParentDimension(member.getDimension().getName());
          memberInfo.setHierarchy(member.getHierarchy().getUniqueName());
          memberInfo.setLevel(member.getLevel().getUniqueName());
          memberInfo.setUniquename(member.getUniqueName());
          //					try {
          //						memberInfo.setChildMemberCount(member.getChildMemberCount());
          //					} catch (OlapException e) {
          //						e.printStackTrace();
          //						throw new RuntimeException(e);
          //					}
          //					NamedList<Property> values = member.getLevel().getProperties();
          //					for(int j=0; j<values.size();j++){
          //						String val;
          //						try {
          //							val = member.getPropertyFormattedValue(values.get(j));
          //						} catch (OlapException e) {
          //							e.printStackTrace();
          //							throw new RuntimeException(e);
          //						}
          //						memberInfo.setProperty(values.get(j).getCaption(), val);
          //					}

          //					if (y > 0) {
          //						for (int previ = y-1; previ >= 0;previ--) {
          //							if(prevMembers[previ] != null) {
          //								memberInfo.setRightOf(prevMemberInfo[previ]);
          //								memberInfo.setRightOfDimension(prevMembers[previ].getDimension().getName());
          //								previ = -1;
          //							}
          //						}
          //					}
          //
          //
          //					if (member.getParentMember() != null)
          //						memberInfo.setParentMember(member.getParentMember().getUniqueName());

        } else {
          memberInfo.setRawValue(null);
          memberInfo.setFormattedValue(null);
          memberInfo.setParentDimension(null);
        }

        if (isColumns) {
          memberInfo.setRight(false);
          memberInfo.setSameAsPrev(same);
          if (member != null)
            memberInfo.setParentDimension(member.getDimension().getName());
          matrix.set(x, y, memberInfo);
        } else {
          memberInfo.setRight(false);
          memberInfo.setSameAsPrev(false);
          matrix.set(y, x, memberInfo);
        }
        int x_parent = isColumns ? x : y-1;
        int y_parent = isColumns ? y-1 : x;
        if (index >= 0) {
          final AxisOrdinalInfo ordinalInfo = axisInfo.ordinalInfos.get(index);
          int depth_i = ordinalInfo.getDepths().indexOf(member.getDepth());
          while (depth_i > 0) {
            depth_i--;
            int parentDepth = (ordinalInfo.getDepths().get(depth_i));
            Member parent = member.getParentMember();
            while (parent != null && parent.getDepth() > parentDepth) {
              parent = parent.getParentMember();
            }
            final MemberCell pInfo = new MemberCell();
            if (parent != null) {
              pInfo.setRawValue(parent.getUniqueName());
              pInfo.setFormattedValue(parent.getCaption()); // First try to get a formatted value
              pInfo.setParentDimension(parent.getDimension().getName());
              pInfo.setHierarchy(parent.getHierarchy().getUniqueName());
              pInfo.setUniquename(parent.getUniqueName());
              pInfo.setLevel(parent.getLevel().getUniqueName());
            } else {
              pInfo.setRawValue("");
              pInfo.setFormattedValue(""); // First try to get a formatted value
              pInfo.setParentDimension(member.getDimension().getName());
              pInfo.setHierarchy(member.getHierarchy().getUniqueName());
              pInfo.setLevel(member.getLevel().getUniqueName());
              pInfo.setUniquename("");
            }
            matrix.set(x_parent, y_parent, pInfo);
            if (isColumns) {
              y_parent--;
            } else {
              x_parent--;
            }

          }
        }
        prevMembers[y] = member;
        prevMemberInfo[y] = memberInfo;
        members[y] = null;
      }
    }
  }
}
