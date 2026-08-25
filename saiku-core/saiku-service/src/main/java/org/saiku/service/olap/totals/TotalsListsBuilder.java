/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.totals;

import java.util.*;
import mondrian.util.Format;
import org.apache.commons.lang3.StringUtils;
import org.olap4j.*;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Measure;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.Property;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.util.Fat;
import org.saiku.olap.util.SaikuProperties;
import org.saiku.service.olap.totals.aggregators.TotalAggregator;

public class TotalsListsBuilder implements FormatList {
    private final Member[] memberBranch;
    private final TotalNode totalBranch[];
    private final TotalAggregator[] aggrTempl;
    private final int col, row;
    private final List<TotalNode> totalsLists[];
    private final int measuresAt;
    private final String[] measuresCaptions;
    private final Measure[] measures;
    private final Map<String, Integer> uniqueToSelected;
    private final AxisInfo dataAxisInfo;
    private final AxisInfo totalsAxisInfo;
    private final CellSet cellSet;
    private final Format[] valueFormats;
    private final ThinQuery thinQuery;

    public TotalsListsBuilder(
            Measure[] selectedMeasures,
            TotalAggregator[] aggrTempl,
            CellSet cellSet,
            AxisInfo totalsAxisInfo,
            AxisInfo dataAxisInfo)
            throws Exception {
        this(selectedMeasures, aggrTempl, cellSet, totalsAxisInfo, dataAxisInfo, null);
    }

    public TotalsListsBuilder(
            Measure[] selectedMeasures,
            TotalAggregator[] aggrTempl,
            CellSet cellSet,
            AxisInfo totalsAxisInfo,
            AxisInfo dataAxisInfo,
            ThinQuery thinQuery)
            throws Exception {
        this.thinQuery = thinQuery;

        Cube cube;
        try {
            cube = cellSet.getMetaData().getCube();
        } catch (OlapException e) {
            throw new RuntimeException(e);
        }

        uniqueToSelected = new HashMap<>();
        if (selectedMeasures.length > 0) {
            valueFormats = new Format[selectedMeasures.length];
            measures = selectedMeasures;
            for (int i = 0; i < valueFormats.length; i++) {
                valueFormats[i] = getMeasureFormat(selectedMeasures[i]);
                uniqueToSelected.put(selectedMeasures[i].getUniqueName(), i);
            }
        } else {
            Measure defaultMeasure = cube.getMeasures().get(0);
            if (cube.getDimensions().get("Measures") != null) {
                Member ms = cube.getDimensions()
                        .get("Measures")
                        .getDefaultHierarchy()
                        .getDefaultMember();
                if (ms instanceof Measure) {
                    defaultMeasure = (Measure) ms;
                }
            }
            measures = new Measure[] {defaultMeasure};
            valueFormats = new Format[] {getMeasureFormat(defaultMeasure)};
        }
        this.cellSet = cellSet;
        this.dataAxisInfo = dataAxisInfo;
        this.totalsAxisInfo = totalsAxisInfo;
        final int maxDepth = dataAxisInfo.maxDepth + 1;
        boolean hasMeasuresOnDataAxis = false;
        int measuresAt = 0;
        int measuresMember = 0;
        final List<Member> members = dataAxisInfo.axis.getPositionCount() > 0
                ? dataAxisInfo.axis.getPositions().get(0).getMembers()
                : Collections.<Member>emptyList();
        for (; measuresMember < members.size(); measuresMember++) {
            Member m = members.get(measuresMember);
            if ("Measures".equals(m.getDimension().getName())) {
                hasMeasuresOnDataAxis = true;
                break;
            }
            measuresAt += dataAxisInfo.levels[measuresMember].size();
        }
        if (hasMeasuresOnDataAxis) {
            this.measuresAt = measuresAt;
            measuresCaptions = new String[selectedMeasures.length];
            for (int i = 0; i < measuresCaptions.length; i++) {
                measuresCaptions[i] = selectedMeasures[i].getCaption();
            }
        } else {
            this.measuresAt = Integer.MIN_VALUE;
            measuresCaptions = null;
        }

        totalBranch = new TotalNode[maxDepth];

        if (aggrTempl[0] == null) {
            // use BlankAggregator if one off measure has aggregator but total aggregator not selected
            for (Measure m : measures) {
                if (m instanceof Fat.MeasureAdapter) {
                    ThinMeasure tm = ((Fat.MeasureAdapter) m).getThinMeasure();
                    if (tm != null
                            && tm.getAggregators() != null
                            && !tm.getAggregators().isEmpty()) {
                        aggrTempl[0] = TotalAggregator.newInstanceByFunctionName("nil");
                        break;
                    }
                }
            }
        }

        TotalNode rootNode = new TotalNode(
                measuresCaptions, measures, aggrTempl[0], this, totalsAxisInfo.fullPositions.size(), dataAxisInfo);
        col = Axis.ROWS.equals(dataAxisInfo.axis.getAxisOrdinal()) ? 1 : 0;
        row = (col + 1) & 1;
        this.aggrTempl = aggrTempl;

        totalBranch[0] = rootNode;
        totalsLists = new List[maxDepth];
        for (int i = 0; i < totalsLists.length; i++) {
            totalsLists[i] = new ArrayList<>();
        }
        totalsLists[0].add(rootNode);
        memberBranch = new Member[dataAxisInfo.maxDepth + 1];
    }

