/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * A dim/hier/level filter — used both as a dashboard-level default
 * (applied on load) and as the {@code target} of a filter-widget tile.
 *
 * <p>Members are MDX unique names. An empty list means "any" — i.e. the
 * filter is registered but selects nothing yet (default state for an
 * empty multi-select widget).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardFilter {

    public String dimension;
    public String hierarchy;
    public String level;
    public List<String> members = new ArrayList<>();
}
