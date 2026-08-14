/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import java.util.List;

/**
 * Read-safe projection of a {@link ScheduledJobFile} for a future REST layer (saiku#1809). Kept
 * distinct from the on-disk type (mirrors {@code MailConfigView} vs {@code MailConfigFile}) so the
 * response shape can never accidentally carry the opaque {@link ScheduledJobFile#getPayload() payload}
 * blob — which may describe recipients / targets an owner shouldn't be forced to expose, and which is
 * where any secret would leak if one were ever (wrongly) stored there.
 *
 * <p>The payload is <b>omitted entirely</b>; a {@link #payloadKeys} list surfaces only the top-level
 * key names so a UI can render "configured" without disclosing values. Everything else here is
 * non-sensitive run/schedule metadata.
 *
 * <p>PR1 ships no REST resource — this record exists so the file-backed store has its redacted
 * counterpart ready, and so the shape is fixed before an endpoint depends on it.
 */
public record ScheduledJobView(
        String id,
        String ownerUsername,
        List<String> ownerRolesSnapshot,
        JobSchedule schedule,
        String type,
        List<String> payloadKeys,
        boolean enabled,
        long createdAt,
        long lastRunAt,
        long nextRunAt,
        JobStatus lastStatus,
        String lastError,
        int consecutiveFailures,
        long cooldownUntil) {

    /** Build a redacted view from an on-disk record, dropping the payload values (keys only). */
    public static ScheduledJobView of(ScheduledJobFile f) {
        List<String> keys =
                f.getPayload() == null ? List.of() : List.copyOf(f.getPayload().keySet());
        return new ScheduledJobView(
                f.getId(),
                f.getOwnerUsername(),
                f.getOwnerRolesSnapshot() == null ? List.of() : List.copyOf(f.getOwnerRolesSnapshot()),
                f.getSchedule(),
                f.getType(),
                keys,
                f.isEnabled(),
                f.getCreatedAt(),
                f.getLastRunAt(),
                f.getNextRunAt(),
                f.getLastStatus(),
                f.getLastError(),
                f.getConsecutiveFailures(),
                f.getCooldownUntil());
    }
}