    private Format getMeasureFormat(Measure m) {
        try {
            String formatString = (String) m.getPropertyValue(Property.StandardCellProperty.FORMAT_STRING);
            if (StringUtils.isBlank(formatString)) {
                if (m.getProperties() != null) {
                    Map<String, Property> props = m.getProperties().asMap();
                    if (props.containsKey("FORMAT_STRING")) {
                        formatString = (String) m.getPropertyValue(props.get("FORMAT_STRING"));
                    } else if (props.containsKey("FORMAT_EXP_PARSED")) {
                        formatString = (String) m.getPropertyValue(props.get("FORMAT_EXP_PARSED"));
                    } else if (props.containsKey("FORMAT_EXP")) {
                        formatString = (String) m.getPropertyValue(props.get("FORMAT_EXP"));
                    } else if (props.containsKey("FORMAT")) {
                        formatString = (String) m.getPropertyValue(props.get("FORMAT"));
                    }
                }
                if (StringUtils.isBlank(formatString)) {
                    formatString = "Standard";
                }
                if (StringUtils.isNotBlank(formatString)
                        && formatString.length() > 1
                        && formatString.startsWith("\"")
                        && formatString.endsWith("\"")) {
                    formatString = formatString.substring(1, formatString.length() - 1);
                }
            }
            return Format.get(formatString, SaikuProperties.locale);
        } catch (OlapException e) {
            throw new RuntimeException(e);
        }
    }

    private void positionMember(final int depth, Member m, final List<Integer> levels, final Member[] branch) {
        for (int i = levels.size() - 1; i >= 0; ) {
            branch[depth + i] = m;
            i--;
            do {
                m = m.getParentMember();
            } while (i >= 0 && m != null && m.getDepth() != levels.get(i));
        }
    }

