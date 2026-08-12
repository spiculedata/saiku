package org.saiku.service.olap.totals.aggregators;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import mondrian.util.Format;
import org.olap4j.Cell;
import org.olap4j.metadata.Measure;
import org.saiku.olap.util.SaikuProperties;

public abstract class TotalAggregator {
    private static final Map<String, TotalAggregatorFactory> all;

    private static interface TotalAggregatorFactory {
        TotalAggregator create();
    }

    static {
        Map<String, TotalAggregatorFactory> tmp = new HashMap<>();

        tmp.put("not", new TotalAggregatorFactory() {
            public TotalAggregator create() {
                return null;
            }
        });

        tmp.put("nil", new TotalAggregatorFactory() {
            public TotalAggregator create() {
                return new BlankAggregator(null);
            }
        });

        tmp.put("sum", new TotalAggregatorFactory() {
            public TotalAggregator create() {
                return new SumAggregator(null);
            }
        });

        tmp.put("max", new TotalAggregatorFactory() {
            public TotalAggregator create() {
                return new MaxAggregator(null);
            }
        });

        tmp.put("min", new TotalAggregatorFactory() {
            public TotalAggregator create() {
                return new MinAggregator(null);
            }
        });

        tmp.put("avg", new TotalAggregatorFactory() {
            public TotalAggregator create() {
                return new AvgAggregator(null);
            }
        });

        all = Collections.unmodifiableMap(tmp);
    }

    private String formattedValue;
    final Format format;

    TotalAggregator(Format format) {
        this.format = format;
    }

    public void addData(Cell cell) {
        try {
            // saiku#1715: totals never adopt the cell FORMAT_STRING - the fetch below
            // was abandoned over an infinite-loop suspicion, so totals format with the
            // constructor format only. Experiment preserved for the ticket:
            //		if (format == null) {
            //			String formatString = (String) cell.getPropertyValue(Property.StandardCellProperty.FORMAT_STRING);
            //			this.format = Format.get(formatString, SaikuProperties.locale);
            //
            //		}
            Object value = cell.getValue();
            if (value instanceof Number) {
                double doubleVal;

                doubleVal = cell.getDoubleValue();
                addData(doubleVal);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract void addData(double data);

    protected abstract Double getValue();

    public abstract TotalAggregator newInstance(Format format, Measure measure);

    public String getFormattedValue() {
        if (formattedValue != null) {
            return formattedValue;
        } else {
            Double value = getValue();
            if (value != null) {
                return format.format(value);
            }
            return "";
        }
    }

    public void setFormattedValue(String formattedValue) {
        this.formattedValue = formattedValue;
    }

    public TotalAggregator newInstance() {
        return newInstance("");
    }

    private TotalAggregator newInstance(String formatString) {
        return newInstance(new Format(formatString, SaikuProperties.locale), null);
    }

    public static TotalAggregator newInstanceByFunctionName(final String functionName) {
        if (functionName == null || functionName.trim().length() == 0) {
            return all.get("not").create();
        }

        return all.get(functionName).create();
    }
}
