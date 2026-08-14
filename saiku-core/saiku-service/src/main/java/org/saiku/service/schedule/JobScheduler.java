/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic-send job engine (saiku#1809, PR2 — engine + handler SPI). <b>Runtime-dormant.</b> PR2
 * ships the engine but wires no Spring bean (see saiku-beans.xml — nothing starts a ticker in the
 * running app), registers no real send handler, and does NOT execute jobs under the owner's identity
 * (owner-identity {@code runAs} is deferred to PR3, behind the {@link JobRunner} seam). Nothing here
 * can send anything to anyone.
 *
 * <p><b>Threading model</b> (mirrors {@code AsyncQueryService}):
 * <ul>
 *   <li>A single daemon ticker ({@link #ticker}, {@code saiku-job-scheduler}) fires {@link #tick()}
 *       every {@link #TICK_PERIOD_SECONDS}s. Each tick scans {@link JobStore} for DUE jobs and
 *       submits each to the worker pool.</li>
 *   <li>A bounded worker {@link ThreadPoolExecutor} ({@code saiku-job-worker}) runs jobs
 *       off-ticker-thread so a slow handler never stalls the scan.</li>
 * </ul>
 *
 * <p><b>Due</b> = {@code enabled && nextRunAt <= now && cooldownUntil <= now}. A job whose prior run
 * is still in flight is SKIPPED (per-job overlap guard, {@link #running}) — a job is never run
 * concurrently with itself.
 *
 * <p><b>After a run</b> the engine writes {@code lastRunAt / lastStatus / lastError (short, sanitized)
 * / consecutiveFailures / cooldownUntil / nextRunAt} back via {@link JobStore#save}. On failure it
 * applies exponential backoff ({@link #BACKOFF_MILLIS}, capped) and, past {@link #MAX_FAILURES}
 * consecutive failures, auto-disables the job.
 *
 * <p><b>Testability:</b> time flows through an injected {@link Clock} — tests use a mutable clock and
 * call {@link #tick()} directly (like {@code AsyncQueryServiceTest} drives {@code sweep()}), never
 * relying on wall-clock timing. {@link #runNow(ScheduledJobFile)} performs one synchronous run so a
 * test can assert on the resulting bookkeeping deterministically.
 */
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    /** How often the ticker scans the store for due jobs. */
    static final long TICK_PERIOD_SECONDS = 30L;

    /** Consecutive failures after which a job auto-disables. */
    static final int MAX_FAILURES = 5;

    /**
     * Exponential backoff cooldown per consecutive-failure count (index = failures - 1), capped at
     * the last entry: 1m, 5m, 30m, then 1h for any further failure. Applied as
     * {@code cooldownUntil = now + backoff}.
     */
    static final long[] BACKOFF_MILLIS = {
        TimeUnit.MINUTES.toMillis(1),
        TimeUnit.MINUTES.toMillis(5),
        TimeUnit.MINUTES.toMillis(30),
        TimeUnit.HOURS.toMillis(1)
    };

    /** Max chars retained from a failure message — keeps lastError short and bounded. */
    private static final int MAX_ERROR_CHARS = 300;

    private final JobStore store;
    private final Clock clock;
    private final JobRunner runner;
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();

    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService ticker;

    /** Per-job overlap guard: id present ⇒ a run is in flight; SKIP a re-submit for that id. */
    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();

    /** Production ctor — system-clock, direct (non-impersonating) runner. */
    public JobScheduler(JobStore store) {
        this(store, Clock.systemUTC(), JobRunner.DIRECT);
    }

    /**
     * Visible for tests — inject a controllable {@link Clock} and/or a custom {@link JobRunner} seam
     * (PR3 will inject the owner-identity runner here). A null runner falls back to {@link
     * JobRunner#DIRECT}; a null clock to {@link Clock#systemUTC()}.
     */
    public JobScheduler(JobStore store, Clock clock, JobRunner runner) {
        if (store == null) {
            throw new IllegalArgumentException("JobStore is required");
        }
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.runner = runner == null ? JobRunner.DIRECT : runner;
        this.workers = new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100), namedDaemon("saiku-job-worker"));
        this.ticker = Executors.newSingleThreadScheduledExecutor(namedDaemon("saiku-job-scheduler"));
    }

    /**
     * Start the periodic ticker. <b>Not</b> called by any Spring bean in PR2 (the engine is dormant);
     * a later slice invokes it from wiring. Idempotent-safe to call once.
     */
    public void start() {
        ticker.scheduleAtFixedRate(this::tick, TICK_PERIOD_SECONDS, TICK_PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("JobScheduler started — tick every {}s", TICK_PERIOD_SECONDS);
    }

    /** Register (or replace) the handler for a job {@code type}. */
    public void registerHandler(String type, JobHandler handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler are required");
        }
        handlers.put(type, handler);
    }

    /** Bulk-register handlers keyed by type. */
    public void setHandlers(Map<String, JobHandler> byType) {
        handlers.clear();
        if (byType != null) {
            byType.forEach(this::registerHandler);
        }
    }

    /** The handler registered for {@code type}, or null. */
    public JobHandler handlerFor(String type) {
        return type == null ? null : handlers.get(type);
    }

    private long now() {
        return clock.millis();
    }

    /**
     * One scan step: find DUE jobs and submit each to the worker pool. Exposed (package-private, but
     * driven from tests via reflection or the test living in the same package) so tests can invoke it
     * synchronously with a controlled clock — no wall-clock sleeps. Never throws; a hiccup on one job
     * doesn't abort the scan.
     */
    void tick() {
        try {
            long now = now();
            for (ScheduledJobFile job : store.listAll()) {
                if (job == null || !isDue(job, now)) {
                    continue;
                }
                submit(job);
            }
        } catch (Exception ex) {
            log.warn("job-scheduler tick hiccup: {}", ex.toString());
        }
    }

    /** DUE = enabled and not in cooldown and next-run time has arrived. */
    static boolean isDue(ScheduledJobFile job, long now) {
        return job.isEnabled() && job.getNextRunAt() <= now && job.getCooldownUntil() <= now;
    }

    /**
     * Submit a due job to the worker pool, honouring the per-job overlap guard. If the prior run is
     * still in flight the submit is skipped and the job is marked {@link JobStatus#SKIPPED}.
     */
    private void submit(ScheduledJobFile job) {
        final String id = job.getId();
        if (running.putIfAbsent(id, Boolean.TRUE) != null) {
            // A prior run is still in flight — never run a job concurrently with itself.
            markSkipped(id);
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    runNow(job);
                } finally {
                    running.remove(id);
                }
            });
        } catch (RejectedExecutionException rex) {
            // Queue full / shutting down — release the guard so a later tick can retry.
            running.remove(id);
            log.debug("job {} rejected by worker pool: {}", id, rex.toString());
        }
    }

    /**
     * Run one job now, synchronously on the caller's thread, and write bookkeeping back to the store.
     * This is the deterministic entry point tests drive. Reloads the job first so it operates on the
     * freshest on-disk state (and no-ops on a deleted job).
     *
     * @return the persisted post-run record, or null if the job vanished / has no handler
     */
    ScheduledJobFile runNow(ScheduledJobFile job) {
        ScheduledJobFile current = store.load(job.getId());
        if (current == null) {
            return null;
        }
        JobHandler handler = handlerFor(current.getType());
        long startedAt = now();
        current.setLastRunAt(startedAt);

        if (handler == null) {
            current.setLastStatus(JobStatus.FAILED);
            current.setLastError("No handler registered for type: " + sanitize(String.valueOf(current.getType())));
            applyFailure(current, startedAt);
            return store.save(current);
        }

        try {
            runner.run(current, handler);
            // Success: reset failure state, clear cooldown, advance next-run.
            current.setLastStatus(JobStatus.OK);
            current.setLastError(null);
            current.setConsecutiveFailures(0);
            current.setCooldownUntil(0L);
            current.setNextRunAt(computeNextRunAt(current, startedAt));
        } catch (Exception e) {
            current.setLastStatus(JobStatus.FAILED);
            current.setLastError(sanitize(shortMessage(e)));
            applyFailure(current, startedAt);
        }
        return store.save(current);
    }

    /**
     * Record a failure: bump the consecutive-failure count, set an exponential-backoff cooldown, and
     * auto-disable once the threshold is crossed. Also advances {@code nextRunAt} so that after
     * cooldown the job is due again (the cooldown gate is what actually holds it back until then).
     */
    private void applyFailure(ScheduledJobFile job, long startedAt) {
        int failures = job.getConsecutiveFailures() + 1;
        job.setConsecutiveFailures(failures);
        job.setCooldownUntil(startedAt + backoffFor(failures));
        job.setNextRunAt(computeNextRunAt(job, startedAt));
        if (failures >= MAX_FAILURES) {
            job.setEnabled(false);
            log.warn("Auto-disabled job {} after {} consecutive failures", job.getId(), failures);
        }
    }

    /** Backoff for the given consecutive-failure count (1-based), capped at the last table entry. */
    static long backoffFor(int failures) {
        int idx = Math.min(Math.max(failures, 1), BACKOFF_MILLIS.length) - 1;
        return BACKOFF_MILLIS[idx];
    }

    /**
     * Next run = last-run + interval (FIXED_INTERVAL). Guards a non-positive interval by scheduling
     * one tick-period ahead so a misconfigured job can't hot-loop. CRON is not honoured yet (PR1's
     * placeholder) — it too falls back to the tick-period nudge.
     */
    static long computeNextRunAt(ScheduledJobFile job, long lastRunAt) {
        JobSchedule s = job.getSchedule();
        long fallback = lastRunAt + TimeUnit.SECONDS.toMillis(TICK_PERIOD_SECONDS);
        if (s == null || s.getKind() != JobSchedule.Kind.FIXED_INTERVAL) {
            return fallback;
        }
        long interval = s.getIntervalMillis();
        return interval > 0 ? lastRunAt + interval : fallback;
    }

    private void markSkipped(String id) {
        try {
            ScheduledJobFile j = store.load(id);
            if (j != null) {
                j.setLastStatus(JobStatus.SKIPPED);
                store.save(j);
            }
        } catch (Exception e) {
            log.debug("Could not mark job {} SKIPPED: {}", id, e.toString());
        }
    }

    /** First line of the throwable's message (or its simple class name), never a stack trace. */
    private static String shortMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        int nl = msg.indexOf('\n');
        return nl >= 0 ? msg.substring(0, nl) : msg;
    }

    /**
     * Sanitize a message for on-disk storage: strip CR/LF (log-forging / multi-line stack fragments),
     * collapse whitespace, and hard-cap the length. Never returns a stack trace. Callers are expected
     * not to place secrets in exception messages, but this bounds the blast radius regardless.
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned =
                raw.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
        if (cleaned.length() > MAX_ERROR_CHARS) {
            cleaned = cleaned.substring(0, MAX_ERROR_CHARS) + "…";
        }
        return cleaned;
    }

    /** True while a run for {@code id} is in flight (visible for tests). */
    boolean isRunning(String id) {
        return running.containsKey(id);
    }

    /** Registered handler count (visible for tests / metrics). */
    int handlerCount() {
        return handlers.size();
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
     * Stop both pools cleanly (mirrors {@code AsyncQueryService#shutdown()}). Bound to the Spring
     * bean's lifecycle in a later slice; also fires outside Spring (tests, embedded runs).
     */
    @PreDestroy
    public void shutdown() {
        log.info("JobScheduler shutting down — {} in-flight", running.size());
        ticker.shutdownNow();
        workers.shutdownNow();
    }

    /** Snapshot of registered handlers (defensive copy) — visible for tests / diagnostics. */
    Map<String, JobHandler> handlersSnapshot() {
        return new HashMap<>(handlers);
    }

    /** Package-visible accessors for tests. */
    JobStore store() {
        return store;
    }

    List<ScheduledJobFile> dueNow() {
        long now = now();
        return store.listAll().stream().filter(j -> j != null && isDue(j, now)).toList();
    }
}
