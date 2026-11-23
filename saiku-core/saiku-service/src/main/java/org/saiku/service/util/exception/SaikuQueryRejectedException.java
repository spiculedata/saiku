/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.exception;

/**
 * saiku#2420 — a statement the server REFUSES to execute because it cannot prove the
 * guardrails hold, rather than a statement that failed while running.
 *
 * <p>The motivating case is the raw {@code DRILLTHROUGH} row cap (saiku#1914/#2327/#2420):
 * a client-supplied {@code DRILLTHROUGH} statement must reach Mondrian carrying a
 * {@code MAXROWS} bound in {@code [1, saiku.olap.max.rows]}. When the lexical clamp cannot
 * establish that — an unterminated comment makes the parse ambiguous, the cap clause names no
 * positive integer, or Mondrian's own parser disagrees with the clamp about what the statement
 * is — the statement used to be passed through or turned into a double-{@code MAXROWS} parse
 * error. Passing through is fail-open (an unbounded fact-table scan); the parse error is
 * fail-closed but surfaces as an opaque 500. This exception is the fail-closed middle ground:
 * the two raw-drillthrough call sites and {@code GenericExceptionMapper} translate it into a
 * typed 400 client error.
 *
 * <p>Message discipline: rejection messages are static strings naming the rule that was
 * violated. They must never quote the rejected statement — the statement reaches the log via
 * the caller's warn entry, and the wire via nothing.
 */
public class SaikuQueryRejectedException extends SaikuServiceException {

    private static final long serialVersionUID = 1L;

    public SaikuQueryRejectedException(String message) {
        super(message);
    }

    public SaikuQueryRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
