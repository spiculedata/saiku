package org.saiku.service.olap.drillthrough;

public class DimensionResultInfo implements ResultInfo {
	private final String dimension;
	private final String hierarchy;
	private final String level;
	public DimensionResultInfo(String dimension, String hierarchy, String level) {
		this.dimension = dimension;
		this.hierarchy = hierarchy;
		this.level = level;
	}
	public String getDimension() {
		return dimension;
	}
	public String getHierarchy() {
		return hierarchy;
	}
	public String getLevel() {
		return level;
	}
}