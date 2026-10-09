/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

/**
 * The kinds of named element the diff engine tracks (saiku#1434).
 *
 * <p>The two model formats do not share a vocabulary — Mondrian has cubes / measures /
 * dimensions / levels, Ossie has semantic models / datasets / fields / metrics — so the kinds
 * are mapped rather than shared:
 *
 * <table>
 *   <caption>Cross-format element mapping</caption>
 *   <tr><th>Mondrian</th><th>Ossie</th><th>{@link ModelElement.Kind}</th></tr>
 *   <tr><td>{@code <Cube>}</td><td>semantic model</td><td>{@link #CUBE}</td></tr>
 *   <tr><td>{@code <Measure>}</td><td>{@code metrics[]}</td><td>{@link #MEASURE}</td></tr>
 *   <tr><td>{@code <Dimension>}</td><td>{@code datasets[]}</td><td>{@link #DIMENSION}</td></tr>
 *   <tr><td>{@code <Level>}</td><td>{@code fields[]}</td><td>{@link #LEVEL}</td></tr>
 * </table>
 *
 * <p>Mapping Ossie metrics onto {@link #MEASURE} and fields onto {@link #LEVEL} is what lets one
 * diff report speak for both formats, and lets the broken-reference scanner report "measure
 * gone" the same way whichever serialisation the model happens to use.
 */
public enum ModelElementKind {
    CUBE("cube", true),
    MEASURE("measure", true),
    DIMENSION("dimension", true),
    LEVEL("level", false);

    private final String label;
    private final boolean containerCarriesOwnName;

    ModelElementKind(String label, boolean containerCarriesOwnName) {
        this.label = label;
        this.containerCarriesOwnName = containerCarriesOwnName;
    }

    public String label() {
        return label;
    }

    /**
     * Whether the {@code container} of an element of this kind is itself a tracked element
     * (Mondrian dimensions contain levels; Ossie datasets contain fields). Cubes and measures
     * have no container.
     */
    public boolean containerCarriesOwnName() {
        return containerCarriesOwnName;
    }
}
