/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * saiku#2326 (CWE-284/524/863) — a stable fingerprint of what the CURRENT
 * caller may see on a datasource, used to scope the AI schema cache.
 *
 * <p>{@link OlapAiCubeMetadataService} builds every {@link AiSchema} through
 * the caller's role-applied connection, so a cached snapshot is only valid
 * for callers whose effective Mondrian role set (and, for credential
 * passthrough, whose principal) resolves to the same visibility. Without a
 * visibility-scoped key the first caller's snapshot — including hierarchies
 * and sample members another role must never see — was served to everyone,
 * and a caller denied the datasource entirely still got a cache hit because
 * the hit path never reached the role-scoped enumeration.
 *
 * <p>Implemented in the web layer by {@code SecurityAwareConnectionManager},
 * which owns the Spring Security context and the datasource definitions.
 *
 * <p>Fail-closed contract: for a datasource whose visibility the implementor
 * cannot resolve, return a per-identity key (never the shared key), so an
 * unresolvable datasource can at worst fragment the cache — never share a
 * snapshot across callers.
 */
public interface AiVisibilityContext {

    /**
     * A stable key for "what this caller may see on this datasource": e.g. the
     * caller's sorted resolved Mondrian role names, the caller's identity for a
     * credential-passthrough datasource, or a per-identity key when the
     * datasource cannot be resolved.
     *
     * @param connectionName the datasource/connection name as referenced by the
     *        caller (may carry the workspace decoration)
     * @return an empty string when schema visibility on this datasource does not
     *         vary by caller (not security-enabled) — the AI schema cache key
     *         then collapses to the role-less base key; a non-empty fingerprint
     *         otherwise. Must be stable for the same caller and differ for
     *         callers whose cube visibility differs.
     */
    String visibilityKey(String connectionName);
}
