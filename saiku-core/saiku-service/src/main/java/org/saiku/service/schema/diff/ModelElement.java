/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.util.Objects;

/**
 * One tracked, named element of a parsed semantic model (saiku#1434).
 *
 * <p>Format-neutral on purpose: a Mondrian {@code <Measure name="Unit Sales">} and an Ossie
 * {@code metrics: [{name: unit_sales}]} both land here as {@code (MEASURE, cube, null, name,
 * signature)} so the diff engine never has to know which serialisation produced them.
 *
 * @param kind      what sort of element this is (see {@link ModelElementKind})
 * @param cube      the owning cube / semantic model name
 * @param container the owning dimension / dataset, or {@code null} for cubes and measures
 * @param name      the element's own name — the identifier saved queries and dashboards reference
 * @param signature a normalised fingerprint of every attribute EXCEPT {@code name}, used for
 *                  rename detection. Two elements whose signatures are equal are the same
 *                  element under a different name; a signature change with an unchanged name is
 *                  a non-breaking {@code MODIFIED} change.
 * @param caption   the display name, when the model gives the element one. Saiku resolves members
 *                  by caption as well as by name (that is how a tile authored in the UI binds a
 *                  member the schema captions {@code "MoM Growth"}), so a reference-walker that
 *                  only knew names would report a working tile as broken. Null when there is no
 *                  caption. Never used for rename detection — a caption is an alias, not a name.
 */
public record ModelElement(
        ModelElementKind kind, String cube, String container, String name, String signature, String caption) {

    public ModelElement {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cube, "cube");
        Objects.requireNonNull(name, "name");
        signature = signature == null ? "" : signature;
    }

    public ModelElement(ModelElementKind kind, String cube, String container, String name, String signature) {
        this(kind, cube, container, name, signature, null);
    }

    public ModelElement(ModelElementKind kind, String cube, String container, String name) {
        this(kind, cube, container, name, "", null);
    }

    /**
     * The stable identity used for set operations: kind + owner + container + name, lower-cased.
     * Mondrian member lookup is case-insensitive, so treating {@code Unit Sales} and
     * {@code unit sales} as the same element is what keeps the scanner's false-positive rate low
     * (saiku#1434 acceptance criterion).
     */
    public String key() {
        return key(kind, cube, container, name);
    }

    public static String key(ModelElementKind kind, String cube, String container, String name) {
        return kind.label() + ' ' + norm(cube) + ' ' + norm(container) + ' ' + norm(name);
    }

    /** Lookup key for a member reference with no container (a measure or a dimension). */
    public static String key(ModelElementKind kind, String cube, String name) {
        return key(kind, cube, null, name);
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Human-readable {@code Cube / Dimension / Name} path used in diff output. */
    public String displayPath() {
        return container == null || container.isBlank() ? name : cube + " / " + container + " / " + name;
    }
}
