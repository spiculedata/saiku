/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

/**
 * One structural difference between the before and after models (saiku#1434).
 *
 * @param type    what happened
 * @param kind    the element kind it happened to
 * @param cube    the owning cube / semantic model
 * @param container the owning dimension / dataset, or null
 * @param from    the element as it existed BEFORE, or null for {@link Type#ADDED}
 * @param to      the element as it exists AFTER, or null for {@link Type#REMOVED}
 * @param breaking whether a saved query or dashboard can break because of this change. Removes
 *                 and renames are contract-breaking: a rename is a remove plus an add from the
 *                 reference walker's point of view, and only the rename pairing tells the reviewer
 *                 that the breakage is intentional.
 */
public record ModelChange(
        Type type,
        ModelElementKind kind,
        String cube,
        String container,
        ModelElement from,
        ModelElement to,
        boolean breaking) {

    public enum Type {
        ADDED,
        REMOVED,
        RENAMED,
        MODIFIED
    }

    public static ModelChange added(ModelElement element) {
        return new ModelChange(Type.ADDED, element.kind(), element.cube(), element.container(), null, element, false);
    }

    public static ModelChange removed(ModelElement element) {
        return new ModelChange(Type.REMOVED, element.kind(), element.cube(), element.container(), element, null, true);
    }

    public static ModelChange renamed(ModelElement before, ModelElement after) {
        return new ModelChange(
                Type.RENAMED,
                after.kind(),
                after.cube(),
                after.container(),
                before,
                after,
                // A renamed cube carries every one of its members with it, so it is breaking
                // even though nothing was removed outright.
                true);
    }

    public static ModelChange modified(ModelElement before, ModelElement after) {
        return new ModelChange(
                Type.MODIFIED,
                after.kind(),
                after.cube(),
                after.container(),
                before,
                after,
                // Definition churn under a stable name cannot orphan a reference, so it is not
                // contract-breaking. It is still reported — reviewers want to see it.
                false);
    }

    /** The display path of whichever side exists (the new name for a rename). */
    public String path() {
        if (to != null) {
            return to.displayPath();
        }
        return from.displayPath();
    }

    public String fromName() {
        return from == null ? null : from.name();
    }

    public String toName() {
        return to == null ? null : to.name();
    }
}
