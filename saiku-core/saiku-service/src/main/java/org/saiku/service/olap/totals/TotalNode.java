/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.totals;

import org.olap4j.Cell;
import org.olap4j.metadata.Measure;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.util.Fat;
import org.saiku.service.olap.totals.aggregators.TotalAggregator;

public class TotalNode {
    private final String[] captions;
    private final TotalAggregator[][] totals;
    private final boolean showsTotals;
    private final int cellsAdded;
    private int span;
    private int width;
    private AxisInfo dataAxisInfo;

    public TotalNode(
            String[] captions,
            Measure[] measures,
            TotalAggregator aggregatorTemplate,
            FormatList formatList,
            int count,
            AxisInfo dataAxisInfo) {

        this.captions = captions;
        this.dataAxisInfo = dataAxisInfo;

        showsTotals = aggregatorTemplate != null;

        if (showsTotals) {
            cellsAdded = captions != null ? captions.length : 1;
            totals = new TotalAggregator[cellsAdded][count];
            String axisName = dataAxisInfo.axis.getAxisOrdinal().name();

            if (aggregatorTemplate != null) {
                for (int i = 0; i < totals.length; i++) {
                    for (int j = 0; j < totals[0].length; j++) {
                        int k = j % measures.length;

                        if (axisName.equals("COLUMNS")) {
                            k = i % measures.length;
                        }

                        if (measures[k] instanceof Fat.MeasureAdapter) {
                            ThinMeasure tm = ((Fat.MeasureAdapter) measures[k]).getThinMeasure();
                            if (tm != null
                                    && tm.getAggregators() != null
                                    && !tm.getAggregators().isEmpty()) {
                                boolean foundAggregator = false;

                                for (String agg : tm.getAggregators()) {
                                    if (agg.indexOf("_") < 0) {
                                        totals[i][j] = TotalAggregator.newInstanceByFunctionName(agg)
                                                .newInstance(formatList.getValueFormat(j, i), measures[k]);
                                        foundAggregator = true;
                                        break;
                                    } else {
                                        String[] tokens = agg.split("_");

                                        if (tokens[1].equals(axisName)) {
                                            agg = tokens[0];
                                            totals[i][j] = TotalAggregator.newInstanceByFunctionName(agg)
                                                    .newInstance(formatList.getValueFormat(j, i), measures[k]);
                                            foundAggregator = true;
                                            break;
                                        }
                                    }
                                }

                                if (foundAggregator) {
                                    continue;
                                }
                            }
                        }

                        totals[i][j] = aggregatorTemplate.newInstance(formatList.getValueFormat(j, i), measures[i]);
                    }
                }
            }
        } else {
            totals = new TotalAggregator[0][count];
            cellsAdded = 0;
        }
    }

    public void addData(int member, int index, Cell cell) {
        totals[member][index].addData(cell);
    }

    public void setFormattedValue(int member, int index, String value) {
        totals[member][index].setFormattedValue(value);
    }

    public int getSpan() {
        return span;
    }

    public void setSpan(int span) {
        this.span = span;
    }

    private void appendSpan(int append) {
        this.span += append;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    private void appendWidth(int append) {
        this.width += append;
    }

    public void appendChild(TotalNode child) {
        appendSpan(child.getRenderedCount());
        appendWidth(child.width);
    }

    public String[] getMemberCaptions() {
        return captions;
    }

    public TotalAggregator[][] getTotalGroups() {
        return totals;
    }

    private int getRenderedCount() {
        return span + cellsAdded;
    }
}
