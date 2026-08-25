/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2.common;

import java.util.ArrayList;
import java.util.List;
import org.saiku.olap.query2.filter.ThinFilter;

public abstract class AbstractThinQuerySet implements ThinQuerySet {

    private String mdx;

    private final List<ThinFilter> filters = new ArrayList<>();

    public abstract String getName();

    public void setMdx(String mdx) {
        this.mdx = mdx;
    }

    public String getMdx() {
        return this.mdx;
    }

    public void addFilter(ThinFilter filter) {
        filters.add(filter);
    }

    public void setFilter(int index, ThinFilter filter) {
        filters.set(index, filter);
    }

    public List<ThinFilter> getFilters() {
        return filters;
    }

    public void clearFilters() {
        filters.clear();
    }
}
