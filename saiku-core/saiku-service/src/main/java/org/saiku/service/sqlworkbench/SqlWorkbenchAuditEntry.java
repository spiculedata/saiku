/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.sqlworkbench;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One audit record for a single SQL workbench execution (saiku#1107). Captures who ran what,
 * against which datasource, and the outcome — the "Notes" section of the issue calls out that
 * every run must be recorded, pairing with the (separate) audit log ticket.
 *
 * <p>Public fields + no-arg constructor so Jackson can round-trip it to/from the JSONL audit
 * file, mirroring {@link org.saiku.service.olap.ai.audit.AiAuditEntry}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SqlWorkbenchAuditEntry {

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_REJECTED = "rejected";
    public static final String OUTCOME_ERROR = "error";

    /** ISO-8601 UTC timestamp, second precision. */
    public String ts;
    /** Authenticated principal that ran the query. */
    public String user;
    /** Saiku datasource name/id the query ran against. */
    public String datasource;
    /** The SQL text as submitted. Deliberately kept (unlike the AI audit log, which withholds
     *  prompt content) — the whole point of this trail is "what SQL ran against the warehouse". */
    public String sql;
    /** Rows returned; null when the run never reached execution ({@code rejected}). */
    public Integer rowCount;
    /** Server-side wall-clock latency, in milliseconds. */
    public Long latencyMs;
    /** One of the {@code OUTCOME_*} constants. */
    public String outcome;
    /** Short, safe reason on a non-success outcome (never a raw stack trace). Null on success. */
    public String error;

    public SqlWorkbenchAuditEntry() {}
}
