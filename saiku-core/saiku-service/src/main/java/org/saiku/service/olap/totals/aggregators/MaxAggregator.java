/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.totals.aggregators;

import mondrian.util.Format;
import org.olap4j.metadata.Measure;

public class MaxAggregator extends TotalAggregator {

    MaxAggregator(Format format) {
        super(format);
    }

    private Double max = null;

    @Override
    public void addData(double data) {
        if (max == null) {
            max = data;
        } else if (max < data) {
            max = data;
        }
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
