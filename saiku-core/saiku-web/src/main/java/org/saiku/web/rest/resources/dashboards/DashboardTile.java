/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * One tile in a dashboard's grid layout. Polymorphic by {@link #type} —
 * fields irrelevant to a given type stay null and Jackson drops them on
 * serialise (via {@link JsonInclude.Include#NON_NULL}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardTile {

    /** Stable per-tile id (UUID-ish). Used as a key for click-filter source
     *  tagging and as a target for filter widget compatibility. */
    public String id;

    /** Grid position + span. {@code x} + {@code w} must stay within
     *  {@code DashboardLayout.cols}; the client enforces overlap rules. */
    public int x;

    public int y;
    public int w;
    public int h;

    /** {@code chart | table | text | filter}. */
    public String type;

    /** Optional display title. */
    public String title;

    /** Cube ref for chart / table / filter tiles. Null for text tiles. */
    public AiCubeRef cube;

    /** Query binding for chart / table tiles. Null for text / filter tiles. */
    public TileQuery query;

    /** Chart subtype for chart tiles (e.g. {@code bar}, {@code line},
     *  {@code pie}). Pulled from the existing {@code chartTypes.ts} catalog. */
    public String chartType;

    /** Markdown source for text tiles. */
    public String text;

    /** Filter target for filter tiles — which dimension/hierarchy/level
     *  this widget drives. Null for non-filter tiles. */
    public DashboardFilter target;

    /** Widget sub-type for filter tiles:
     *  {@code single-select | multi-select | date-range}. */
    public String widget;
}
