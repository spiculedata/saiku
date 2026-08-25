/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.olap4j.impl.Named;
import org.saiku.ArrayMapDeserializer;
import org.saiku.olap.query2.common.AbstractThinSortableQuerySet;

public class ThinHierarchy extends AbstractThinSortableQuerySet implements Named {

    private String name;
    private String caption;
    private String dimension;

    private Map<String, ThinLevel> levels = new HashMap<>();

    @JsonDeserialize(using = ArrayMapDeserializer.class)
    private Map<String, String> cmembers = new HashMap<>();

    public ThinHierarchy() {}

    public ThinHierarchy(String uniqueName, String caption, String dimension, Map<String, ThinLevel> levels) {
        this.name = uniqueName;
        this.caption = caption;
        this.dimension = dimension;
        if (levels != null) {
            this.levels = levels;
        }
    }

    public ThinHierarchy(
            String uniqueName, String caption, String dimension, Map<String, ThinLevel> levels, List<String> tcm) {
        this.name = uniqueName;
        this.caption = caption;
        this.dimension = dimension;
        if (levels != null) {
            this.levels = levels;
        }
        for (String t : tcm) {
            cmembers.put(t, t);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * @return the caption
     */
    public String getCaption() {
        return caption;
    }

    /**
     * @param caption the caption to set
     */
    public void setCaption(String caption) {
        this.caption = caption;
    }

    /**
     * @return the levels
     */
    public Map<String, ThinLevel> getLevels() {
        return levels;
    }

    public ThinLevel getLevel(String name) {
        if (levels.containsKey(name)) {
            return levels.get(name);
        }
        return null;
    }

    /**
     * @param levels the levels to set
     */
    public void setLevels(Map<String, ThinLevel> levels) {
        this.levels = levels;
    }

    /**
     * @param name the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the dimension
     */
    public String getDimension() {
        return dimension;
    }

    /**
     * @param dimension the dimension to set
     */
    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public Map<String, String> getCmembers() {
        return cmembers;
    }

    public void setCmembers(Map<String, String> cmembers) {
        this.cmembers = cmembers;
    }
}
