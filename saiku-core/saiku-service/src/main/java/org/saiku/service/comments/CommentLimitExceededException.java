/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.comments;

/**
 * Raised by {@link CommentService#add} when a comment breaches one of the
 * storage caps configured for #2535: the per-comment body size
 * ({@code saiku.comments.maxBodyBytes}) or the per-dashboard live-comment
 * count ({@code saiku.comments.maxCount}). The REST layer translates this into
 * a 413 with the kind in the message.
 */
public class CommentLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which cap fired. */
    public enum Kind {
        /** The comment body exceeds the configured body-size cap. */
        BODY_TOO_LARGE,
        /** The dashboard already holds the configured maximum of live comments. */
        COUNT_REACHED
    }

    private final Kind kind;

    public CommentLimitExceededException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
