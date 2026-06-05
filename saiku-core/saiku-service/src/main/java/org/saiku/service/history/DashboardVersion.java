/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.history;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One archived dashboard version (issue #947). Stored as a single JSON line in
 * a per-dashboard {@code saiku-history-*.jsonl} file (see
 * {@link DashboardHistoryService}). {@link #dashboard} holds the full
 * {@code .saikudash} JSON of that version; list responses omit it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardVersion {

    /** Stable id (UUID). */
    public String version;

    /** Epoch millis the version was archived. */
    public long createdAt;

    /** Username whose save replaced this state (the prior author of record). */
    public String author;

    /** The full dashboard JSON snapshot. */
    public String dashboard;

    public DashboardVersion() {}
}
