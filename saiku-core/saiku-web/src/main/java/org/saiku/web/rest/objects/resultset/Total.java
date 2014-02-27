package org.saiku.web.rest.objects.resultset;

import org.saiku.service.olap.totals.TotalNode;
import org.saiku.service.olap.totals.aggregators.TotalAggregator;

public class Total {
	private final Cell[][] cells;
	private final String[] captions;
	private final int span;
	private final int width;
	
	public Total(TotalNode node) {
		this(node.getTotalGroups(), node.getMemberCaptions(), node.getSpan(), node.getWidth());
	}
	
	public Total(TotalAggregator[][] values, String[] captions, int span, int width) {
		if (values.length > 0)
			this.cells = new Cell[values.length][values[0].length];
		else
			this.cells = new Cell[0][];
		
		this.captions = captions;
		
		for (int i = 0; i < values.length; i++) {
			for (int j = 0; j < values[0].length; j++) {
				this.cells[i][j] = new Cell(values[i][j].getFormattedValue(), Cell.Type.DATA_CELL);
			}
		}
		this.span = span;
		this.width = width;
	}

	public Cell[][] getCells() {
		return cells;
	}
	
	public String[] getCaptions() {
		return captions;
	}

	public int getSpan() {
		return span;
	}
	
	public int getWidth() {
		return width;
	}
}
