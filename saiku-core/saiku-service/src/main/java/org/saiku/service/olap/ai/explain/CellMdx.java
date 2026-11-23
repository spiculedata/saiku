/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the MDX for one cell: the "cell query" behind the number the user right-clicked.
 *
 * <p>Shape — measures on the columns axis, every other member of the coordinate as a slicer:
 *
 * <pre>
 * SELECT {[Measures].[Store Sales]} ON 0 FROM [Sales]
 * WHERE ([Customers].[USA], [Time].[1997])
 * </pre>
 *
 * <p>A cell query rather than the parent cellset's MDX because it is the thing that actually
 * produces the number: it collapses to a single row in the generated SQL, so the panel shows the
 * statement that computes exactly the cell being explained, and it is cheap enough to re-run for the
 * SQL capture without touching the cached result.
 *
 * <p><b>Why the uniquename validation.</b> Member text is interpolated into an MDX string that is
 * then executed, so a member whose uniquename carried a bracket or a quote would be statement
 * injection into the cube, not a formatting bug. Member names come from the schema rather than
 * from the request, but a schema is data too (OlapDiscoverService reads cube names out of the
 * datasource), so every member is checked against {@link #MEMBER_UNIQUENAME} before use and a
 * malformed one is dropped rather than escaped. Silently dropping degrades the panel to a
 * less-specific query; interpolating would execute something the user never asked for.
 */
public final class CellMdx {

    /**
     * A bracketed, optionally dotted member path: {@code [Customers]}, {@code [Customers].[USA]}.
     * Mondrian escapes {@code ]} inside a member as {@code ]} followed by itself, so a stricter
     * grammar is deliberately not attempted here — a member that doesn't match is dropped.
     */
    private static final Pattern MEMBER_UNIQUENAME =
            Pattern.compile("^\\[[^\\[\\]]*\\](\\s*\\.\\s*\\[[^\\[\\]]*\\])*$");

    private static final Pattern FROM_REFERENCE = Pattern.compile("^\\[[^\\[\\]]*\\](\\s*\\.\\s*\\[[^\\[\\]]*\\])*$");

    private CellMdx() {}

    /**
     * MDX selecting exactly one cell.
     *
     * @param from cube reference for the FROM clause (e.g. {@code [Sales]})
     * @param coordinate the cell's members, row axis and column axis concatenated
     * @return the cell MDX, or {@code null} when no usable member survived validation
     */
    public static String cellQuery(String from, List<CellMember> coordinate) {
        String cube = sanitiseFrom(from);
        if (cube == null) {
            return null;
        }
        List<String> members = new ArrayList<>();
        List<String> measures = new ArrayList<>();
        for (CellMember member : coordinate) {
            String uniqueName = sanitiseMember(member == null ? null : member.uniqueName());
            if (uniqueName == null) {
                continue;
            }
            if (member.measure()) {
                measures.add(uniqueName);
            } else {
                members.add(uniqueName);
            }
        }
        if (measures.isEmpty() && members.isEmpty()) {
            return null;
        }
        StringBuilder select = new StringBuilder("SELECT ");
        if (measures.isEmpty()) {
            // No measure dimension on this axis (a MEMBER set that isn't a measure, or a
            // measure-hierarchy-less cube): project the whole coordinate instead and skip the
            // slicer clause — listing the same members in both places would be a redundant
            // cross-filter, not a narrower query.
            select.append("{").append(String.join(", ", members)).append("}");
            return select.append(" ON 0 FROM ").append(cube).toString();
        }
        select.append("{").append(String.join(", ", measures)).append("}");
        select.append(" ON 0 FROM ").append(cube);
        if (!members.isEmpty()) {
            select.append(" WHERE (").append(String.join(", ", members)).append(")");
        }
        return select.toString();
    }

    /**
     * Pull the FROM reference out of an existing query's MDX, so a cell query addresses the cube
     * the same way the parent query does (Saiku qualifies the FROM clause with catalog/schema
     * whenever the connection sets one, and getting that wrong fails to resolve).
     *
     * @return the reference, or {@code null} when the MDX has no parsable FROM clause
     */
    public static String fromOf(String mdx) {
        if (mdx == null) {
            return null;
        }
        String[] tokens = mdx.trim().split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if ("FROM".equalsIgnoreCase(tokens[i])) {
                String candidate = tokens[i + 1].replaceAll("[;,]+$", "");
                if (FROM_REFERENCE.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Bracketed member path or {@code null} when it isn't one. */
    public static String sanitiseMember(String uniqueName) {
        if (uniqueName == null) {
            return null;
        }
        String trimmed = uniqueName.trim();
        return MEMBER_UNIQUENAME.matcher(trimmed).matches() ? trimmed : null;
    }

    /** Bracketed cube reference or {@code null} when it isn't one. */
    private static String sanitiseFrom(String from) {
        if (from == null) {
            return null;
        }
        String trimmed = from.trim();
        return FROM_REFERENCE.matcher(trimmed).matches() ? trimmed : null;
    }
}
