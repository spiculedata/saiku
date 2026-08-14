/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * When a {@link ScheduledJobFile} is due to run — the typed schedule value persisted inside a job
 * record (saiku#1809, PR1: dormant job model only, no engine).
 *
 * <p>PR1 supports only {@link Kind#FIXED_INTERVAL} (run every {@link #intervalMillis} ms). A nullable
 * {@link #cronExpression} field is carried on disk now so a later slice can add cron scheduling
 * without a file-format migration; PR1 never reads it.
 *
 * <p>This class holds no behaviour beyond being a plain data carrier — there is no clock, no timer,
 * and nothing here computes a next-run or fires anything. It is inert infrastructure.
 *
 * <p>Unknown properties are ignored so a newer on-disk record still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobSchedule {

    /** The kind of schedule. PR1 ships {@link #FIXED_INTERVAL} only. */
    public enum Kind {
        /** Run every {@link JobSchedule#intervalMillis} milliseconds. */
        FIXED_INTERVAL,
        /** Reserved for a later slice — cron-driven scheduling. Not honoured in PR1. */
        CRON
    }

    private Kind kind = Kind.FIXED_INTERVAL;

    /** Interval in millis for {@link Kind#FIXED_INTERVAL}; ignored for other kinds. */
    private long intervalMillis;

    /** IANA timezone id (e.g. {@code "Europe/London"}) the schedule is interpreted in; may be null. */
    private String timezone;

    /** Cron expression for {@link Kind#CRON}; nullable and unused in PR1 (forward-compat placeholder). */
    private String cronExpression;

    public JobSchedule() {}

    public JobSchedule(Kind kind, long intervalMillis, String timezone) {
        this.kind = kind;
        this.intervalMillis = intervalMillis;
        this.timezone = timezone;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }
}
