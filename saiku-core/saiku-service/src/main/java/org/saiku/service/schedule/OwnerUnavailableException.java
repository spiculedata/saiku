/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

/**
 * Thrown by a {@link JobRunner} when a job's owner can no longer back a run — the owner account has
 * been deleted or disabled (saiku#1809, PR3 — owner-identity execution).
 *
 * <p>This is a distinct signal from a handler failure. A handler that throws means "the work failed,
 * retry with backoff"; an {@code OwnerUnavailableException} means "there is no longer a valid identity
 * to run as, so stop firing this job entirely". The {@link JobScheduler} recognises this type
 * specially: instead of the backoff-then-eventually-disable failure path, it marks the run {@link
 * JobStatus#SKIPPED} and <b>immediately auto-disables</b> the job, so a job for a deleted user does
 * not keep firing (and keep failing) until it happens to cross the consecutive-failure threshold.
 *
 * <p>The owner-identity runner throws this <b>before</b> impersonating — so a missing/disabled owner
 * never reaches {@code SessionService.runAs} and no handler runs. This is the fail-closed guard: no
 * job ever executes under a stale or absent identity.
 */
public class OwnerUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public OwnerUnavailableException(String message) {
        super(message);
    }
}
