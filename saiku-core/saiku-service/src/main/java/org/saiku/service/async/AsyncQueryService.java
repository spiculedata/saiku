/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.async;

import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.olap4j.CellSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Wraps {@link ThinQueryService} with a fire-and-poll asynchronous execution
 * lifecycle so clients can kick off long-running MDX queries, poll for status,
 * then stream the result only when it's ready.
 *
 * <p>Threading model:
 * <ul>
 *   <li>A {@link ThreadPoolExecutor} (default 4/16/200) runs the actual OLAP
 *       work off-request-thread.</li>
 *   <li>A scheduled sweeper evicts completed handles: those whose result was
 *       already fetched and are &gt;5 min idle, or (failsafe) any handle
 *       terminal for &gt;30 min.</li>
 *   <li>Cancellation interrupts the future and best-effort cancels the OLAP
 *       statement via {@link ThinQueryService#cancel(String)} (off the caller
 *       thread to avoid blocking the request).</li>
 * </ul>
 *
 * <p>The synchronous {@code /execute} path is untouched — this class does not
 * sit in the middle of it.
 */
public class AsyncQueryService {

    private static final Logger log = LoggerFactory.getLogger(AsyncQueryService.class);

    private static final long SWEEP_PERIOD_SECONDS = 60L;
    private static final long IDLE_AFTER_FETCH_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long IDLE_FAILSAFE_MS = TimeUnit.MINUTES.toMillis(30);

    private ThinQueryService thinQueryService;

    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService sweeper;
    /**
     * Dedicated single-thread executor for best-effort statement cancels.
     * Keeping this off the main work executor means a saturated queue can't
     * prevent us from tearing down a running MDX statement — the whole point
     * of cancel() is to drain the work, so it must never queue behind it.
     */
    private final ExecutorService cancelExecutor;

    private final ConcurrentHashMap<String, AsyncQueryHandle> handles = new ConcurrentHashMap<>();

    public AsyncQueryService() {
        this(defaultExecutor());
    }

