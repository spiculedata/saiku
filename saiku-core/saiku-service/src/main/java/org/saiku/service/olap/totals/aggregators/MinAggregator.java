package org.saiku.service.olap.totals.aggregators;

import org.olap4j.metadata.Measure;

import mondrian.util.Format;


public class MinAggregator extends TotalAggregator {
	
	protected MinAggregator(Format format) {
		super(format);
	}

	Double min = null;

	@Override
	public void addData(double data) {
		if (min == null)
			min = data;
		else if (min > data)
			min = data;
	}

	@Override
	public Double getValue() {
		return min;
	}

	@Override
	public TotalAggregator newInstance(Format format, Measure measure) {
		return new MinAggregator(format);
	}
	
}