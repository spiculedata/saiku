package org.saiku.service.olap.totals.aggregators;

import mondrian.util.Format;
import org.olap4j.metadata.Measure;

/**
 * Created by brunogamacatao on 22/06/16.
 */
public class BlankAggregator extends TotalAggregator {
    private static final String BLANK_VALUE = "-";

    BlankAggregator(Format format) {
        super(format);
    }

    @Override
    public void addData(double data) {}

    @Override
    public Double getValue() {
        return 0.0;
    }

    @Override
    public TotalAggregator newInstance() {
        return new BlankAggregator(format);
    }

    public TotalAggregator newInstance(Format format, Measure measure) {
        return new BlankAggregator(format);
    }

    @Override
    public String getFormattedValue() {
        return BLANK_VALUE;
    }
}
