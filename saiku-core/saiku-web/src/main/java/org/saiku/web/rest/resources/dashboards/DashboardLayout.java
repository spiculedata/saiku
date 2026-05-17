/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Grid layout for a dashboard. v1 is a 12-column CSS grid that auto-stacks
 * to a single column below ~768px. Rows grow with tile count; the layout
 * doesn't carry a row cap.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardLayout {

    /** Fixed at 12 in v1. Persisted explicitly so a future bump (16-col,
     *  24-col) doesn't silently relayout older dashboards. */
    public int cols = 12;

    public List<DashboardTile> tiles = new ArrayList<>();
}
