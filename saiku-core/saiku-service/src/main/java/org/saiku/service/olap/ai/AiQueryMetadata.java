/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-result metadata returned alongside the matrix. Lets clients (and
 * agents) render the result without having to re-derive axis labels.
 */
public class AiQueryMetadata {

    public static class Caption {
        private String name;
        private String caption;

        public Caption() {}

        public Caption(String name, String caption) {
            this.name = name;
            this.caption = caption;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String v) {
            this.caption = v;
        }
    }

    /** When the result was computed + whether it came from cache. */
    public static class Freshness {
        private long computedAtMillis;
        private boolean cached;

        public Freshness() {}

        public Freshness(long computedAtMillis, boolean cached) {
            this.computedAtMillis = computedAtMillis;
            this.cached = cached;
        }

        public long getComputedAtMillis() {
            return computedAtMillis;
        }

        public void setComputedAtMillis(long v) {
            this.computedAtMillis = v;
        }

        public boolean isCached() {
            return cached;
        }

        public void setCached(boolean v) {
            this.cached = v;
        }
    }

    private List<Caption> rows = new ArrayList<>();
    private List<Caption> columns = new ArrayList<>();
    private List<String> measures = new ArrayList<>();
    /** MDX the engine actually executed — opaque to the agent, useful for
     *  human debugging in admin tools / logs. */
    private String generatedMdx;
    /** When the result was computed + whether it was served from cache. */
    private Freshness freshness;

    public List<Caption> getRows() {
        return rows;
    }

    public void setRows(List<Caption> v) {
        this.rows = v == null ? new ArrayList<>() : v;
    }

    public List<Caption> getColumns() {
        return columns;
    }

    public void setColumns(List<Caption> v) {
        this.columns = v == null ? new ArrayList<>() : v;
    }

    public List<String> getMeasures() {
        return measures;
    }

    public void setMeasures(List<String> v) {
        this.measures = v == null ? new ArrayList<>() : v;
    }

    public String getGeneratedMdx() {
        return generatedMdx;
    }

    public void setGeneratedMdx(String v) {
        this.generatedMdx = v;
    }

    public Freshness getFreshness() {
        return freshness;
    }

    public void setFreshness(Freshness v) {
        this.freshness = v;
    }
}
