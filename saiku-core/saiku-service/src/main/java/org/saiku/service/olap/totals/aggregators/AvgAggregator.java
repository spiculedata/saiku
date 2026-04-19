package org.saiku.service.olap.totals.aggregators;

import java.util.Set;
import mondrian.util.Format;
import org.olap4j.Cell;
import org.olap4j.OlapException;
import org.olap4j.metadata.Datatype;
import org.olap4j.metadata.Measure;
import org.olap4j.metadata.Property;

public class AvgAggregator extends TotalAggregator {
    private static final Property DRILLTHROUGH_COUNT = new Property() {

        public String getCaption() {
            return null;
        }

        public String getDescription() {
            return null;
        }

        public String getName() {
            return "DRILLTHROUGH_COUNT";
        }

        public String getUniqueName() {
            return null;
        }

        public boolean isVisible() {
            return false;
        }

        public ContentType getContentType() {
            return null;
        }

        public Datatype getDatatype() {
            return null;
        }

        public Set<TypeFlag> getType() {
            return null;
        }
    };

    AvgAggregator(Format format) {
        super(format);
    }

    private double accumulator = 0.0;
    private long count = 0;

    public void addData(Cell cell) {
        Object value = cell.getValue();
        if (value instanceof Number) {
            Integer count = (Integer) cell.getPropertyValue(DRILLTHROUGH_COUNT);
            double doubleVal;
            try {
                doubleVal = cell.getDoubleValue();
            } catch (OlapException e) {
                throw new RuntimeException(e);
            }
            if (count.longValue() > -1) {
                this.count += count.longValue();
                accumulator += doubleVal * count.doubleValue();
            } else {
                this.count++;
                accumulator += doubleVal;
            }
        }
    }

    @Override
    public void addData(double data) {}

    @Override
    public Double getValue() {
        if (count > 0) {
            return accumulator / count;
        }
        return null;
    }

    @Override
    public TotalAggregator newInstance(Format format, Measure measure) {
        return new AvgAggregator(format);
    }
}
