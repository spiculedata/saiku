/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

/**
 * The resource bounds every headless render runs under (saiku#1810).
 *
 * <p>A scheduler thread is a scarce resource and an unattended render is, by definition, not watched
 * by anyone — so a render that misbehaves must fail on its own rather than take the scheduler with
 * it. All three caps are enforced fail-closed: exceeding one aborts the render and throws {@link
 * SnapshotRenderException}.
 *
 * @param maxPanels how many measure tiles a reference may name
 * @param timeoutMillis wall-clock budget for the whole read + encode, enforced on a worker
 * @param maxOutputBytes the largest artifact that may be handed back
 */
public record SnapshotLimits(int maxPanels, long timeoutMillis, int maxOutputBytes) {

    public SnapshotLimits {
        if (maxPanels < 1) {
            throw new IllegalArgumentException("maxPanels must be >= 1");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be >= 1");
        }
    }
}
