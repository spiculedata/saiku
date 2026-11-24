/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.exception;

/**
 * saiku#2526 — a request identifier (field, metric) that does not resolve to anything in the
 * caller's view of the semantic model. Distinct from {@link SaikuAccessDeniedException} (which
 * means the identifier exists but the caller's roles deny it) and from a bare
 * {@code IllegalArgumentException}: this is a typed, client-fixable rejection, so the web layer
 * maps it to <b>400</b> with the message the caller can act on.
 *
 * <p>Motivating case: the Ossie shelf-state translator used to emit any identifier it could not
 * resolve as a quoted physical column, so a caller denied a semantic field could name its
 * physical column instead and get the values. Unresolvable identifiers are now refused before
 * any SQL is emitted.
 *
 * <p>Still a {@link RuntimeException} (via {@code SaikuServiceException}) so it needs no method
 * signature changes, and is never an NPE or an information leak on the wire.
 */
public class SaikuValidationException extends SaikuServiceException {

    private static final long serialVersionUID = 1L;

    public SaikuValidationException(String message) {
        super(message);
    }

    public SaikuValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
