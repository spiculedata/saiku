/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code POST /ai/query/saved} — the dashboard layer's
 * resolver for reference-bound tiles. Carries the JCR path to the saved
 * {@code .saiku} file plus the runtime dashboard filters to merge onto
 * the loaded ThinQuery before execution.
 *
 * <p>Filters are optional. When absent / empty, the saved query runs
 * exactly as it was authored — matching pre-merge behavior.
 */
public class AiSavedQueryRequest {

    private String path;
    private List<AiFilterSelection> filters = new ArrayList<>();
    private List<AiFilterSelection> forcedFilters = new ArrayList<>();

    public AiSavedQueryRequest() {}

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<AiFilterSelection> getFilters() {
        return filters;
    }

    public void setFilters(List<AiFilterSelection> filters) {
        this.filters = filters == null ? new ArrayList<>() : filters;
    }

    /**
     * Forced RLS filters (saiku#1104). Unlike {@link #getFilters()} (best-effort dashboard chips),
     * these MUST be applied to the loaded query or the request fails closed — they carry the
     * row-level-security restriction from the embed token's JWT claims, so silently dropping one
     * would serve unfiltered rows. Set only by the embed surface; empty for normal saved-query runs.
     */
    public List<AiFilterSelection> getForcedFilters() {
        return forcedFilters;
    }

    public void setForcedFilters(List<AiFilterSelection> forcedFilters) {
        this.forcedFilters = forcedFilters == null ? new ArrayList<>() : forcedFilters;
    }
}
