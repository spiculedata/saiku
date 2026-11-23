/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

/** Thrown when a snapshot cannot be rendered or encoded (saiku#1810). */
public class SnapshotRenderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SnapshotRenderException(String message) {
        super(message);
    }

    public SnapshotRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
