/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

/**
 * Outcome of a job's most recent run, recorded on {@link ScheduledJobFile#getLastStatus()}
 * (saiku#1809, PR1). PR1 never sets any of these — there is no engine to run a job — but the field
 * exists on disk now so a later engine slice writes to it without a file-format change.
 */
public enum JobStatus {
    /** The last run completed successfully. */
    OK,
    /** The last run threw / reported an error. */
    FAILED,
    /** The run was skipped (disabled, in cooldown, not yet due). */
    SKIPPED
}
