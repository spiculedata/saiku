/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

/**
 * One reference from a saved query, dashboard or app to a member that the after-model no longer
 * resolves (saiku#1434).
 *
 * @param file      repository-relative path of the file the reference came from
 * @param location  where inside that file — a tile id, a query name, or {@code <mdx>} for a
 *                  saved query's MDX
 * @param kind      the kind of member the reference points at
 * @param cube      the cube the reference was resolved against (may be null when the file's cube
 *                  itself is gone)
 * @param dimension the dimension for a level reference, otherwise null
 * @param name      the member name the file asked for
 * @param reason    why it no longer resolves — {@link #UNKNOWN_CUBE},
 *                  {@link #MISSING_MEASURE}, {@link #MISSING_DIMENSION}, {@link #MISSING_LEVEL}
 */
public record BrokenReference(
        String file,
        String location,
        ModelElementKind kind,
        String cube,
        String dimension,
        String name,
        String reason) {

    public static final String UNKNOWN_CUBE = "cube no longer exists in the model";
    public static final String MISSING_MEASURE = "measure no longer exists in the cube";
    public static final String MISSING_DIMENSION = "dimension no longer exists in the cube";
    public static final String MISSING_LEVEL = "level no longer exists in the dimension";

    /** The reference as it would be written in MDX — what a reviewer greps for. */
    public String mdxForm() {
        return switch (kind) {
            case MEASURE -> "[Measures].[" + name + "]";
            case LEVEL -> "[" + dimension + "].[" + name + "]";
            case DIMENSION -> "[" + name + "]";
            case CUBE -> "[" + name + "]";
        };
    }

    /** One line of the report, e.g. {@code homes/admin/x.saikudash — tile-kpi — [Measures].[Unit Sales]}. */
    public String describe() {
        return file + " — " + (location == null || location.isBlank() ? "(file)" : location) + " — " + mdxForm() + " ("
                + reason + ")";
    }
}
