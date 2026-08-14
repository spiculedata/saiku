/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

/**
 * The handler-dispatch seam the {@link JobScheduler} calls to actually run a job (saiku#1809, PR2).
 *
 * <p>This exists as a one-method join point so a later slice (PR3) can swap in an owner-identity
 * implementation — one that establishes the job owner's security context via
 * {@code SessionService.runAs(job.getOwnerUsername(), job.getOwnerRolesSnapshot(), ...)} around the
 * handler call — <b>without rewriting the engine</b>. PR2 deliberately does NOT do that: the
 * {@linkplain #DIRECT default} just calls {@code handler.handle(job)} directly, on the caller's
 * thread, with no impersonation. No {@code SessionService}, no {@code runAs}, no {@code
 * SecurityContext} touches this PR.
 */
@FunctionalInterface
public interface JobRunner {

    /**
     * Run {@code job} through {@code handler}. Implementations may wrap the call (PR3: owner-identity)
     * but must ultimately invoke {@link JobHandler#handle(ScheduledJobFile)}.
     *
     * @throws Exception whatever the handler throws (propagated so the scheduler records it)
     */
    void run(ScheduledJobFile job, JobHandler handler) throws Exception;

    /**
     * PR2 default: invoke the handler directly with no owner-identity / impersonation. This is the
     * seam PR3 replaces.
     */
    JobRunner DIRECT = (job, handler) -> handler.handle(job);
}
