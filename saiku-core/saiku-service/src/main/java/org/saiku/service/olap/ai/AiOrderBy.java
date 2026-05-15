/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * Sort + top-N specification. {@code by} is a measure name (canonical
 * or display); {@code direction} is "asc" or "desc" (case-insensitive).
 *
 * <p>With a non-zero {@link AiQueryRequest#getLimit() limit}, the
 * converter emits TopCount (desc) or BottomCount (asc). Without limit,
 * it emits Order on the rows axis.
 */
public class AiOrderBy {

    private String by;
    private String direction = "desc";

    public AiOrderBy() {}

    public AiOrderBy(String by, String direction) {
        this.by = by;
        this.direction = direction;
    }

    public String getBy() {
        return by;
    }

    public void setBy(String v) {
        this.by = v;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String v) {
        this.direction = v;
    }

    public boolean isAscending() {
        return direction != null && direction.toLowerCase().startsWith("asc");
    }
}
