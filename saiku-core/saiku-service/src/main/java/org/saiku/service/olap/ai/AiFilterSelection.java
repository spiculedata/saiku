/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * A slicer-axis filter. Lands in the MDX WHERE clause.
 *
 * <p>Supported operators ({@link #getOp()}):
 * <ul>
 *   <li>{@code "in"} (default) — emit the explicit member set.
 *       {@link #members} must be non-empty.</li>
 *   <li>{@code "not_in"} — emit {@code Except(level.Members, {members})}.
 *       {@link #members} must be non-empty.</li>
 *   <li>{@code "between"} — emit {@code Range(start, end)}.
 *       {@link #members} must have exactly 2 entries [start, end].</li>
 *   <li>{@code "descendants_of"} — emit {@code Descendants(member, ALL)}.
 *       {@link #members} must have exactly 1 entry.</li>
 *   <li>{@code "relative"} — emit a time-relative set driven by
 *       {@link #value} (and {@link #n} for {@code last_n_*}). See
 *       {@link AiSchemaConverter} for the supported {@code value} enum and
 *       MDX translation. {@link #members} is unused.</li>
 * </ul>
 *
 * <p>Member entries (for ops other than {@code relative}) are MDX unique
 * names like {@code [Time].[Time].[Year].&[2001]}. Build them by appending
 * the member name to the level's {@code uniqueName}, or fetch ready-made
 * unique names via {@code GET /ai/members/search}.
 */
public class AiFilterSelection {

    private String dimension;
    private String hierarchy;
    private String level;
    /** Operator. Default {@code "in"}. */
    private String op = "in";

    private List<String> members = new ArrayList<>();
    /** Relative-op preset. One of {@code last_n_days|last_n_months|last_n_quarters|
     *  last_n_years|ytd|mtd|qtd|previous_period|same_period_last_year}. */
    private String value;
    /** Count for {@code last_n_*} presets. Defaults to 1; ignored for other presets. */
    private int n = 1;

    public AiFilterSelection() {}

    public AiFilterSelection(String dimension, String hierarchy, String level, List<String> members) {
        this.dimension = dimension;
        this.hierarchy = hierarchy;
        this.level = level;
        this.members = members == null ? new ArrayList<>() : members;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String v) {
        this.dimension = v;
    }

    public String getHierarchy() {
        return hierarchy;
    }

    public void setHierarchy(String v) {
        this.hierarchy = v;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String v) {
        this.level = v;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String v) {
        this.op = v == null || v.isEmpty() ? "in" : v;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> v) {
        this.members = v == null ? new ArrayList<>() : v;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String v) {
        this.value = v;
    }

    public int getN() {
        return n;
    }

    public void setN(int v) {
        this.n = v;
    }
}
