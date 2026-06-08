/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * Unified filter panel docked at the top of the dashboard editor
 * (saiku#996). Replaces the per-filter-tile model — filter widgets no
 * longer occupy grid cells.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardFilterPanel {

    /** UI hint — whether the panel renders collapsed (chip strip only)
     *  or expanded (compact pickers visible). Persisted so the analyst's
     *  preference survives reload. */
    public boolean collapsed = false;

    /** Ordered list of filters in the panel. Order is significant — it
     *  drives both the picker layout and the chip strip render order. */
    public List<PanelFilter> filters = new ArrayList<>();

    /** One entry in the panel. Inherits the dim/hier/level target shape
     *  from DashboardFilter via field duplication (no Java-side
     *  inheritance — keeps Jackson serialisation flat) and adds a stable
     *  id + a widget discriminator. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PanelFilter {

        /** Stable identifier so the drag-reorder UI can key entries
         *  without re-deriving them from (dim, hier, level). */
        public String id;

        /** {@code single-select | multi-select | date-range}. */
        public String widget;

        /** Optional source cube. Lets the panel populate the member
         *  dropdown via /ai/members/search without deriving the cube
         *  from a sibling tile. */
        public AiCubeRef cube;

        public String dimension;
        public String hierarchy;
        public String level;
        public List<String> members = new ArrayList<>();
    }
}
