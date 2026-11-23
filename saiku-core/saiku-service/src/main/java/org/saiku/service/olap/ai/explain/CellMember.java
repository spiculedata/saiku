/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

/**
 * One member on a cell coordinate: the {@code uniquename} as MDX text ({@code
 * [Customers].[USA]}), the display {@code caption}, whether it sits on the measure dimension, and
 * the unique name of its parent member ({@code null} for a top-level member of a hierarchy with no
 * {@code All}).
 *
 * <p>The parent is what lets the driver analysis tell two quarters of a year (both children of
 * {@code [Time].[1997]}) apart from a year total sitting next to one of them — see
 * {@link CellDrivers}.
 *
 * <p>Deliberately not {@code org.olap4j.Member} — the driver/narrative logic in this package is
 * pure and unit-testable against a list of these, so it needs no live connection.
 */
public record CellMember(String uniqueName, String caption, boolean measure, String parentUniqueName) {

    public CellMember {
        uniqueName = uniqueName == null ? "" : uniqueName.trim();
        caption = caption == null || caption.isBlank() ? uniqueName : caption;
    }

    public static CellMember of(String uniqueName, String caption) {
        return new CellMember(uniqueName, caption, false, null);
    }

    /** A member with a known parent, e.g. a quarter under its year. */
    public static CellMember under(String parentUniqueName, String uniqueName, String caption) {
        return new CellMember(uniqueName, caption, false, parentUniqueName);
    }

    public static CellMember measure(String uniqueName, String caption) {
        return new CellMember(uniqueName, caption, true, null);
    }

    @Override
    public String toString() {
        return caption;
    }
}
