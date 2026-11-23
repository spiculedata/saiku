/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.exception;

/**
 * saiku#1968 (CWE-863) — a caller is authenticated but not authorised to reach a specific
 * security-enabled datasource (their roles resolve to no Mondrian role, and they are not an admin).
 *
 * <p>A dedicated subtype of {@link SaikuServiceException} so the two distinct call sites can tell an
 * access-denial apart from a generic service failure:
 *
 * <ul>
 *   <li>The <b>single-datasource</b> path (a user querying one specific cube they hold no role on)
 *       lets this propagate — the request fails closed, no data is served.</li>
 *   <li>The <b>whole-datasource enumeration</b> path ({@code AbstractConnectionManager
 *       .getAllConnections()} / {@code getAllOlapConnections()}) catches this per datasource and
 *       simply OMITS the denied datasource from the returned map instead of aborting the whole
 *       loop, so a user with disjoint roles across two security-enabled datasources still sees the
 *       ones they CAN access (and only those).</li>
 * </ul>
 *
 * <p>Still a {@link RuntimeException} (via {@code SaikuServiceException}) so it needs no method
 * signature changes, and is never an NPE or an information leak on the wire.
 */
public class SaikuAccessDeniedException extends SaikuServiceException {

    private static final long serialVersionUID = 1L;

    public SaikuAccessDeniedException(String message) {
        super(message);
    }

    public SaikuAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
