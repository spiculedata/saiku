package org.saiku.service.olap.drillthrough;

public class MeasureResultInfo implements ResultInfo {
	private String name;
	public MeasureResultInfo(String name) {
		super();
		this.name = name;
	}

	public String getName() {
		return name;
	}
}