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
 * Measure to include on the COLUMNS axis. The {@code name} must match a
 * measure exposed by the cube's schema. {@code aggregators} are optional —
 * if omitted, the cube's default aggregator is used (Phase 3 will surface
 * the live list via {@code /ai/schema}).
 */
public class AiMeasureSelection {

    private String name;
    private List<String> aggregators = new ArrayList<>();

    public AiMeasureSelection() {}

    public AiMeasureSelection(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public List<String> getAggregators() {
        return aggregators;
    }

    public void setAggregators(List<String> v) {
        this.aggregators = v == null ? new ArrayList<>() : v;
    }
}
