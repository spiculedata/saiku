package org.saiku.service.olap.totals.aggregators;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import mondrian.util.Format;

import org.olap4j.Cell;
import org.olap4j.OlapException;
import org.olap4j.metadata.Measure;
import org.saiku.olap.util.SaikuProperties;

public abstract class TotalAggregator {
	private static final Map<String, TotalAggregator> all;
	static {
		Map<String, TotalAggregator> tmp = new HashMap<String, TotalAggregator>();
		tmp.put("sum", new SumAggregator(null));
		tmp.put("max", new MaxAggregator(null));
		tmp.put("min", new MinAggregator(null));
		tmp.put("avg", new AvgAggregator(null));
		all = Collections.unmodifiableMap(tmp);
	}
	private String formattedValue;
	protected final Format format;
	
	protected TotalAggregator(Format format) {
		this.format = format;
	}
	
	public void addData(Cell cell) {
		Object value = cell.getValue();
		if (value instanceof Number) {
			double doubleVal;
			try {
				doubleVal = cell.getDoubleValue();
				addData(doubleVal);
			} catch (OlapException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	protected abstract void addData(double data);
	public abstract Double getValue();
	public abstract TotalAggregator newInstance(Format format, Measure measure);
	
	public String getFormattedValue() {
		if (formattedValue != null)
			return formattedValue;
		else {
			Double value = getValue();
			if (value != null)
				return format.format(value);
			return "";
		}
	}
	public void setFormattedValue(String formattedValue) {
		this.formattedValue = formattedValue;
	}
	
	public TotalAggregator newInstance() {
		return newInstance("");
	}
	
	public TotalAggregator newInstance(String formatString) {
		return newInstance(new Format(formatString, SaikuProperties.locale), null);
	}
	
	public static TotalAggregator newInstanceByFunctionName(final String functionName) {
		return all.get(functionName);
	}
}