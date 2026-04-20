package org.saiku.service.schema.generate.delta;

/**
 * Tag applied to each element of a {@link org.saiku.service.schema.generate.draft.DraftSchema}
 * when reconciling a freshly inferred draft against a baseline sidecar.
 *
 * <ul>
 *   <li>{@link #NEW} — element exists in current but not in baseline (upstream added it).
 *   <li>{@link #EXISTING} — element exists in both (matched by stable id).
 *   <li>{@link #REMOVED_UPSTREAM} — element existed in baseline but is gone from current.
 * </ul>
 */
public enum DeltaTag {
    NEW,
    EXISTING,
    REMOVED_UPSTREAM
}
