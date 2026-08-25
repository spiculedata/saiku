/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.session;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.saiku.service.schema.generate.delta.DeltaReport;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * In-memory state for a single schema-generation workflow: holds the draft under construction, any
 * LLM suggestions, the applied op log, and the current pipeline stage.
 *
 * <p>Mutable by design. Not thread-safe: callers that share a session across threads must
 * serialise access externally (typical usage is single-session-per-request driven by the
 * {@link SchemaGenSessionStore}).
 *
 * <p>{@link #lastAccessedAt()} is bumped by {@link #appendOp(SuggestionOp)}; the store also touches
 * it on every {@code get()} to implement sliding-TTL expiry.
 */
public class SchemaGenSession {

    /** High-level pipeline state of the session. */
    public enum Stage {
        /** Created but no introspection yet. */
        PENDING,
        /** JDBC introspection in progress. */
        INTROSPECTING,
        /** Rule-based inference running. */
        INFERRING,
        /** LLM enrichment running. */
        ENRICHING,
        /** Draft ready; awaiting user review / op application. */
        READY,
        /** Mondrian schema persisted. */
        SAVED,
        /** Terminal failure — see session logs. */
        FAILED;
    }

    private final String id;
    private final String dataSourceId;
    private final Instant createdAt;
    private final Clock clock;
    private final List<SuggestionOp> opLog = new ArrayList<>();

    private DraftSchema draft;
    private SuggestionSet suggestions;
    private Stage stage;
    private Instant lastAccessedAt;
    private String failureMessage;
    private DeltaReport deltaReport;

    SchemaGenSession(String id, String dataSourceId, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.dataSourceId = Objects.requireNonNull(dataSourceId, "dataSourceId");
        this.clock = Objects.requireNonNull(clock, "clock");
        Instant now = clock.instant();
        this.createdAt = now;
        this.lastAccessedAt = now;
        this.stage = Stage.PENDING;
    }

    public String id() {
        return id;
    }

    public String dataSourceId() {
        return dataSourceId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }

    /** Package-private: the store bumps this on every successful {@code get()}. */
    void touch() {
        this.lastAccessedAt = clock.instant();
    }

    public DraftSchema draft() {
        return draft;
    }

    public void setDraft(DraftSchema draft) {
        this.draft = draft;
    }

    public SuggestionSet suggestions() {
        return suggestions;
    }

    public void setSuggestions(SuggestionSet suggestions) {
        this.suggestions = suggestions;
    }

    public Stage stage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    /**
     * Human-readable error message set by the orchestrator when the pipeline transitions to
     * {@link Stage#FAILED}. {@code null} in all non-failed states.
     */
    public String failureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    /**
     * Delta report produced during re-run mode: non-{@code null} when the orchestrator found a
     * prior sidecar for the data source and reconciled it against the fresh introspection. {@code
     * null} on first-run (no baseline available).
     */
    public DeltaReport deltaReport() {
        return deltaReport;
    }

    public void setDeltaReport(DeltaReport deltaReport) {
        this.deltaReport = deltaReport;
    }

    /** Mutable op log — applied suggestions + manual edits, in application order. */
    public List<SuggestionOp> opLog() {
        return opLog;
    }

    /** Append an op to the log and touch the access timestamp. */
    public void appendOp(SuggestionOp op) {
        opLog.add(Objects.requireNonNull(op, "op"));
        touch();
    }
}
