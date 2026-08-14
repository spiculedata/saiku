/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The run-time resolution of a job owner's identity (saiku#1809, PR3 — owner-identity execution).
 *
 * <p>A snapshot resolved fresh from the user store at run time by an {@link OwnerIdentityResolver},
 * used by {@link OwnerIdentityJobRunner} to decide whether — and under exactly which roles — a
 * scheduled job may run. It carries three facts:
 *
 * <ul>
 *   <li>{@link #present()} — the owner account still exists AND is enabled. If false, the job MUST NOT
 *       run (fail-closed) — no run context is ever built from a missing/disabled owner.</li>
 *   <li>{@link #currentRoles()} — the owner's CURRENT roles, re-resolved at run time. These, and only
 *       these, become the run's granted authorities. A role an admin revoked is absent here
 *       immediately, so a stale {@code ownerRolesSnapshot} can never stale-grant it.</li>
 * </ul>
 *
 * <p>Immutable and defensively copied; never holds credentials.
 */
public final class OwnerIdentity {

    private final boolean present;
    private final List<String> currentRoles;

    private OwnerIdentity(boolean present, List<String> currentRoles) {
        this.present = present;
        this.currentRoles = currentRoles == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(currentRoles));
    }

    /**
     * The owner exists and is enabled — a run may proceed under {@link #currentRoles()}.
     *
     * @param currentRoles the owner's current roles (re-resolved at run time); may be empty, never a
     *     stale snapshot
     */
    public static OwnerIdentity present(List<String> currentRoles) {
        return new OwnerIdentity(true, currentRoles);
    }

    /** The owner is missing or disabled — fail closed, the job must not run and should auto-disable. */
    public static OwnerIdentity absent() {
        return new OwnerIdentity(false, Collections.emptyList());
    }

    /** True iff the owner account still exists and is enabled. */
    public boolean present() {
        return present;
    }

    /** The owner's current roles (unmodifiable). Empty when absent. */
    public List<String> currentRoles() {
        return currentRoles;
    }
}
