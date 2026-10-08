/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One node of the LookML block tree: a {@code key: value} parameter, a {@code key: { … }}
 * block, or a bare positional value (a {@code join_on: 1 = 1} predicate, say).
 *
 * <p>LookML isn't YAML or JSON — it's a nested-brace dialect where the {@code key:} is
 * optional for list-ish values and quoting rules differ. Rather than bolt on a YAML parser
 * (and get {@code ${…}} / {@code {{…}}} / bare-expression edge cases wrong), the converter
 * walks this tree. See {@link LookmlParser}.
 */
public class LookmlBlock {

    private final String key;
    private final String value;
    private final List<LookmlBlock> children = new ArrayList<>();

    public LookmlBlock(String key, String value) {
        this.key = key;
        this.value = value;
    }

    /** The parameter / block name, or {@code null} for a bare positional value. */
    public String getKey() {
        return key;
    }

    /** The scalar value, or {@code null} when this node is a block rather than a parameter. */
    public String getValue() {
        return value;
    }

    public List<LookmlBlock> getChildren() {
        return children;
    }

    public LookmlBlock add(LookmlBlock child) {
        children.add(child);
        return this;
    }

    public boolean isBlock() {
        return !children.isEmpty();
    }

    /** First child block with the given key, if any. */
    public Optional<LookmlBlock> child(String childKey) {
        return children.stream().filter(c -> childKey.equalsIgnoreCase(c.key)).findFirst();
    }

    /**
     * The scalar value of the first child with the given key. Lookup is case-insensitive and
     * trims, because hand-edited LookML is inconsistent about both.
     */
    public Optional<String> param(String paramKey) {
        return children.stream()
                .filter(c -> !c.isBlock() && paramKey.equalsIgnoreCase(c.key))
                .map(c -> c.value)
                .findFirst()
                .map(String::trim);
    }

    /** All child blocks with the given key, in declaration order. */
    public List<LookmlBlock> childrenNamed(String childKey) {
        List<LookmlBlock> out = new ArrayList<>();
        for (LookmlBlock c : children) {
            if (childKey.equalsIgnoreCase(c.key)) out.add(c);
        }
        return out;
    }

    /** True when a parameter is present and one of the truthy LookML booleans. */
    public boolean flag(String paramKey) {
        return param(paramKey)
                .map(v -> "yes".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v))
                .orElse(false);
    }

    @Override
    public String toString() {
        return key + ": " + value + (children.isEmpty() ? "" : " {" + children + "}");
    }
}