    private void traverse(List<Integer>[] levels, List<TotalNode>[] totalLists) {
        int fullPosition = 0;
        final Member[] prevMemberBranch = new Member[memberBranch.length];

        nextpos:
        for (final Position p : dataAxisInfo.axis.getPositions()) {
            int depth = 1;
            int mI = 0;

            for (final Member m : p.getMembers()) {
                final int maxDepth = levels[mI].get(levels[mI].size() - 1);
                if (m.getDepth() < maxDepth) {
                    continue nextpos;
                }
                positionMember(depth, m, levels[mI], memberBranch);
                depth += levels[mI].size();
                mI++;
            }

            int changedFrom = 1;
            while (changedFrom < memberBranch.length - 1
                    && memberBranch[changedFrom].equals(prevMemberBranch[changedFrom])) {
                changedFrom++;
            }

            for (int i = totalBranch.length - 1; i >= changedFrom; i--) {
                if (totalBranch[i] != null) {
                    totalBranch[i - 1].appendChild(totalBranch[i]);
                }
            }

            for (int i = changedFrom; i < totalBranch.length; i++) {
                String[] captions = measuresAt > i - 1 ? measuresCaptions : null;

                String uniqueLevelName = dataAxisInfo.uniqueLevelNames.get(i - 1);
                ThinLevel level = thinQuery.getLevel(uniqueLevelName);

                if (level != null
                        && level.getAggregators() != null
                        && !level.getAggregators().isEmpty()) {
                    List<String> lvlAggr = level.getAggregators();
                    Measure[] newMeasures = new Measure[measures.length];

                    for (int j = 0; j < newMeasures.length; j++) {
                        if (j < lvlAggr.size()) {
                            ThinMeasure mockMeasure = new ThinMeasure(
                                    measures[j].getName(),
                                    measures[j].getUniqueName(),
                                    measures[j].getCaption(),
                                    ThinMeasure.Type.EXACT);
                            mockMeasure.getAggregators().add(lvlAggr.get(j));
                            newMeasures[j] = new Fat.MeasureAdapter(measures[j], mockMeasure);
                        } else {
                            newMeasures[j] = measures[j];
                        }
                    }

                    totalBranch[i] = new TotalNode(
                            captions,
                            newMeasures,
                            aggrTempl[i],
                            this,
                            totalsAxisInfo.fullPositions.size(),
                            dataAxisInfo);
                } else {
                    totalBranch[i] = new TotalNode(
                            captions, measures, aggrTempl[i], this, totalsAxisInfo.fullPositions.size(), dataAxisInfo);
                }

                totalLists[i].add(totalBranch[i]);
            }

            System.arraycopy(memberBranch, 0, prevMemberBranch, 0, prevMemberBranch.length);

            totalBranch[totalBranch.length - 1].setSpan(1);
            totalBranch[totalBranch.length - 1].setWidth(1);

            for (int t = 0; t < totalsAxisInfo.fullPositions.size(); t++) {
                Cell cell = getCellAt(fullPosition, t);
                for (int branchNode = 0; branchNode < totalBranch.length; branchNode++) {
                    if (aggrTempl[branchNode] != null) {
                        totalBranch[branchNode].addData(getMemberIndex(branchNode, fullPosition), t, cell);
                    }
                }
            }
            fullPosition++;
        }
        for (int i = totalBranch.length - 1; i > 0; i--) {
            totalBranch[i - 1].appendChild(totalBranch[i]);
        }

        for (TotalNode n : totalLists[totalLists.length - 1]) {
            n.setWidth(0);
        }
    }

    public List<TotalNode>[] buildTotalsLists() {
        traverse(dataAxisInfo.levels, totalsLists);
        return totalsLists;
    }

    private Cell getCellAt(int axisCoord, int perpAxisCoord) {
        final Position[] positions = new Position[] {
            dataAxisInfo.fullPositions.get(axisCoord), totalsAxisInfo.fullPositions.get(perpAxisCoord)
        };
        return cellSet.getCell(positions[col], positions[row]);
    }

    private int getMemberIndex(int depth, int index) {
        if (depth - 1 < measuresAt) {
            Member m = dataAxisInfo.fullPositions.get(index).getMembers().get(dataAxisInfo.measuresMember);
            if (uniqueToSelected.containsKey(m.getUniqueName())) {
                return uniqueToSelected.get(m.getUniqueName());
            }
        }
        return 0;
    }

    public Format getValueFormat(int position, int member) {
        int formatIndex = 0;
        if (dataAxisInfo.measuresMember >= 0) {
            formatIndex = member;
        } else if (totalsAxisInfo.measuresMember >= 0) {
            Member m = totalsAxisInfo.fullPositions.get(position).getMembers().get(totalsAxisInfo.measuresMember);
            if (uniqueToSelected.containsKey(m.getUniqueName())) {
                formatIndex = uniqueToSelected.get(m.getUniqueName());
            }
        }
        return valueFormats[formatIndex];
    }
}
