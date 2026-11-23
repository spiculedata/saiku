/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The parsed contents of one semantic model, flattened to {@link ModelElement}s (saiku#1434).
 *
 * <p>Both parsers produce this one shape, which is why the diff engine, the broken-reference
 * scanner and the Markdown renderer have no format conditionals in them.
 *
 * <p>Membership tests are <strong>case-insensitive</strong>: Mondrian resolves member names
 * case-insensitively and both Mondrian and Ossie model files are hand-edited, so a
 * case-sensitive index would report a rename of {@code Product Family} to {@code product family}
 * and then flag every dashboard that still says {@code Product Family} — a pure false positive
 * against a change Mondrian would accept. That is the single biggest contributor to the < 5%
 * false-positive budget in saiku#1434.
 */
public final class ModelSnapshot {

    private final ModelFormat format;
    private final String name;
    private final List<ModelElement> elements;
    private final Map<String, ModelElement> byKey;
    /** Display names (captions / labels) to the element they name, lower-cased. */
    private final Map<String, String> byCaption;

    ModelSnapshot(ModelFormat format, String name, List<ModelElement> elements) {
        this.format = format;
        this.name = name == null || name.isBlank() ? "(unnamed)" : name;
        this.elements = List.copyOf(elements);
        Map<String, ModelElement> index = new LinkedHashMap<>();
        Map<String, String> captions = new LinkedHashMap<>();
        for (ModelElement e : this.elements) {
            index.putIfAbsent(e.key(), e);
            if (e.caption() != null && !e.caption().isBlank()) {
                captions.putIfAbsent(
                        e.kind().label() + ' ' + norm(e.cube()) + ' ' + norm(e.container()) + ' ' + norm(e.caption()),
                        e.key());
            }
        }
        this.byKey = Collections.unmodifiableMap(index);
        this.byCaption = Collections.unmodifiableMap(captions);
    }

    public ModelFormat format() {
        return format;
    }

    /** The schema / semantic-model name, e.g. {@code FoodMart} or {@code flights}. */
    public String name() {
        return name;
    }

    public List<ModelElement> elements() {
        return elements;
    }

    public Optional<ModelElement> find(ModelElementKind kind, String cube, String container, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        ModelElement byName = byKey.get(ModelElement.key(kind, cube, container, name));
        if (byName != null) {
            return Optional.of(byName);
        }
        // A display name resolves the same member. Saiku accepts either on the query surfaces, so
        // the validator has to as well or every captioned member reads as broken.
        String canonical = byCaption.get(ModelElement.key(kind, cube, container, name));
        return canonical == null ? Optional.empty() : Optional.ofNullable(byKey.get(canonical));
    }

    public boolean hasCube(String cube) {
        return find(ModelElementKind.CUBE, cube, null, cube).isPresent();
    }

    /** True when {@code cube} exposes a measure called {@code measure} (or a calculated member). */
    public boolean hasMeasure(String cube, String measure) {
        return find(ModelElementKind.MEASURE, cube, null, measure).isPresent();
    }

    public boolean hasDimension(String cube, String dimension) {
        return find(ModelElementKind.DIMENSION, cube, null, dimension).isPresent();
    }

    public boolean hasLevel(String cube, String dimension, String level) {
        return find(ModelElementKind.LEVEL, cube, dimension, level).isPresent();
    }

    /** Every cube name in the model, in document order. */
    public List<String> cubeNames() {
        List<String> names = new ArrayList<>();
        for (ModelElement e : elements) {
            if (e.kind() == ModelElementKind.CUBE) {
                names.add(e.name());
            }
        }
        return names;
    }

    /** Members of one cube, filtered by kind, in document order. */
    public List<ModelElement> members(String cube, ModelElementKind kind) {
        String target = norm(cube);
        List<ModelElement> out = new ArrayList<>();
        for (ModelElement e : elements) {
            if (e.kind() == kind && norm(e.cube()).equals(target)) {
                out.add(e);
            }
        }
        return out;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "ModelSnapshot[" + format.displayName() + " " + name + ", " + elements.size() + " elements]";
    }
}
