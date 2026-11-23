/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

/**
 * Thrown when a snapshot reference is unsigned, tampered, expired, off-origin, malformed, or names a
 * caller other than the one presenting it (saiku#1810).
 *
 * <p>Every message is a <b>fixed, short, reason-only string</b> — never the offending token, path or
 * user — because these messages reach a scheduled job's recorded outcome and, via the REST surface, a
 * client. There is no reason to make a signature oracle or a path oracle out of them.
 */
public class SnapshotReferenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SnapshotReferenceException(String message) {
        super(message);
    }
}
