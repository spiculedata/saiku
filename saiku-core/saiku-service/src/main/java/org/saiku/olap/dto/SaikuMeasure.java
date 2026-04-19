package org.saiku.olap.dto;

public class SaikuMeasure extends SaikuMember {

    private String measureGroup;

    public SaikuMeasure() {}

    public SaikuMeasure(
            String name,
            String uniqueName,
            String caption,
            String description,
            String dimensionUniqueName,
            String hierarchyUniqueName,
            String levelUniqueName,
            boolean visible,
            boolean calculated,
            String measuregroup) {
        super(
                name,
                uniqueName,
                caption,
                description,
                dimensionUniqueName,
                hierarchyUniqueName,
                levelUniqueName,
                calculated);
        this.measureGroup = measuregroup;
    }

    /**
     *
     * @return the measuregroup
     */
    public String getMeasureGroup() {
        return measureGroup;
    }
}
