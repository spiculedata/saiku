/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import static org.junit.Assert.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * Deterministic unit tests for {@link JobScheduler}. Time flows through a {@link MutableClock} and the
 * scan/run steps are driven synchronously ({@link JobScheduler#tick()} / {@link
 * JobScheduler#runNow(ScheduledJobFile)}) so nothing depends on wall-clock timing — mirrors how
 * {@code AsyncQueryServiceTest} drives {@code sweep()} directly. Uses an in-memory {@link JobStore}
 * (no {@code saiku.home}).
 */
public class JobSchedulerTest {

    private JobScheduler scheduler;

    @After
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /* ---------------- helpers ---------------- */

    /** A clock whose "now" the test advances explicitly. */
    private static final class MutableClock extends Clock {
        private volatile long millis;

        MutableClock(long startMillis) {
            this.millis = startMillis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** Counting handler that can optionally block on a latch or throw. */
    private static final class RecordingHandler implements JobHandler {
        final AtomicInteger calls = new AtomicInteger();
        volatile Exception toThrow;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        @Override
        public void handle(ScheduledJobFile job) throws Exception {
            calls.incrementAndGet();
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                release.await(5, TimeUnit.SECONDS);
            }
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    private static ScheduledJobFile newJob(JobStore store, long intervalMillis) {
        return store.create(
                "alice",
                List.of("ROLE_USER"),
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, intervalMillis, "UTC"),
                "TEST",
                Map.of("k", "v"),
                true);
    }

    /* ---------------- tests ---------------- */

    @Test(timeout = 5000)
    public void dueJobRuns_handlerInvokedAndBookkeepingWritten() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        RecordingHandler h = new RecordingHandler();
        scheduler.registerHandler("TEST", h);

        ScheduledJobFile job = newJob(store, 60_000L); // nextRunAt defaults to 0 → due
        ScheduledJobFile after = scheduler.runNow(job);

        assertEquals(1, h.calls.get());
        assertEquals(JobStatus.OK, after.getLastStatus());
        assertNull(after.getLastError());
        assertEquals(0, after.getConsecutiveFailures());
        assertEquals(0L, after.getCooldownUntil());
        assertEquals(clock.millis(), after.getLastRunAt());
        assertEquals(clock.millis() + 60_000L, after.getNextRunAt());
    }

    @Test(timeout = 5000)
    public void notYetDue_skippedByTick() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        RecordingHandler h = new RecordingHandler();
        scheduler.registerHandler("TEST", h);

        ScheduledJobFile job = newJob(store, 60_000L);
        job.setNextRunAt(clock.millis() + 30_000L); // in the future
        store.save(job);

        assertFalse(JobScheduler.isDue(job, clock.millis()));
        assertTrue(scheduler.dueNow().isEmpty());
    }

    @Test(timeout = 5000)
    public void disabledJob_notDue() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        scheduler.registerHandler("TEST", new RecordingHandler());

        ScheduledJobFile job = newJob(store, 60_000L);
        job.setEnabled(false);
        store.save(job);

        assertFalse(JobScheduler.isDue(job, clock.millis()));
        assertTrue(scheduler.dueNow().isEmpty());
    }

    @Test(timeout = 5000)
    public void inCooldown_notDue() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);

        ScheduledJobFile job = newJob(store, 60_000L);
        job.setCooldownUntil(clock.millis() + 10_000L);
        assertFalse(JobScheduler.isDue(job, clock.millis()));
        clock.advance(10_001L);
        assertTrue(JobScheduler.isDue(job, clock.millis()));
    }

    @Test(timeout = 10000)
    public void overlapGuard_blocksConcurrentSameJobRun() throws Exception {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        RecordingHandler h = new RecordingHandler();
        h.entered = new CountDownLatch(1);
        h.release = new CountDownLatch(1);
        scheduler.registerHandler("TEST", h);

        newJob(store, 60_000L); // due (nextRunAt=0)

        // First tick submits the job to the worker pool; it blocks inside the handler.
        scheduler.tick();
        assertTrue("handler should have started", h.entered.await(5, TimeUnit.SECONDS));

        // Second tick while the first run is still in flight → overlap guard SKIPs it.
        scheduler.tick();

        ScheduledJobFile snap = store.listAll().get(0);
        assertEquals(JobStatus.SKIPPED, snap.getLastStatus());
        assertEquals("handler must not have been entered twice", 1, h.calls.get());

        // Let the first run finish.
        h.release.countDown();
        // Wait for the guard to clear (worker removes it in a finally).
        long deadline = System.currentTimeMillis() + 5000;
        while (scheduler.isRunning(snap.getId()) && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertFalse(scheduler.isRunning(snap.getId()));
    }

    @Test(timeout = 5000)
    public void handlerThrows_cooldownSet_failuresIncrement_sanitizedError() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        RecordingHandler h = new RecordingHandler();
        h.toThrow = new RuntimeException("smtp failed\n\tat com.example.Mailer.send(Mailer.java:42)\n\tat x");
        scheduler.registerHandler("TEST", h);

        ScheduledJobFile job = newJob(store, 60_000L);
        ScheduledJobFile after = scheduler.runNow(job);

        assertEquals(JobStatus.FAILED, after.getLastStatus());
        assertEquals(1, after.getConsecutiveFailures());
        assertEquals(clock.millis() + JobScheduler.BACKOFF_MILLIS[0], after.getCooldownUntil());
        // No stack trace, single line, no newline/tab leakage.
        assertEquals("smtp failed", after.getLastError());
        assertFalse(after.getLastError().contains("at com.example"));
        assertFalse(after.getLastError().contains("\n"));
    }

    @Test(timeout = 5000)
    public void autoDisableAfterThreshold() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        RecordingHandler h = new RecordingHandler();
        h.toThrow = new RuntimeException("boom");
        scheduler.registerHandler("TEST", h);

        ScheduledJobFile job = newJob(store, 60_000L);
        ScheduledJobFile after = null;
        for (int i = 0; i < JobScheduler.MAX_FAILURES; i++) {
            after = scheduler.runNow(job);
            // Clear cooldown so runNow proceeds each time (runNow itself doesn't gate on due-ness).
            after.setCooldownUntil(0L);
            store.save(after);
        }
        assertNotNull(after);
        assertEquals(JobScheduler.MAX_FAILURES, after.getConsecutiveFailures());
        assertFalse("job should auto-disable at threshold", after.isEnabled());
        assertEquals(JobStatus.FAILED, after.getLastStatus());
    }

    @Test(timeout = 5000)
    public void nextRunAtAdvancesByInterval() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        scheduler.registerHandler("TEST", new RecordingHandler());

        ScheduledJobFile job = newJob(store, 90_000L);
        ScheduledJobFile after = scheduler.runNow(job);
        assertEquals(clock.millis() + 90_000L, after.getNextRunAt());

        // A second run at a later clock advances from the new "now".
        clock.advance(120_000L);
        after.setNextRunAt(0L); // make due again
        store.save(after);
        ScheduledJobFile after2 = scheduler.runNow(after);
        assertEquals(clock.millis() + 90_000L, after2.getNextRunAt());
    }

    @Test(timeout = 5000)
    public void noHandlerForType_recordsFailureNotCrash() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        // No handler registered.
        ScheduledJobFile job = newJob(store, 60_000L);
        ScheduledJobFile after = scheduler.runNow(job);
        assertEquals(JobStatus.FAILED, after.getLastStatus());
        assertTrue(after.getLastError().contains("No handler"));
        assertEquals(1, after.getConsecutiveFailures());
    }

    @Test(timeout = 5000)
    public void backoffTableCapsAtLastEntry() {
        assertEquals(JobScheduler.BACKOFF_MILLIS[0], JobScheduler.backoffFor(1));
        assertEquals(JobScheduler.BACKOFF_MILLIS[1], JobScheduler.backoffFor(2));
        assertEquals(JobScheduler.BACKOFF_MILLIS[3], JobScheduler.backoffFor(4));
        // Beyond the table length → capped at the last entry.
        assertEquals(JobScheduler.BACKOFF_MILLIS[3], JobScheduler.backoffFor(9));
        // Defensive: non-positive count clamps to the first entry.
        assertEquals(JobScheduler.BACKOFF_MILLIS[0], JobScheduler.backoffFor(0));
    }

    @Test(timeout = 5000)
    public void tickRunsDueJobViaWorkerPool() throws Exception {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        RecordingHandler h = new RecordingHandler();
        h.entered = new CountDownLatch(1);
        scheduler.registerHandler("TEST", h);

        newJob(store, 60_000L);
        scheduler.tick();
        assertTrue(h.entered.await(5, TimeUnit.SECONDS));
    }

    @Test(timeout = 5000)
    public void runnerSeamIsInvoked_pr3JoinPoint() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        AtomicInteger seamCalls = new AtomicInteger();
        JobRunner countingRunner = (job, handler) -> {
            seamCalls.incrementAndGet();
            handler.handle(job); // PR2 behaviour: direct call, no impersonation
        };
        scheduler = new JobScheduler(store, clock, countingRunner);
        RecordingHandler h = new RecordingHandler();
        scheduler.registerHandler("TEST", h);

        scheduler.runNow(newJob(store, 60_000L));
        assertEquals("engine must dispatch through the JobRunner seam", 1, seamCalls.get());
        assertEquals(1, h.calls.get());
    }

    @Test(timeout = 5000)
    public void cleanShutdownStopsPools() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        JobScheduler s = new JobScheduler(store, clock, JobRunner.DIRECT);
        s.start();
        s.shutdown();
        // A tick after shutdown must not throw (worker pool rejected-execution is swallowed).
        s.registerHandler("TEST", new RecordingHandler());
        newJob(store, 60_000L);
        s.tick(); // should be a no-op-ish; must not throw
    }

    /* ---------------- saiku#1809 PR3 carry-forward fixes (engine side) ---------------- */

    /**
     * Carry-forward fix #2 (QA on PR2): shutdown must ACTUALLY terminate the ticker/worker threads,
     * not merely leave a post-shutdown {@code tick()} non-throwing. Assert real termination via
     * {@link JobScheduler#awaitTermination}.
     */
    @Test(timeout = 5000)
    public void shutdown_terminatesTickerAndWorkerThreads() throws Exception {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        JobScheduler s = new JobScheduler(store, clock, JobRunner.DIRECT);
        s.start();
        s.shutdown();
        assertTrue(
                "both the ticker and worker pools must actually terminate on shutdown",
                s.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * Carry-forward fix #1 (SEC+QA on PR2): a manual {@code runNow()} and a concurrent ticker
     * {@code tick()} of the SAME job must resolve to exactly ONE execution — the overlap guard now
     * covers both triggers, so they can never interleave into a double-run.
     */
    @Test(timeout = 10000)
    public void runNowAndTick_sameJob_executeExactlyOnce() throws Exception {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        RecordingHandler h = new RecordingHandler();
        h.entered = new CountDownLatch(1);
        h.release = new CountDownLatch(1);
        scheduler.registerHandler("TEST", h);

        final ScheduledJobFile job = newJob(store, 60_000L); // due (nextRunAt=0)

        // Kick off a manual runNow on a separate thread; it acquires the overlap guard and blocks
        // inside the handler on the release latch.
        Thread manual = new Thread(() -> scheduler.runNow(job));
        manual.start();
        assertTrue("manual runNow should have entered the handler", h.entered.await(5, TimeUnit.SECONDS));

        // While that run is in flight, a ticker tick of the same job must be overlap-SKIPPED, not run.
        scheduler.tick();
        // Give the (rejected) worker path a beat to record the SKIP; the guard is already held.
        assertTrue("the job's guard must be held while runNow is in flight", scheduler.isRunning(job.getId()));

        // Release the in-flight run and let it finish.
        h.release.countDown();
        manual.join(5000);
        // Drain: wait for the guard to clear.
        long deadline = System.currentTimeMillis() + 5000;
        while (scheduler.isRunning(job.getId()) && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }

        assertEquals("exactly one execution across runNow + concurrent tick", 1, h.calls.get());
    }

    /**
     * Engine recognises {@link OwnerUnavailableException} specially: instead of the
     * backoff-then-eventually-disable failure path, the run is SKIPPED and the job auto-disables
     * IMMEDIATELY (a job for a deleted/disabled owner stops firing at once).
     */
    @Test(timeout = 5000)
    public void ownerUnavailable_marksSkippedAndAutoDisablesImmediately() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        // A runner that always signals the owner is gone/disabled.
        JobRunner ownerGone = (job, handler) -> {
            throw new OwnerUnavailableException("owner deleted");
        };
        scheduler = new JobScheduler(store, clock, ownerGone);
        scheduler.registerHandler("TEST", new RecordingHandler());

        ScheduledJobFile after = scheduler.runNow(newJob(store, 60_000L));

        assertEquals(JobStatus.SKIPPED, after.getLastStatus());
        assertFalse("owner-unavailable must auto-disable immediately", after.isEnabled());
        // It is NOT the normal failure path — the consecutive-failure counter is not bumped.
        assertEquals(0, after.getConsecutiveFailures());
    }

    @Test(timeout = 5000)
    public void noOpHandlerSucceeds() {
        MutableClock clock = new MutableClock(1_000_000L);
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, clock, JobRunner.DIRECT);
        scheduler.registerHandler("TEST", new NoOpJobHandler());
        ScheduledJobFile after = scheduler.runNow(newJob(store, 60_000L));
        assertEquals(JobStatus.OK, after.getLastStatus());
    }
}
