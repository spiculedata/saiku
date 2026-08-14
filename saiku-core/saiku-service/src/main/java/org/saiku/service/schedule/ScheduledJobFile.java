/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * On-disk record for a periodic-send job (saiku#1809, PR1 — dormant job model + file-backed store,
 * <b>no engine, no REST, no send capability, no scheduling threads</b>).
 *
 * <p>Persisted one-per-id as {@code ${saiku.home}/jobs/<jobId>.json} by {@link JobStore}. Every
 * persisted field has an explicit Java field so a Jackson round-trip never silently drops config
 * (the dashboard-save drop-on-reload lesson). This POJO is deliberately NOT the API response type —
 * {@link ScheduledJobView} is the read-safe projection.
 *
 * <p><b>No secrets on disk.</b> {@link #payload} is an opaque JSON blob describing what a future
 * engine would send (which dashboard, which recipients hint, etc). It must NOT carry credentials or
 * tokens — those resolve from the mail-config / connection layers at send time. PR1 stores and reads
 * it verbatim and does nothing with it.
 *
 * <p>The run-bookkeeping fields ({@link #lastRunAt}, {@link #nextRunAt}, {@link #lastStatus}, {@link
 * #lastError}, {@link #consecutiveFailures}, {@link #cooldownUntil}) exist so a later engine slice can
 * record outcomes without a file-format migration. PR1 never writes them — there is nothing that
 * runs a job.
 *
 * <p>Unknown properties are ignored so a newer on-disk file written by a future version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduledJobFile {

    /** Opaque URL-safe random id; also the store filename stem. */
    private String id;

    /** Username that owns the job (the identity a future run would execute under). */
    private String ownerUsername;

    /** Roles the owner held when the job was created — the data-scope a future run would use. */
    private List<String> ownerRolesSnapshot;

    /** When the job is due to run (see {@link JobSchedule}). Never fired in PR1. */
    private JobSchedule schedule;

    /** Opaque discriminator, e.g. {@code EMAIL_SUBSCRIPTION} / {@code THRESHOLD_ALERT} / {@code WEBHOOK_DIGEST}. */
    private String type;

    /** Opaque JSON payload describing what would be sent. NO secrets. Stored verbatim. */
    private Map<String, Object> payload;

    /** Whether the job is active. PR1 has no scheduler, so this gates nothing yet. */
    private boolean enabled = true;

    /** Epoch millis when the job was created. */
    private long createdAt;

    /** Epoch millis of the last run, or 0 if never run. Unused in PR1. */
    private long lastRunAt;

    /** Epoch millis of the next scheduled run, or 0 if unscheduled. Unused in PR1. */
    private long nextRunAt;

    /** Outcome of the last run, or null if never run. Unused in PR1. */
    private JobStatus lastStatus;

    /** Short sanitized error from the last failed run, or null. Never a stack trace / secret. Unused in PR1. */
    private String lastError;

    /** Number of consecutive failed runs — for backoff/cooldown in a later slice. Unused in PR1. */
    private int consecutiveFailures;

    /** Epoch millis until which the job is in cooldown (0 = none). Unused in PR1. */
    private long cooldownUntil;

    public ScheduledJobFile() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public List<String> getOwnerRolesSnapshot() {
        return ownerRolesSnapshot;
    }

    public void setOwnerRolesSnapshot(List<String> ownerRolesSnapshot) {
        this.ownerRolesSnapshot = ownerRolesSnapshot;
    }

    public JobSchedule getSchedule() {
        return schedule;
    }

    public void setSchedule(JobSchedule schedule) {
        this.schedule = schedule;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(long lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(long nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public JobStatus getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(JobStatus lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public long getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(long cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }
}
