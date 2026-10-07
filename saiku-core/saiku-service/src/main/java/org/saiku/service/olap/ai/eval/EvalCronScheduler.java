/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * saiku#1477 (Phase 2b) — drives {@link ScheduledEvalRunner#runScheduled()} from a cron expression
 * so a live deployment's eval store keeps filling in on its own, without an external scheduler
 * {@code curl}ing {@code POST /rest/saiku/admin/ai-evals/run}.
 *
 * <p><b>Off by default.</b> A blank/absent cron expression ({@code saiku.ai.eval.schedule.cron} unset
 * — the default) means {@link #start()} creates no thread, schedules nothing and logs a single INFO
 * line saying so. An <em>unparseable</em> expression is treated the same way (one ERROR line, then
 * inert): a typo in a properties file must never take the app down at boot.
 *
 * <p><b>Self-rescheduling, not {@code scheduleAtFixedRate}.</b> Each fire schedules the next one
 * from the clock <em>after</em> the sweep completes. That keeps the cron semantics (the expression
 * owns the cadence) while making overlap structurally impossible — a sweep that outlives its
 * interval simply pushes the next fire out, rather than queueing a second concurrent sweep against
 * the same LLM provider and result store.
 *
 * <p><b>Threading.</b> One daemon thread ({@code saiku-eval-cron}) from a
 * {@link ScheduledExecutorService}, mirroring {@code JobScheduler}'s posture. The sweep itself runs
 * as a <b>context-free background caller</b>: no Spring {@code SecurityContext}, no bound HTTP
 * request. On a security-enabled datasource that means role resolution lands on the same
 * start-up/warm-up carve-out {@code SecurityAwareConnectionManager} already grants background
 * callers (see {@code enforceRoleResolvedOrAdmin}) — operators should know that before pointing a
 * cron at a cube whose rows are role-masked. This is why the whole feature is opt-in: an eval sweep
 * costs LLM budget and runs unattended.
 *
 * <p><b>Testability.</b> Time flows through an injected {@link Clock}; the next-fire computation
 * ({@link #nextDelayMillis}) and the guarded fire ({@link #fireOnce}) are package-visible so tests
 * assert them deterministically without waiting on wall-clock time.
 */
public class EvalCronScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvalCronScheduler.class);

    /** Floor on the delay handed to the executor, so a clock jump can't hot-loop the sweep. */
    static final long MIN_DELAY_MILLIS = 1_000L;

    private final String cronExpression;
    private final Runnable sweep;
    private final Clock clock;

    /** Created by {@link #start()}; null while disabled. */
    private volatile ScheduledExecutorService executor;

    /**
     * Spring wiring factory — the one-liner a {@code saiku-beans.xml} {@code <bean>} needs:
     *
     * <pre>{@code
     * <bean id="evalScheduler" class="org.saiku.service.olap.ai.eval.EvalCronScheduler"
     *       factory-method="forCron" init-method="start" destroy-method="stop">
     *   <constructor-arg value="${saiku.ai.eval.schedule.cron:}"/>
     *   <constructor-arg value="${saiku.home:./saiku-home}/evals"/>
     *   <constructor-arg ref="aiAskServiceBean"/>
     *   <constructor-arg ref="aiCubeMetadataServiceBean"/>
     *   <constructor-arg ref="evalThinQueryServiceBean"/>
     *   <constructor-arg ref="evalResultStoreBean"/>
     * </bean>
     * }</pre>
     *
     * <p>Everything except the cron string is a reference, and the adapter/runner are built
     * <em>inside</em> the sweep lambda — so with the cron unset (the default) the scheduler starts
     * inert without ever constructing an ask adapter or touching a datasource.
     */
    public static EvalCronScheduler forCron(
            String cronExpression,
            String evalsDir,
            AiAskService askService,
            AiCubeMetadataService cubeMetadataService,
            ThinQueryService thinQueryService,
            EvalResultStore store) {
        String dir = evalsDir == null || evalsDir.isBlank() ? "./saiku-home/evals" : evalsDir;
        Runnable sweep = () -> new ScheduledEvalRunner(
                        java.nio.file.Path.of(dir),
                        new LiveEvalAskAdapter(
                                askService, cubeMetadataService, new AiSchemaConverter(), thinQueryService),
                        store)
                .runScheduled();
        return new EvalCronScheduler(cronExpression, sweep);
    }

    public EvalCronScheduler(String cronExpression, Runnable sweep) {
        this(cronExpression, sweep, Clock.systemDefaultZone());
    }

    /** Visible for tests — injects a controllable {@link Clock}. */
    EvalCronScheduler(String cronExpression, Runnable sweep, Clock clock) {
        this.cronExpression = cronExpression == null ? "" : cronExpression.trim();
        this.sweep = sweep;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /**
     * Spring {@code init-method}. Starts the cron thread when — and only when — a non-blank,
     * parseable expression is configured. Idempotent: a second call is a no-op.
     */
    public synchronized void start() {
        if (executor != null) {
            return; // already started
        }
        if (cronExpression.isEmpty()) {
            log.info("Scheduled agent-eval sweeps are disabled (saiku.ai.eval.schedule.cron not set)");
            return;
        }
        CronExpression cron = parseCron();
        if (cron == null) {
            return; // parseCron already logged the reason; stay inert rather than fail boot
        }
        executor = Executors.newSingleThreadScheduledExecutor(namedDaemon("saiku-eval-cron"));
        long delay = nextDelayMillis(cron, clock.instant());
        if (scheduleNext(cron, delay) && delay >= 0) {
            log.info(
                    "Scheduled agent-eval sweeps with cron \"{}\" (first sweep in {}s)", cronExpression, delay / 1000L);
        }
    }

    /**
     * Spring {@code destroy-method} / {@code @PreDestroy}. Stops the cron thread. Never throws —
     * shutdown must not mask a real failure elsewhere in the context close.
     */
    public synchronized void stop() {
        ScheduledExecutorService e = executor;
        executor = null;
        if (e == null) {
            return;
        }
        e.shutdownNow();
        log.info("Scheduled agent-eval sweeps stopped");
    }

    /** True once {@link #start()} has armed the cron thread. */
    boolean isStarted() {
        return executor != null;
    }

    /** The configured expression, trimmed; empty when the scheduler is disabled. */
    String cronExpression() {
        return cronExpression;
    }

    /**
     * Milliseconds from {@code from} until the next fire time the expression allows, floored at
     * {@link #MIN_DELAY_MILLIS}. Visible for tests.
     */
    long nextDelayMillis(CronExpression cron, Instant from) {
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(from, clock.getZone()));
        if (next == null) {
            // Unreachable for a well-formed expression; treated as "far future" rather than 0 so a
            // pathological expression can't turn into a hot loop.
            return Long.MAX_VALUE;
        }
        long millis = Duration.between(from, next.toInstant()).toMillis();
        return Math.max(millis, MIN_DELAY_MILLIS);
    }

    /** Run the sweep once, then arm the next fire. Never throws. Visible for tests. */
    void fireOnce(CronExpression cron) {
        try {
            sweep.run();
        } catch (RuntimeException e) {
            // A scheduler thread that dies on an exception is silently never rescheduled — that
            // would look exactly like "the cron stopped working" to an operator.
            log.error("Scheduled agent-eval sweep threw; continuing on the same cron", e);
        } finally {
            scheduleNext(cron, nextDelayMillis(cron, clock.instant()));
        }
    }

    private boolean scheduleNext(CronExpression cron, long delayMillis) {
        ScheduledExecutorService e = executor;
        if (e == null) {
            return false; // stopped
        }
        try {
            e.schedule(() -> fireOnce(cron), delayMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException rex) {
            // Shutting down (or already stopped) — expected during shutdown, not an error.
            log.debug("agent-eval cron fire rejected: {}", rex.toString());
            return false;
        }
    }

    /** Parse the configured expression, or log why we can't and return null (stay inert). */
    private CronExpression parseCron() {
        try {
            return CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException e) {
            log.error(
                    "Ignoring invalid saiku.ai.eval.schedule.cron=\"{}\" ({}: {}) — scheduled agent-eval "
                            + "sweeps stay disabled",
                    cronExpression,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private static ThreadFactory namedDaemon(final String base) {
        final AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, base + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
