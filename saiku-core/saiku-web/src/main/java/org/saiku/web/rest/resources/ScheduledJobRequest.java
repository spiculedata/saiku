/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import java.util.Map;
import org.saiku.service.schedule.JobSchedule;

/**
 * Request body for {@code POST /saiku/admin/jobs} — create a periodic-send job (saiku#1809 PR4).
 *
 * <p>The job is created OWNED BY THE CALLING ADMIN (server-minted owner + roles snapshot); a client
 * can NOT set the owner. The {@code payload} is an opaque JSON blob describing what a future send
 * handler would do — it must carry NO secrets (credentials resolve from the mail-config / connection
 * layers at send time). PR4 registers no real send handler, so a created job cannot send anything.
 */
public class ScheduledJobRequest {

    private JobSchedule schedule;
    private String type;
    private Map<String, Object> payload;
    private boolean enabled = true;

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
}
