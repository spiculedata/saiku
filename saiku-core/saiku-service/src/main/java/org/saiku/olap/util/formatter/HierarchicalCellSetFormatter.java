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

import java.util.*;
import org.olap4j.CellSetAxis;
import org.olap4j.Position;
import org.olap4j.impl.Olap4jUtil;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Member;
import org.saiku.olap.dto.resultset.Matrix;
import org.saiku.olap.dto.resultset.MemberCell;

/**
 * Hierarchical cell-set formatter. Shares all the scaffolding —
 * {@code format}, {@code computeAxisInfo}, the two-axis {@code formatPage} and
 * the per-data-cell {@code populateCell} block — with
 * {@link AbstractCellSetFormatter}; only {@link #populateAxis} is specific to
 * this formatter (preserved verbatim through issue #1163, including the
 * saiku#845 {@code Arrays.fill(members, null)} clear and the {@code same}-nulling
 * of repeated row members).
 */
public class HierarchicalCellSetFormatter extends AbstractCellSetFormatter {

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
    @Override
    protected void populateAxis(
            final Matrix matrix,
            final CellSetAxis axis,
            final AxisInfo axisInfo,
            final boolean isColumns,
            final int offset) {
        if (axis == null) return;
        final Member[] prevMembers = new Member[axisInfo.getWidth()];
        final MemberCell[] prevMemberInfo = new MemberCell[axisInfo.getWidth()];
        final Member[] members = new Member[axisInfo.getWidth()];

        for (int i = 0; i < axis.getPositions().size(); i++) {
            final int x = offset + i;
            final Position position = axis.getPositions().get(i);
            int yOffset = 0;
            final List<Member> memberList = position.getMembers();
            final Map<Hierarchy, List<Integer>> lvls = new HashMap<>();
            // saiku#845: mirrors the saiku#788 fix on the flattened sibling.
            // members[] is declared outside the outer position loop and reused
            // across iterations; a shallow position that only fills slot 0
            // would otherwise inherit slots 1..N from the previous (deeper)
            // position's members — silently collapsing mixed-depth row sets
            // (parent + descendants on the same axis) into a duplicated
            // deepest row. Clear before each position so shallow slots render
            // as empty rather than as the wrong member's caption.
            java.util.Arrays.fill(members, null);
            for (int j = 0; j < memberList.size(); j++) {
                Member member = memberList.get(j);
                final AxisOrdinalInfo ordinalInfo = axisInfo.ordinalInfos.get(j);
                List<Integer> depths = ordinalInfo.getDepths();
                Collections.sort(depths);
                lvls.put(member.getHierarchy(), depths);
                if (ordinalInfo.getDepths().size() > 0
                        && member.getDepth() < ordinalInfo.getDepths().get(0)) break;
                final int y = yOffset + ordinalInfo.getDepths().indexOf(member.getDepth());
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
                        memberInfo.setProperty(
                                "levelindex",
                                ""
                                        + lvls.get(member.getHierarchy())
                                                .indexOf(member.getLevel().getDepth()));
                    }
                    if (x - 1 == offset) memberInfo.setLastRow(true);

                    matrix.setOffset(offset);
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
                    if (same) {
                        memberInfo.setFormattedValue(null);
                        memberInfo.setRawValue(null);
                        memberInfo.setParentDimension(null);
                    }
                    memberInfo.setRight(false);
                    memberInfo.setSameAsPrev(false);

                    matrix.set(y, x, memberInfo);
                }
                prevMembers[y] = member;
                prevMemberInfo[y] = memberInfo;
                members[y] = null;
            }
        }
    }
}
