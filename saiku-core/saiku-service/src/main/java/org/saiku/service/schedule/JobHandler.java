/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

/**
 * Handler SPI for a periodic-send job (saiku#1809, PR2 — engine + handler SPI, still
 * <b>runtime-dormant</b>: no Spring wiring, no owner-identity execution, no real send handler).
 *
 * <p>The {@link JobScheduler} dispatches a due {@link ScheduledJobFile} to the handler registered
 * for its {@link ScheduledJobFile#getType() type}. A handler does the actual work of a job (in a
 * later slice: render a dashboard and email/Slack/Teams-send it). PR2 ships only the interface and a
 * {@linkplain NoOpJobHandler no-op implementation} — nothing here can send anything.
 *
 * <p><b>Threading:</b> {@link #handle(ScheduledJobFile)} runs on a scheduler worker thread, never on
 * a request thread. Implementations must be thread-safe; the scheduler guarantees a single job is
 * never run concurrently with itself (per-job overlap guard), but two <em>different</em> jobs sharing
 * a handler instance can run in parallel.
 *
 * <p><b>Errors:</b> throw to signal failure. The scheduler records a short, sanitized message (never a
 * stack trace, never a secret), increments the failure count, and applies exponential backoff /
 * auto-disable. Do NOT put credentials or tokens into exception messages.
 */
@FunctionalInterface
public interface JobHandler {

    /**
     * Execute one run of {@code job}. Blocks until the run completes. Throw to signal failure.
     *
     * @param job the due job to run; never {@code null}
     * @throws Exception on any failure — the scheduler catches it and records a sanitized outcome
     */
    void handle(ScheduledJobFile job) throws Exception;
}
