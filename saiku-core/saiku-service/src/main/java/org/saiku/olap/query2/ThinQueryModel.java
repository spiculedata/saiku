package org.saiku.olap.query2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ThinQueryModel {

    private Map<AxisLocation, ThinAxis> axes = new HashMap<>();
    private boolean visualTotals = false;
    private String visualTotalsPattern;
    private boolean lowestLevelsOnly = false;
    private ThinDetails details;
    private List<ThinCalculatedMeasure> calculatedMeasures = new ArrayList<>();
    private List<ThinCalculatedMember> calculatedMembers = new ArrayList<>();

    public enum AxisLocation {
        FILTER,
        COLUMNS,
        ROWS,
        PAGES
    }

    /**
     * @return the axes
     */
    public Map<AxisLocation, ThinAxis> getAxes() {
        return axes;
    }

    public ThinAxis getAxis(AxisLocation axis) {
        return axes.get(axis);
    }

    /**
     * @param axes the axes to set
     */
    public void setAxes(Map<AxisLocation, ThinAxis> axes) {
        this.axes = axes;
    }

    /**
     * @return the visualTotals
     */
    public boolean isVisualTotals() {
        return visualTotals;
    }

    /**
     * @param visualTotals the visualTotals to set
     */
    public void setVisualTotals(boolean visualTotals) {
        this.visualTotals = visualTotals;
    }

    /**
     * @return the visualTotalsPattern
     */
    public String getVisualTotalsPattern() {
        return visualTotalsPattern;
    }

    /**
     * @param visualTotalsPattern the visualTotalsPattern to set
     */
    public void setVisualTotalsPattern(String visualTotalsPattern) {
        this.visualTotalsPattern = visualTotalsPattern;
    }

    /**
     * @return the lowestLevelsOnly
     */
    public boolean isLowestLevelsOnly() {
        return lowestLevelsOnly;
    }

    /**
     */
    public void setLowestLevelsOnly(boolean lowest) {
        this.lowestLevelsOnly = lowest;
    }

    public List<ThinCalculatedMeasure> getCalculatedMeasures() {
        return calculatedMeasures;
    }

    public void setCalculatedMeasures(List<ThinCalculatedMeasure> calculatedMeasures) {
        this.calculatedMeasures = calculatedMeasures;
    }

    public List<ThinCalculatedMember> getCalculatedMembers() {
        return calculatedMembers;
    }

    public void setCalculatedMembers(List<ThinCalculatedMember> calculatedMembers) {
        this.calculatedMembers = calculatedMembers;
    }

    public ThinDetails getDetails() {
        return details;
    }

    public void setDetails(ThinDetails details) {
        this.details = details;
    }

    public boolean hasAggregators() {
        if (axes != null) {
            for (ThinAxis ta : axes.values()) {
                if (ta.getAggregators().size() > 0) {
                    return true;
                }
                if (ta.getHierarchies() != null) {
                    for (ThinHierarchy th : ta.getHierarchies()) {
                        for (ThinLevel tl : th.getLevels().values()) {
                            if (tl.getAggregators() != null
                                    && tl.getAggregators().size() > 0) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        // TODO Auto-generated method stub
        return false;
    }

    public ThinLevel getLevel(String name) {
        for (ThinAxis ta : axes.values()) {
            if (ta.getHierarchies() != null) {
                for (ThinHierarchy th : ta.getHierarchies()) {
                    for (ThinLevel level : th.getLevels().values()) {
                        String uniqueName = th.getName() + ".[" + level.getName() + "]";
                        if (name != null && name.equals(uniqueName)) {
                            return level;
                        }
                    }
                }
            }
        }

        return null;
    }
}
