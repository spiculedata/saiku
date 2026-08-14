/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link JobHandler} that does nothing but log (saiku#1809, PR2). Placeholder / test handler while
 * the engine is <b>runtime-dormant</b> — it sends nothing, touches no external system, and always
 * succeeds. A real EMAIL_SUBSCRIPTION / WEBHOOK_DIGEST handler arrives in a later slice.
 */
public final class NoOpJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(NoOpJobHandler.class);

    @Override
    public void handle(ScheduledJobFile job) {
        log.debug(
                "NoOpJobHandler: pretend-ran job {} (type={}) — sends nothing",
                job == null ? null : job.getId(),
                job == null ? null : job.getType());
    }
}
