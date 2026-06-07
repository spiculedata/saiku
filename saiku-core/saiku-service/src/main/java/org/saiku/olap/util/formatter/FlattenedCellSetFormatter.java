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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.olap4j.Cell;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.Position;
import org.olap4j.impl.Olap4jUtil;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.saiku.olap.dto.resultset.Matrix;
import org.saiku.olap.dto.resultset.MemberCell;

/**
 * Flattened cell-set formatter. Shares the scaffolding —
 * {@code format}, {@code computeAxisInfo} and the per-data-cell
 * {@code populateCell} block — with {@link AbstractCellSetFormatter}, but
 * overrides {@link #formatPage} wholesale to apply its ignorex/ignorey row and
 * column collapsing (delegating each surviving data cell back to
 * {@link #populateCell}), and overrides {@link #populateAxis} (preserved
 * verbatim through issue #1163, including the saiku#788
 * {@code Arrays.fill(members, null)} clear and the STICKY {@code expanded} flag
 * declared outside the inner loop and never reset).
 */
public class FlattenedCellSetFormatter extends AbstractCellSetFormatter {

    private final List<Integer> ignorex = new ArrayList<>();
    private final List<Integer> ignorey = new ArrayList<>();

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
    @Override
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
            // We assume that every position contains members with same levels,
            // so, we just need the first position to retrieve this information.
            Position p = rowsAxis.getPositions().get(0);

            for (int m = 0; m < p.getMembers().size(); m++) {
                AxisOrdinalInfo a = rowsAxisInfo.ordinalInfos.get(m);

                // For each member's depth of the first position, add its level
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
        populateAxis(matrix, columnsAxis, columnsAxisInfo, true, xOffsset);
        populateAxis(matrix, rowsAxis, rowsAxisInfo, false, yOffset);

        // TODO - why did we do this in the first place??? HERE BE DRAGONS
        //		int headerwidth = matrix.getMatrixWidth();
        //		if (headerwidth > 2) {
        //			for(int yy=matrix.getMatrixHeight(); yy > matrix.getOffset() ; yy--) {
        //				for(int xx=0; xx < headerwidth-1;xx++) {
        //							if (matrix.get(xx,yy-1) != null && matrix.get(xx,yy) != null &&  matrix.get(xx,yy-1).getRawValue() !=
        // null
        //									&& matrix.get(xx,yy-1).getRawValue().equals(matrix.get(xx, yy).getRawValue()))
        //							{
        //								matrix.set(xx, yy, new MemberCell());
        //							}
        //							else {
        //								break;
        //							}
        //					}
        //			}
        //		}

        // Populate cell values
        int newyOffset = yOffset;
        int newxOffset = xOffsset;
        List<Integer> donex = new ArrayList<>();
        List<Integer> doney = new ArrayList<>();
        for (final Cell cell : cellIter(pageCoords, cellSet)) {
            final List<Integer> coordList = cell.getCoordinateList();
            int y = newyOffset;
            int x = newxOffset;
            if (coordList.size() > 0) {
                if (coordList.get(0) == 0) {
                    newxOffset = xOffsset;
                    donex = new ArrayList<>();
                }
                x = newxOffset;
                if (coordList.size() > 0) x += coordList.get(0);
                y = newyOffset;
                if (coordList.size() > 1) y += coordList.get(1);

                boolean stop = false;
                if (coordList.size() > 0 && ignorex.contains(coordList.get(0))) {
                    if (!donex.contains(coordList.get(0))) {
                        newxOffset--;
                        donex.add(coordList.get(0));
                    }
                    stop = true;
                }
                if (coordList.size() > 1 && ignorey.contains(coordList.get(1))) {
                    if (!doney.contains(coordList.get(1))) {
                        newyOffset--;
                        doney.add(coordList.get(1));
                    }
                    stop = true;
                }
                if (stop) {
                    continue;
                }
            }

            populateCell(matrix, cell, coordList, x, y);
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
     * @param oldoffset
     *            Ordinal of first cell to populate in matrix
     */
    @Override
    protected void populateAxis(
            final Matrix matrix,
            final CellSetAxis axis,
            final AxisInfo axisInfo,
            final boolean isColumns,
            final int oldoffset) {
        if (axis == null) {
            return;
        }

        int offset = oldoffset;

        final Member[] prevMembers = new Member[axisInfo.getWidth()];
        final MemberCell[] prevMemberInfo = new MemberCell[axisInfo.getWidth()];
        final Member[] members = new Member[axisInfo.getWidth()];

        // For each axis' position
        for (int i = 0; i < axis.getPositions().size(); i++) {
            final int x = offset + i;
            final Position position = axis.getPositions().get(i);
            int yOffset = 0;
            final List<Member> memberList = position.getMembers();

            // saiku#788: members[] is reused across iterations, so a shallow
            // position that only fills slot 0 would otherwise inherit slots
            // 1..N from the previous (deeper) position — producing the
            // silent-data-loss mixed-depth bug. Clear before each position so
            // each row's shallow slots render as empty rather than the wrong
            // member's caption.
            java.util.Arrays.fill(members, null);

            // For each position's member
            for (int j = 0; j < memberList.size(); j++) {
                Member member = memberList.get(j);
                final AxisOrdinalInfo ordinalInfo = axisInfo.ordinalInfos.get(j);
                List<Integer> depths = ordinalInfo.getDepths();
                Collections.sort(depths);

                // saiku#788: previously this branch unconditionally dropped any
                // position whose member sat above the max observed depth —
                // silently collapsing mixed-depth row sets (e.g. parent +
                // descendants on the same axis) to just the deepest member.
                // Now we keep the position: members[] is filled at the slot
                // matching the member's depth, and deeper slots stay null (so
                // populateAxis below renders them as empty MemberCells).

                if (ordinalInfo.getDepths().size() > 0
                        && member.getDepth() < ordinalInfo.getDepths().get(0)) {
                    break;
                }

                // It stores each position's member in members array sorted by its depth
                final int y = yOffset + ordinalInfo.getDepths().indexOf(member.getDepth());
                members[y] = member;
                yOffset += ordinalInfo.getWidth();
            }

            boolean expanded = false;
            boolean same = true;

            for (int y = 0; y < members.length; y++) {
                final MemberCell memberInfo = new MemberCell();
                final Member member = members[y];

                // The index of the member on its position
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
                    if (x - 1 == offset) memberInfo.setLastRow(true);

                    matrix.setOffset(oldoffset);
                    memberInfo.setRawValue(member.getUniqueName());
                    memberInfo.setFormattedValue(member.getCaption()); // First try to get a formatted value
                    memberInfo.setParentDimension(member.getDimension().getName());
                    memberInfo.setUniquename(member.getUniqueName());
                    memberInfo.setHierarchy(member.getHierarchy().getUniqueName());
                    memberInfo.setLevel(member.getLevel().getUniqueName());
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

                int x_parent = isColumns ? x : y - 1;
                int y_parent = isColumns ? y - 1 : x;

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
