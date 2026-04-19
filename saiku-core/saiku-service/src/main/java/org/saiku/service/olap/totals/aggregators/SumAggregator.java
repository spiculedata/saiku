package org.saiku.service.olap.totals.aggregators;

import mondrian.util.Format;
import org.olap4j.metadata.Measure;

public class SumAggregator extends TotalAggregator {

    SumAggregator(Format format) {
        super(format);
    }

    private double sum = 0.0;

    @Override
    public void addData(double data) {
        sum += data;
    }

    @Override
    public Double getValue() {
        return sum;
    }

    @Override
    public TotalAggregator newInstance() {
        return new SumAggregator(format);
    }

    public TotalAggregator newInstance(Format format, Measure measure) {
        return new SumAggregator(format);
    }
}
