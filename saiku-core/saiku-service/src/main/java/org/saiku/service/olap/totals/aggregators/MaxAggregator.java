package org.saiku.service.olap.totals.aggregators;

import org.olap4j.metadata.Measure;

import mondrian.util.Format;

public class MaxAggregator extends TotalAggregator {
	
	protected MaxAggregator(Format format) {
		super(format);
	}

	Double max = null;

	@Override
	public void addData(double data) {
		if (max == null)
			max = data;
		else if (max < data)
			max = data;
	}

	@Override
	public Double getValue() {
		return max;
	}

	@Override
	public TotalAggregator newInstance(Format format, Measure measure) {
		return new MaxAggregator(format);
	}
	
}