    public AsyncQueryService(ThreadPoolExecutor executor) {
        this.executor = executor;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(namedDaemon("saiku-async-sweeper"));
        this.sweeper.scheduleAtFixedRate(this::sweep, SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
        this.cancelExecutor = Executors.newSingleThreadExecutor(namedDaemon("saiku-async-cancel"));
    }

    public void setThinQueryService(ThinQueryService thinQueryService) {
        this.thinQueryService = thinQueryService;
    }

    public ThinQueryService getThinQueryService() {
        return thinQueryService;
    }

    private static ThreadPoolExecutor defaultExecutor() {
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200), namedDaemon("saiku-async-query"));
        tpe.allowCoreThreadTimeOut(false);
        return tpe;
    }

    private static ThreadFactory namedDaemon(final String base) {
        final AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, base + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Submit a query for async execution. Returns immediately with a handle
     * whose status is {@code PENDING} until the worker picks it up.
     */
    public AsyncQueryHandle submit(final ThinQuery query) {
        return submit(query, null);
    }

    /**
     * Submit a query for async execution, propagating the caller's Spring
     * {@link RequestAttributes} to the worker thread. This is required when
     * {@link #thinQueryService} is a session-scoped bean (as in the default
     * webapp wiring): the executor thread has no HTTP request/session, so
     * resolving the scoped proxy without propagation throws
     * "Scope 'session' is not active for the current thread".
     *
     * <p>TODO: if the submitting HTTP request finishes and its session is
     * invalidated before the worker runs, the scoped proxy will still fail.
     * We rely on the client polling, which keeps the session alive.
     */
    public AsyncQueryHandle submit(final ThinQuery query, final RequestAttributes requestAttributes) {
        if (thinQueryService == null) {
            throw new IllegalStateException("AsyncQueryService.thinQueryService not wired — cannot submit");
        }
        final String id = UUID.randomUUID().toString();
        final AsyncQueryHandle handle = new AsyncQueryHandle(id, query, currentPrincipal());
        handles.put(id, handle);

        final CompletableFuture<CellSet> fut;
        try {
            fut = CompletableFuture.supplyAsync(
                    () -> {
                        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
                        if (requestAttributes != null) {
                            RequestContextHolder.setRequestAttributes(requestAttributes, true);
                        }
                        try {
                            handle.compareAndSetStatus(
                                    AsyncQueryHandle.Status.PENDING, AsyncQueryHandle.Status.RUNNING);
                            thinQueryService.execute(query);
                            // ThinQueryService.execute stashes the CellSet into its
                            // per-query context keyed by the query name; fetch it.
                            return thinQueryService.getContext(query.getName()).getOlapResult();
                        } finally {
                            if (requestAttributes != null) {
                                if (previous != null) {
                                    RequestContextHolder.setRequestAttributes(previous);
                                } else {
                                    RequestContextHolder.resetRequestAttributes();
                                }
                            }
                        }
                    },
                    executor);
        } catch (RejectedExecutionException rex) {
            // Executor queue was full / already shut down — we put the handle in
            // the map before submit, so drop it to avoid a leaked PENDING entry
            // that would only be GC'd by the failsafe sweeper 30 min later.
            handles.remove(id);
            throw rex;
        }

        fut.whenComplete((cellSet, err) -> {
            handle.touch();
            if (err != null) {
                // CompletableFuture wraps in CompletionException — unwrap.
                Throwable root = err.getCause() != null ? err.getCause() : err;
                if (handle.getStatus() != AsyncQueryHandle.Status.CANCELLED) {
                    handle.setStatus(AsyncQueryHandle.Status.FAILED);
                    handle.setErrorMessage(
                            root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
                }
                log.debug("Async query {} failed: {}", id, root.toString());
            } else {
                // Race window: cancel() may have flipped the handle to CANCELLED
                // milliseconds before the query actually finished. In that case
                // the successful CellSet is stranded — close it now rather than
                // leaving it pinned until the sweeper evicts the handle.
                if (handle.getStatus() == AsyncQueryHandle.Status.CANCELLED) {
                    if (cellSet != null) {
                        try {
                            cellSet.close();
                        } catch (Exception closeEx) {
                            log.debug("Orphaned CellSet close for {} failed: {}", id, closeEx.toString());
                        }
                    }
                } else {
                    handle.compareAndSetStatus(AsyncQueryHandle.Status.RUNNING, AsyncQueryHandle.Status.DONE);
                }
            }
        });

        handle.setFuture(fut);
        return handle;
    }

    public AsyncQueryHandle get(String id) {
        AsyncQueryHandle h = handles.get(id);
        if (h != null) {
            h.touch();
        }
        return h;
    }

    /**
     * Owner-scoped lookup. Returns the handle for {@code id} only when it is
     * owned by {@code principal} (or {@code admin} is true); otherwise returns
     * {@code null} — indistinguishably from an unknown id, so callers can 404
     * on both and avoid leaking handle existence to a non-owner (IDOR fix,
     * #1165 audit-3).
     *
     * @param id the async handle id
     * @param principal the current caller's principal name ({@code null} if
     *     unauthenticated / no security context)
     * @param admin whether the caller holds an admin role and may bypass the
     *     ownership check
     */
    public AsyncQueryHandle getOwned(String id, String principal, boolean admin) {
        AsyncQueryHandle h = handles.get(id);
        if (h == null) {
            return null;
        }
        if (!admin && !h.isOwnedBy(principal)) {
            return null;
        }
        h.touch();
        return h;
    }

    /**
     * Owner-scoped cancel. Cancels only when the handle is owned by
     * {@code principal} (or {@code admin}); returns {@code false} on a missing
     * handle <em>or</em> an ownership mismatch so the resource can 404 on both.
     */
    public boolean cancelOwned(String id, String principal, boolean admin) {
        AsyncQueryHandle h = handles.get(id);
        if (h == null) {
            return false;
        }
        if (!admin && !h.isOwnedBy(principal)) {
            return false;
        }
        return cancel(id);
    }

    /**
     * Resolve the current caller's principal name from the Spring Security
     * context, or {@code null} when there is none (no authentication, or an
     * anonymous principal). Used to bind an owner to a handle at submit time.
     */
    public static String currentPrincipal() {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                            .getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return null;
            }
            if (auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
                return null;
            }
            String name = auth.getName();
            return (name == null || name.isEmpty()) ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Register a pre-built handle. Used by tests that need to exercise
     *  resource-layer logic against a known {@link AsyncQueryHandle.Status}
     *  without driving a real executor — production submission flows through
     *  {@link #submit(ThinQuery)}, never this. */
    public void register(AsyncQueryHandle h) {
        if (h == null) return;
        handles.put(h.getId(), h);
    }

    /**
     * Fetch the completed CellSet for {@code id}. Marks the handle as
     * result-fetched so the sweeper can evict it after the idle window.
     * Returns null if the handle is missing or not yet DONE.
     */
    public CellSet result(String id) {
        AsyncQueryHandle h = handles.get(id);
        if (h == null) {
            return null;
        }
        if (h.getStatus() != AsyncQueryHandle.Status.DONE) {
            return null;
        }
        CompletableFuture<CellSet> f = h.getFuture();
        if (f == null) {
            return null;
        }
        try {
            CellSet cs = f.getNow(null);
            if (cs != null) {
                h.markResultFetched();
            }
            return cs;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cancel a running/pending query. Cancels the future and schedules a
     * best-effort statement cancel on the executor so we don't block the
     * calling thread on the JDBC/OLAP driver.
     */
    public boolean cancel(final String id) {
        final AsyncQueryHandle h = handles.get(id);
        if (h == null) {
            return false;
        }
        h.setStatus(AsyncQueryHandle.Status.CANCELLED);
        CompletableFuture<CellSet> fut = h.getFuture();
        if (fut != null) {
            fut.cancel(true);
        }
        // Best-effort: close the underlying OLAP statement off-thread.
        final ThinQueryService tqs = this.thinQueryService;
        if (tqs != null && h.getQuery() != null && h.getQuery().getName() != null) {
            cancelExecutor.execute(() -> {
                try {
                    tqs.cancel(h.getQuery().getName());
                } catch (SQLException e) {
                    log.debug("Cancel for {} couldn't close statement: {}", id, e.toString());
                } catch (Exception e) {
                    log.debug("Cancel for {} failed: {}", id, e.toString());
                }
            });
        }
        return true;
    }

    /** Visible for tests / metrics. */
    public int size() {
        return handles.size();
    }

    void sweep() {
        try {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, AsyncQueryHandle>> it =
                    handles.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, AsyncQueryHandle> e = it.next();
                AsyncQueryHandle h = e.getValue();
                AsyncQueryHandle.Status s = h.getStatus();
                boolean terminal = s == AsyncQueryHandle.Status.DONE
                        || s == AsyncQueryHandle.Status.FAILED
                        || s == AsyncQueryHandle.Status.CANCELLED;
                if (!terminal) {
                    continue;
                }
                long idle = now - h.getLastAccessedAt();
                if (h.isResultFetched() && idle > IDLE_AFTER_FETCH_MS) {
                    it.remove();
                } else if (idle > IDLE_FAILSAFE_MS) {
                    it.remove();
                }
            }
        } catch (Exception ex) {
            log.warn("async-query sweeper hiccup: {}", ex.toString());
        }
    }

    /**
     * Shut the executor down. Invoked both by the Spring bean's
     * {@code destroy-method="shutdown"} wiring and the {@link PreDestroy}
     * annotation, so it also fires outside Spring-XML lifecycles
     * (e.g. standalone tests, embedded Jetty runs).
     */
    @PreDestroy
    public void shutdown() {
        log.info("AsyncQueryService shutting down — {} outstanding handles", handles.size());
        sweeper.shutdownNow();
        executor.shutdownNow();
        cancelExecutor.shutdownNow();
    }
}
