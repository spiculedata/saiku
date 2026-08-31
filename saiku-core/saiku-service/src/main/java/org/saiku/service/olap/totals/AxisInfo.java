/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.totals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import org.olap4j.CellSetAxis;
import org.olap4j.Position;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Member;

public class AxisInfo {
    public List<Integer>[] levels;
    public final List<String> uniqueLevelNames = new ArrayList<>();
    public int maxDepth;
    public int measuresMember;
    public List<Position> fullPositions;
    public final CellSetAxis axis;

    public AxisInfo(CellSetAxis axis) {
        this.axis = axis;
        calcAxisInfo(axis);
    }

    private void calcAxisInfo(CellSetAxis axis) {
        calcAxisInfo(this, axis);
    }

    private static void calcAxisInfo(AxisInfo axisInfo, CellSetAxis axis) {
        final List<Hierarchy> hierarchies = resolveHierarchies(axis);
        final int hCount = hierarchies.size();
        final List<Integer> levels[] = new List[hCount];
        final HashSet<Integer>[][] usedLevels = new HashSet[hCount][];
        final int[] maxDepth = new int[hCount];

        for (int i = 0; i < hCount; i++) {
            maxDepth[i] = -1;
            levels[i] = new ArrayList<>();

            usedLevels[i] = new HashSet[levelCount(hierarchies.get(i), axis, i)];

            for (int j = 0; j < usedLevels[i].length; j++) {
                usedLevels[i][j] = new HashSet<>();
            }
        }
        axisInfo.measuresMember = Integer.MIN_VALUE;

        for (final Position p : axis.getPositions()) {
            int mI = 0;
            for (final Member m : p.getMembers()) {
                if ("Measures".equals(m.getDimension().getName())) {
                    axisInfo.measuresMember = mI;
                }
                usedLevels[mI][m.getLevel().getDepth()].add(m.getDepth());
                mI++;
            }
        }

        for (int i = 0; i < usedLevels.length; i++) {
            for (int j = 0; j < usedLevels[i].length; j++) {
                if (usedLevels[i][j].size() > 0) {
                    HashSet<Integer> obj = usedLevels[i][j];
                    Iterator<Integer> it = obj.iterator();
                    while (it.hasNext()) {
                        levels[i].add(it.next());
                    }

                    if (hierarchies.get(i) != null) {
                        axisInfo.uniqueLevelNames.add(
                                hierarchies.get(i).getLevels().get(j).getUniqueName());
                    }
                }
            }
        }

        int maxAxisDepth = 0;
        for (int i = 0; i < hCount; i++) {
            maxAxisDepth += levels[i].size();
        }
        axisInfo.levels = levels;
        axisInfo.maxDepth = maxAxisDepth;
        findFullPositions(axisInfo, axis);
    }

    /**
     * Returns the axis hierarchies, filling any null entry from the members present on that axis.
     *
     * <p>{@link org.olap4j.CellSetAxisMetaData#getHierarchies()} can report a null entry: it happens
     * with a Mondrian 4 schema converted from a Mondrian 3 one, where a hierarchy of the axis has no
     * metadata counterpart. The list is positional, so the slot cannot be dropped; it is resolved
     * from the members instead, and left null only when no member can fill it.
     *
     * @param axis the axis being measured
     * @return a positional list of hierarchies, null only where nothing could resolve the slot
     */
    private static List<Hierarchy> resolveHierarchies(CellSetAxis axis) {
        final List<Hierarchy> resolved = new ArrayList<>(axis.getAxisMetaData().getHierarchies());
        if (!resolved.contains(null)) {
            return resolved;
        }
        for (final Position p : axis.getPositions()) {
            int i = 0;
            for (final Member m : p.getMembers()) {
                if (i < resolved.size() && resolved.get(i) == null && m.getLevel() != null) {
                    resolved.set(i, m.getLevel().getHierarchy());
                }
                i++;
            }
            if (!resolved.contains(null)) {
                break;
            }
        }
        return resolved;
    }

    /**
     * Number of level slots to reserve for one hierarchy of the axis.
     *
     * @param hierarchy the hierarchy, possibly null when it could not be resolved
     * @param axis the axis being measured
     * @param index position of the hierarchy on the axis
     * @return a size large enough for every level depth used at that position
     */
    private static int levelCount(Hierarchy hierarchy, CellSetAxis axis, int index) {
        if (hierarchy != null) {
            return hierarchy.getLevels().size();
        }
        int deepest = -1;
        for (final Position p : axis.getPositions()) {
            final List<Member> members = p.getMembers();
            if ((index < members.size()) && (members.get(index).getLevel() != null)) {
                deepest = Math.max(deepest, members.get(index).getLevel().getDepth());
            }
        }
        return deepest + 1;
    }

    private static void findFullPositions(AxisInfo axisInfo, CellSetAxis axis) {
        axisInfo.fullPositions = new ArrayList<>(axis.getPositionCount());
        List<Integer>[] levels = axisInfo.levels;
        nextpos:
        for (final Position p : axis.getPositions()) {
            int mI = 0;
            for (final Member m : p.getMembers()) {
                final int maxDepth = levels[mI].get(levels[mI].size() - 1);
                if (m.getDepth() < maxDepth) {
                    continue nextpos;
                }
                mI++;
            }
            axisInfo.fullPositions.add(p);
        }
    }
}
