/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * A slicer-axis filter. Lands in the MDX WHERE clause. {@code members}
 * must be non-empty; each entry is a member unique name like
 * {@code [Time].[Time By].[Year].&[2001]}.
 */
public class AiFilterSelection {

    private String dimension;
    private String hierarchy;
    private String level;
    private List<String> members = new ArrayList<>();

    public AiFilterSelection() {}

    public AiFilterSelection(String dimension, String hierarchy, String level, List<String> members) {
        this.dimension = dimension;
        this.hierarchy = hierarchy;
        this.level = level;
        this.members = members == null ? new ArrayList<>() : members;
    }

    public String getDimension() { return dimension; }
    public void setDimension(String v) { this.dimension = v; }
    public String getHierarchy() { return hierarchy; }
    public void setHierarchy(String v) { this.hierarchy = v; }
    public String getLevel() { return level; }
    public void setLevel(String v) { this.level = v; }
    public List<String> getMembers() { return members; }
    public void setMembers(List<String> v) { this.members = v == null ? new ArrayList<>() : v; }
}
