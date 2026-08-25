/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.dto;

import java.util.List;
import java.util.Map;

public class SaikuCubeMetadata {

    private final List<SaikuDimension> dimensions;
    private final List<SaikuMember> measures;
    private final Map<String, Object> properties;

    public SaikuCubeMetadata(
            List<SaikuDimension> dimensions, List<SaikuMember> measures, Map<String, Object> properties) {
        this.dimensions = dimensions;
        this.measures = measures;
        this.properties = properties;
    }

    /**
     * @return the dimensions
     */
    public List<SaikuDimension> getDimensions() {
        return dimensions;
    }

    /**
     * @return the measures
     */
    public List<SaikuMember> getMeasures() {
        return measures;
    }

    /**
     * @return the properties
     */
    public Map<String, Object> getProperties() {
        return properties;
    }
}
