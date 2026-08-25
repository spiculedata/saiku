/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.totals.aggregators;

import mondrian.util.Format;
import org.olap4j.metadata.Measure;

public class MinAggregator extends TotalAggregator {

    MinAggregator(Format format) {
        super(format);
    }

    private Double min = null;

    @Override
    public void addData(double data) {
        if (min == null) {
            min = data;
        } else if (min > data) {
            min = data;
        }
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
