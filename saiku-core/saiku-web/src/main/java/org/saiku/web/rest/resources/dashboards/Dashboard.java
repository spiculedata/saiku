/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level dashboard document. Persisted as a single {@code .saikudash}
 * JSON file in the JCR repository alongside {@code .saiku} query files.
 *
 * <p>The shape is intentionally small — dashboards are a layout layer on
 * top of the AI Query API, not a new query engine. See
 * {@code docs/plans/2026-05-16-dashboards-design.md}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Dashboard {

    /** Stable UUID. Survives renames; used for cross-references. */
    public String id;

    /** Human-readable name shown in the editor + toolbar. */
    public String name;

    /** Schema version. Bumped on incompatible changes; v1 = 1. */
    public int version = 1;

    public DashboardLayout layout = new DashboardLayout();

    /** Default filter values applied on load. Each entry becomes an
     *  {@code ActiveFilter} with {@code source: 'default'} client-side. */
    public List<DashboardFilter> filters = new ArrayList<>();

    /** Unified filter panel docked at the top of the editor (saiku#996).
     *  Replaces the per-filter-tile model — filter widgets no longer
     *  occupy grid cells. Null on dashboards saved before the migration;
     *  the client populates it on first load by promoting any
     *  {@code type:"filter"} tiles. */
    public DashboardFilterPanel filterPanel;
}
