package org.saiku.service.olap.totals;

import org.olap4j.Cell;
import org.olap4j.metadata.Measure;
import org.saiku.service.olap.totals.aggregators.TotalAggregator;


public class TotalNode {
	final String captions[];
	final TotalAggregator totals[][];
	final boolean showsTotals;
	final int cellsAdded;
	int span;
	int width;

	public TotalNode(String[] captions, Measure[] measures, TotalAggregator aggregatorTemplate, FormatList formatList, int count) {
		this.captions = captions;
		showsTotals = aggregatorTemplate != null;

		if (showsTotals) {
			cellsAdded = captions != null ? captions.length : 1;
			totals = new TotalAggregator[cellsAdded][count];

			if (aggregatorTemplate != null) {
				for (int i = 0; i < totals.length; i++) {
					for (int j = 0; j < totals[0].length; j++)
						totals[i][j] = aggregatorTemplate.newInstance(formatList.getValueFormat(j, i), measures[i]);
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
	
	public void appendSpan(int append) {
		this.span += append;
	}
	
	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}
	
	public void appendWidth(int append) {
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
	
	public int getRenderedCount() {
		return span + cellsAdded;
	}
}