/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.olap4j.CellSet;
import org.saiku.olap.query2.ThinQuery;

/**
 * Handle for an asynchronous query submission. Tracks lifecycle state and
 * references to the underlying {@link CompletableFuture} that will eventually
 * complete with a {@link CellSet}.
 *
 * <p>This is a mutable data holder — intentionally simple. The owning
 * {@link AsyncQueryService} is responsible for all state transitions.
 */
public class AsyncQueryHandle {

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    private final String id;
    private final long submittedAt;
    private final ThinQuery query;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private volatile CompletableFuture<CellSet> future;
    private volatile String errorMessage;
    private volatile long lastAccessedAt;
    private volatile boolean resultFetched;

    /**
     * Principal name of the user who submitted this query. Used by the
     * resource layer to enforce that only the submitting user (or an admin)
     * may poll status, fetch the result, cancel, or drill through the handle —
     * UUID ids are unguessable but can leak (logs, Referer), so id-only lookup
     * is an IDOR. {@code null} means "no owner bound" (no security context at
     * submit time, e.g. unit tests), which only matches a {@code null} caller.
     */
    private volatile String owner;

    public AsyncQueryHandle(String id, ThinQuery query) {
        this(id, query, null);
    }

    public AsyncQueryHandle(String id, ThinQuery query, String owner) {
        this.id = id;
        this.query = query;
        this.owner = owner;
        this.submittedAt = System.currentTimeMillis();
        this.lastAccessedAt = this.submittedAt;
    }

    public String getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    /**
     * True when {@code principal} is allowed to access this handle: the owner
     * matches exactly (including both {@code null}). Admin bypass is decided by
     * the caller, not here.
     */
    public boolean isOwnedBy(String principal) {
        return java.util.Objects.equals(this.owner, principal);
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    public ThinQuery getQuery() {
        return query;
    }

    public Status getStatus() {
        return status.get();
    }

    public void setStatus(Status s) {
        status.set(s);
    }

    public boolean compareAndSetStatus(Status expected, Status next) {
        return status.compareAndSet(expected, next);
    }

    public CompletableFuture<CellSet> getFuture() {
        return future;
    }

    public void setFuture(CompletableFuture<CellSet> f) {
        this.future = f;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void touch() {
        this.lastAccessedAt = System.currentTimeMillis();
    }

    public boolean isResultFetched() {
        return resultFetched;
    }

    public void markResultFetched() {
        this.resultFetched = true;
        touch();
    }
}
