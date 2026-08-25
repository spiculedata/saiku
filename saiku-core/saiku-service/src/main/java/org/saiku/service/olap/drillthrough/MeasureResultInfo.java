/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
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
