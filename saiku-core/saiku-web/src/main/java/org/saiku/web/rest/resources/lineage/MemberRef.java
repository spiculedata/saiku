/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.lineage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed MDX unique name, e.g. {@code [Measures].[Store Sales]} or {@code [Store].[Stores].
 * [Store Country]}. Used to match a target measure / dimension / hierarchy / level against the
 * looser, name-only references that dashboard tiles store (saiku#1120 Phase 1: dashboards persist
 * a bare caption like {@code "Store Sales"} in {@code kpi.measure} / {@code measures[].name}, not
 * the bracketed unique name — see {@code KpiConfig}/{@code AiMeasureSelection}).
 */
final class MemberRef {

    private static final Pattern SEGMENT = Pattern.compile("\\[([^\\]]+)\\]");

    private final String uniqueName;
    private final List<String> segments;

    private MemberRef(String uniqueName, List<String> segments) {
        this.uniqueName = uniqueName;
        this.segments = segments;
    }

    static MemberRef parse(String uniqueName) {
        List<String> segs = new ArrayList<>();
        Matcher m = SEGMENT.matcher(uniqueName);
        while (m.find()) {
            segs.add(m.group(1));
        }
        if (segs.isEmpty()) {
            // No brackets at all — treat the whole (trimmed) string as a bare name, so a caller
            // that passes just "Store Sales" instead of "[Measures].[Store Sales]" still works.
            segs.add(uniqueName.trim());
        }
        return new MemberRef(uniqueName, segs);
    }

    /** The exact string the caller passed in — used for raw-text (MDX/formula) substring matches. */
    String uniqueName() {
        return uniqueName;
    }

    boolean isMeasure() {
        return segments.size() >= 2 && "Measures".equalsIgnoreCase(segments.get(0));
    }

    /** Bare caption a dashboard tile would carry — the last bracketed segment. */
    String leafName() {
        return segments.get(segments.size() - 1);
    }

    String dimensionName() {
        return segments.get(0);
    }

    String hierarchyName() {
        return segments.size() > 1 ? segments.get(1) : null;
    }

    /** Non-null only when the target is a specific level (3+ segments), e.g.
     *  {@code [Store].[Stores].[Store Country]}. A dimension- or hierarchy-level query (1 or 2
     *  segments) leaves this null so {@link #matchesAxis} matches any level under it. */
    String levelName() {
        return segments.size() > 2 ? segments.get(segments.size() - 1) : null;
    }

    /** True if {@code text} literally contains this unique name (saved-query MDX, calc-member
     *  formulas, and — as a fallback — raw dashboard JSON all embed unique names verbatim). */
    boolean matchesRawText(String text) {
        return text != null && text.contains(uniqueName);
    }

    /** True if a measure-bearing field (bare caption or full unique name) refers to this member.
     *  Only meaningful when {@link #isMeasure()}. */
    boolean matchesMeasureField(String value) {
        if (value == null || !isMeasure()) {
            return false;
        }
        return value.equalsIgnoreCase(leafName()) || value.contains(uniqueName);
    }

    /** True if a dimension/hierarchy/level axis reference (dashboard row/column/filter/timeLevel)
     *  refers to this member, at whatever granularity the target was specified. Only meaningful
     *  when this ref is NOT a measure. */
    boolean matchesAxis(String dimension, String hierarchy, String level) {
        if (isMeasure() || dimension == null || !dimension.equalsIgnoreCase(dimensionName())) {
            return false;
        }
        if (hierarchyName() != null && (hierarchy == null || !hierarchy.equalsIgnoreCase(hierarchyName()))) {
            return false;
        }
        if (levelName() != null && (level == null || !level.equalsIgnoreCase(levelName()))) {
            return false;
        }
        return true;
    }
}
