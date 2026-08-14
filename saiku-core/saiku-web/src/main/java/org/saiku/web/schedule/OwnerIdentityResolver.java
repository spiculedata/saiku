/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.schedule;

/**
 * Resolves a job owner's identity fresh from the authoritative user store at run time (saiku#1809,
 * PR3 — owner-identity execution).
 *
 * <p>This is the seam {@link OwnerIdentityJobRunner} calls, immediately before impersonating, to
 * answer two fail-closed questions about {@code ownerUsername}:
 *
 * <ol>
 *   <li>Does the owner still EXIST and is the account ENABLED? (If not → the job must not run.)</li>
 *   <li>What are the owner's CURRENT roles? (Re-resolved live — never the persisted
 *       {@code ownerRolesSnapshot}, which can stale-grant a role an admin already revoked.)</li>
 * </ol>
 *
 * <p>Kept as an interface so the runner is unit-testable with a fake resolver and so the DAO-backed
 * production wiring (PR4) is swappable. Implementations MUST fail closed: any doubt about the owner's
 * existence/enabled-state or roles resolves to {@link OwnerIdentity#absent()} rather than a
 * best-effort grant.
 */
@FunctionalInterface
public interface OwnerIdentityResolver {

    /**
     * Resolve {@code username} to a fresh {@link OwnerIdentity}.
     *
     * @param username the job owner's username (server-minted on the job record, never client input)
     * @return {@link OwnerIdentity#present(java.util.List)} with the owner's current roles when the
     *     account exists and is enabled; {@link OwnerIdentity#absent()} otherwise (missing, disabled,
     *     blank username, or any resolution error). Never {@code null}.
     */
    OwnerIdentity resolve(String username);
}
