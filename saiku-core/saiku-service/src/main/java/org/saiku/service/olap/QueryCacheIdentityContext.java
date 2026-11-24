/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

/**
 * saiku#2538 (CWE-636) — a stable per-caller fragment for the RESULT caches
 * (the disk-backed Arrow cellset cache, the in-flight {@link QueryCoalescer}
 * and anything else keyed through {@link org.saiku.service.cache.QueryCacheKey}).
 *
 * <p>Those caches were keyed by query + cube version + the caller's Saiku
 * role set. That is sufficient for Mondrian role masking, but on a
 * {@code security.type=passthrough} datasource every user connects to the
 * warehouse with their OWN credentials, so two users with the same Saiku
 * roles can legitimately see different rows — yet they share one cache key
 * and the second user receives the first user's cellset. The AI schema cache
 * already keys per user for the same datasource class (the
 * {@code AiVisibilityContext} rule, saiku#2326); this seam reuses that rule
 * for the result caches.
 *
 * <p>Implemented in the web layer by {@code SecurityAwareConnectionManager},
 * which owns the Spring Security context and the datasource definitions.
 *
 * <p>Fail-closed contract: if the implementor cannot classify the datasource
 * (unknown or decorated name, repository hiccup) it must return a
 * per-identity fragment rather than the shared empty one, so an unresolvable
 * datasource can at worst fragment the caches — never share a cellset across
 * callers.
 */
public interface QueryCacheIdentityContext {

    /**
     * A stable fragment distinguishing "which warehouse identity produced this
     * result" on the named datasource.
     *
     * @param connectionName the datasource/connection name as referenced by the
     *        caller (may carry the workspace decoration)
     * @return a non-empty per-caller fragment (e.g. {@code "user:<identity>"})
     *         when results on this datasource can differ per warehouse account —
     *         pass-through credentials, or an unresolvable datasource under the
     *         fail-closed contract; an empty string when the caller's identity
     *         does NOT distinguish results (no security, or role-masked only —
     *         the role set is already in the key). Must be stable for the same
     *         caller and differ for callers whose warehouse visibility differs.
     */
    String identityKey(String connectionName);
}
