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
}
