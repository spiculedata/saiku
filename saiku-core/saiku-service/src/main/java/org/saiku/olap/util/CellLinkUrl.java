/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Cube-authored HTTP(S) template used to open a business-system URL from a cellset
 * intersection. The cube annotation wins; an optional {@code <cellLinkUrl>} on the
 * {@code .sds} is the fallback when the schema has none.
 */
public final class CellLinkUrl {

    /** Mondrian {@code <Annotation name="saiku.cellLink.url">} on the cube. */
    public static final String ANNOTATION = "saiku.cellLink.url";

    /** Optional JAXB / properties key on the datasource ({@code <cellLinkUrl>}). */
    public static final String PROPERTY = "cellLinkUrl";

    private CellLinkUrl() {}

    /** Read the cube annotation; blank or missing → {@code null}. */
    @Nullable
    public static String fromAnnotationMap(@Nullable Map<String, String> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }
        return blankToNull(annotations.get(ANNOTATION));
    }

    /** Cube annotation first, then the {@code .sds} property. */
    @Nullable
    public static String preferCubeThenSds(@Nullable String fromCube, @Nullable String fromSds) {
        String cube = blankToNull(fromCube);
        if (cube != null) {
            return cube;
        }
        return blankToNull(fromSds);
    }

    @Nullable
    private static String blankToNull(@Nullable String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
