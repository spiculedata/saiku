/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.schedule;

import java.util.concurrent.atomic.AtomicReference;
import org.saiku.service.schedule.JobHandler;
import org.saiku.service.schedule.JobRunner;
import org.saiku.service.schedule.OwnerUnavailableException;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The owner-identity implementation of the {@link JobRunner} seam (saiku#1809, PR3). It runs a
 * scheduled job's handler AS the job's owner, so the run has exactly the owner's RLS / privilege scope
 * — no more, no less.
 *
 * <p><b>Why it lives in {@code saiku-web}:</b> {@link SessionService#runAs} (the impersonation
 * primitive) lives here, while the {@link JobRunner} interface it implements lives in {@code
 * saiku-service}. This class bridges the two, so the engine stays identity-agnostic.
 *
 * <p><b>Trusted caller.</b> {@code SessionService.runAs}'s header warns: never call it from a path
 * driven by untrusted input that picks the username/roles. A scheduler is the opposite of that — job
 * records are <b>server-minted</b>; {@code ownerUsername} comes off the record, never off a request.
 * This mirrors the share-link precedent ({@code ShareViewResource}), which the warning explicitly
 * blesses.
 *
 * <p><b>Fail-closed guards, applied BEFORE impersonating:</b>
 *
 * <ol>
 *   <li><b>Owner exists + enabled.</b> The owner is re-resolved from the authoritative user store via
 *       {@link OwnerIdentityResolver}. If the account is gone or disabled the run does NOT proceed —
 *       it throws {@link OwnerUnavailableException}, which the engine turns into a SKIPPED run + an
 *       immediate auto-disable (a job for a deleted user stops firing at once).</li>
 *   <li><b>Roles re-resolved live.</b> The run context is built from the owner's CURRENT roles
 *       (resolved at run time), NOT from the persisted {@code ownerRolesSnapshot}. A role an admin
 *       revoked is absent from the run immediately; the snapshot is only ever an audit/display
 *       fallback, never a grant.</li>
 *   <li><b>No escalation.</b> The run context is EXACTLY the owner's current roles. There is no code
 *       path that runs a job as an admin, an ambient superuser, or anyone but its own owner.</li>
 * </ol>
 */
public final class OwnerIdentityJobRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerIdentityJobRunner.class);

    private final SessionService sessionService;
    private final OwnerIdentityResolver ownerResolver;

    public OwnerIdentityJobRunner(SessionService sessionService, OwnerIdentityResolver ownerResolver) {
        if (sessionService == null || ownerResolver == null) {
            throw new IllegalArgumentException("sessionService and ownerResolver are required");
        }
        this.sessionService = sessionService;
        this.ownerResolver = ownerResolver;
    }

    @Override
    public void run(ScheduledJobFile job, JobHandler handler) throws Exception {
        if (job == null || handler == null) {
            throw new IllegalArgumentException("job and handler are required");
        }
        final String owner = job.getOwnerUsername();
        if (owner == null || owner.isBlank()) {
            // No owner on the record — there is no identity to run as. Fail closed.
            throw new OwnerUnavailableException("Job " + job.getId() + " has no owner — refusing to run");
        }

        // (1) Re-resolve the owner fresh from the authoritative store — existence, enabled-state, and
        // CURRENT roles. Do this BEFORE impersonating so a missing/disabled owner never reaches runAs.
        OwnerIdentity identity = ownerResolver.resolve(owner);
        if (identity == null || !identity.present()) {
            throw new OwnerUnavailableException(
                    "Owner '" + owner + "' of job " + job.getId() + " is missing or disabled — refusing to run");
        }

        // (2) The run context is EXACTLY the owner's current roles — never the stale snapshot, never an
        // admin/ambient token. A revoked role is already absent from currentRoles().
        final java.util.List<String> runRoles = identity.currentRoles();
        log.debug("Running job {} as owner '{}' with {} current role(s)", job.getId(), owner, runRoles.size());

        // (3) Impersonate for the duration of the handler call only; runAs restores the prior context
        // in its own finally. The handler may throw — propagate it (and any error) to the engine, which
        // records a sanitized failure. runAs takes a Supplier, so capture a checked throwable and
        // rethrow it after the impersonation window closes.
        final AtomicReference<Exception> thrown = new AtomicReference<>();
        sessionService.runAs(owner, runRoles, () -> {
            try {
                handler.handle(job);
            } catch (Exception e) {
                thrown.set(e);
            }
            return null;
        });
        Exception e = thrown.get();
        if (e != null) {
            throw e;
        }
    }
}
