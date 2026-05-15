/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * A row or column axis entry. {@code dimension} is required; {@code hierarchy}
 * defaults to the single-named hierarchy when the dimension only has one;
 * {@code level} is required.
 *
 * <p>If {@code members} is non-empty, only those members are shown on the
 * axis (useful for slicing); otherwise all members at that level are
 * included.
 */
public class AiAxisSelection {

    private String dimension;
    private String hierarchy;
    private String level;
    private List<String> members = new ArrayList<>();

    public AiAxisSelection() {}

    public AiAxisSelection(String dimension, String hierarchy, String level) {
        this.dimension = dimension;
        this.hierarchy = hierarchy;
        this.level = level;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String v) {
        this.dimension = v;
    }

    public String getHierarchy() {
        return hierarchy;
    }

    public void setHierarchy(String v) {
        this.hierarchy = v;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String v) {
        this.level = v;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> v) {
        this.members = v == null ? new ArrayList<>() : v;
    }
}
