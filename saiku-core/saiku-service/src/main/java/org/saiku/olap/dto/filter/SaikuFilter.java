/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.dto.filter;

import java.util.ArrayList;
import java.util.List;
import org.saiku.olap.dto.SimpleCubeElement;

public class SaikuFilter {

    private String name;
    private String description;
    private SimpleCubeElement dimension;

    private SimpleCubeElement hierarchy;
    private List<SimpleCubeElement> members = new ArrayList<>();
    private String owner;

    public SaikuFilter() {}

    public SaikuFilter(
            String name,
            String description,
            SimpleCubeElement dimension,
            SimpleCubeElement hierarchy,
            List<SimpleCubeElement> members) {
        this(name, description, dimension, hierarchy, members, null);
    }

    public SaikuFilter(
            String name,
            String description,
            SimpleCubeElement dimension,
            SimpleCubeElement hierarchy,
            List<SimpleCubeElement> members,
            String owner) {
        this.name = name;
        this.description = description;
        this.dimension = dimension;
        this.hierarchy = hierarchy;
        this.members = members;
        this.owner = owner;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return the dimension
     */
    public SimpleCubeElement getDimension() {
        return dimension;
    }

    /**
     * @return the hierarchy
     */
    public SimpleCubeElement getHierarchy() {
        return hierarchy;
    }

    /**
     * @return the members
     */
    public List<SimpleCubeElement> getMembers() {
        return members;
    }

    /**
     * @return the owner
     */
    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }
}
