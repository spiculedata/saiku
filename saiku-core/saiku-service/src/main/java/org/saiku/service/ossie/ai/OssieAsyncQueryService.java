/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.ossie.OssieQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight async wrapper around {@link OssieQueryService} so the AI resource can hand out a
 * {@code queryId} and poll status. Parallel to the MDX-side {@code AsyncQueryService} but
 * scoped to Ossie's CellDataSet output — MDX's version is built around olap4j's {@code CellSet}
 * and can't be reused.
 *
 * <p>Ownership: each handle carries the caller's principal name (typically the Spring Security
 * username). {@link #getOwned(String, String)} returns null when the requested queryId isn't
 * owned by the caller — same IDOR-fix pattern MDX's async uses (saiku#906).
 *
 * <p>Retention: completed handles are kept in memory for {@link #RESULT_TTL} so a client can
 * poll immediately-after-DONE without racing GC. The evict pass runs on every read; there's no
 * background thread. For scale we can add one, but the AI use case is one query at a time per
 * principal so contention on the map is negligible.
 */
public class OssieAsyncQueryService {

    private static final Logger log = LoggerFactory.getLogger(OssieAsyncQueryService.class);

    /** Keep completed handles this long so a slow-polling client still gets the result. */
    static final Duration RESULT_TTL = Duration.ofMinutes(10);

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final OssieQueryService queryService;

    public OssieAsyncQueryService(OssieQueryService queryService) {
        this.queryService = queryService;
        this.executor = Executors.newFixedThreadPool(4, new NamedThreadFactory());
    }

    /**
     * Submit a shelf-state query for async execution. Returns immediately with a handle
     * carrying the queryId; the actual execution runs on the internal thread pool.
     *
     * @param tq            the pre-validated ThinQuery — validation should have already run in
     *                      the resource layer so failures here are execution failures, not
     *                      wire-shape failures.
     * @param principalName the caller's principal name for ownership checks on later polls.
     */
    public Handle submit(ThinQuery tq, String principalName) {
        String id = "ossie-ai-async-" + UUID.randomUUID().toString().substring(0, 8);
        Handle h = new Handle(id, principalName, tq, Instant.now());
        handles.put(id, h);
        h.future = executor.submit(() -> {
            try {
                h.status = Status.RUNNING;
                CellDataSet cds = queryService.execute(tq);
                h.result = cds;
                h.status = Status.DONE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                h.status = Status.CANCELLED;
                h.errorMessage = "interrupted";
            } catch (Exception e) {
                log.warn("Ossie async execution failed for {}: {}", id, e.getMessage());
                h.status = Status.FAILED;
                h.errorMessage = e.getMessage();
            }
            h.completedAt = Instant.now();
            return h;
        });
        return h;
    }

    /**
     * Look up a handle, but only return it if owned by the given principal (or if the caller is
     * admin). Returns null on unknown id AND on ownership mismatch — same shape MDX uses so a
     * 404 doesn't leak id existence.
     */
    public Handle getOwned(String queryId, String principalName, boolean isAdmin) {
        evictExpired();
        Handle h = handles.get(queryId);
        if (h == null) return null;
        if (isAdmin || (principalName != null && principalName.equals(h.principalName))) return h;
        return null;
    }

    /**
     * Cancel the future. Returns true if the handle existed and was owned by the caller — the
     * cancellation itself is best-effort (Callable checks interruption between statements).
     */
    public boolean cancel(String queryId, String principalName, boolean isAdmin) {
        Handle h = getOwned(queryId, principalName, isAdmin);
        if (h == null) return false;
        if (h.status == Status.DONE || h.status == Status.FAILED || h.status == Status.CANCELLED) {
            return true;
        }
        h.status = Status.CANCELLED;
        if (h.future != null) h.future.cancel(true);
        h.completedAt = Instant.now();
        return true;
    }

    /**
     * Sweep expired handles. Called opportunistically on every getOwned so we don't need a
     * background thread. Cheap: the map is small, the check is O(n) over the entries.
     */
    private void evictExpired() {
        Instant now = Instant.now();
        Set<String> stale = new HashSet<>();
        for (Map.Entry<String, Handle> e : handles.entrySet()) {
            Handle h = e.getValue();
            if (h.completedAt != null && Duration.between(h.completedAt, now).compareTo(RESULT_TTL) > 0) {
                stale.add(e.getKey());
            }
        }
        for (Iterator<String> it = stale.iterator(); it.hasNext(); ) {
            handles.remove(it.next());
        }
    }

    /** For tests + graceful shutdown. Ossie async is not restart-safe — running queries drop. */
    public void shutdown() {
        executor.shutdownNow();
    }

    /** Handle state — one per submitted query. */
    public static class Handle {
        private final String id;
        private final String principalName;
        private final ThinQuery query;
        private final Instant submittedAt;
        private volatile Status status = Status.PENDING;
        private volatile Instant completedAt;
        private volatile CellDataSet result;
        private volatile String errorMessage;
        private volatile Future<?> future;

        Handle(String id, String principalName, ThinQuery query, Instant submittedAt) {
            this.id = id;
            this.principalName = principalName;
            this.query = query;
            this.submittedAt = submittedAt;
        }

        public String getId() {
            return id;
        }

        public String getPrincipalName() {
            return principalName;
        }

        public ThinQuery getQuery() {
            return query;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public Status getStatus() {
            return status;
        }

        public Instant getCompletedAt() {
            return completedAt;
        }

        public CellDataSet getResult() {
            return result;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    /**
     * Test hook — expose the aggregate handle count so a shutdown hook / metrics endpoint can
     * report. Not on any public API path yet.
     */
    public int handleCount() {
        return handles.size();
    }

    /** Convenience for test IDs. */
    static List<String> statusesFor(String id) {
        return List.of(id);
    }

    /** Convenience for test IDs. Not on any resource surface. */
    static Map<String, Object> handleMap(Object... kv) {
        return Map.of();
    }

    /** Name the async threads so they show up cleanly in dumps. */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ossie-ai-async-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
