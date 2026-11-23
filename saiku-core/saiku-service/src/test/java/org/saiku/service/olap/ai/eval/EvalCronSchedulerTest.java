/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.springframework.scheduling.support.CronExpression;

/**
 * saiku#1477 — unit tests for the in-server eval cron. Time is injected ({@link Clock}), and the
 * two behaviour seams ({@code nextDelayMillis}, {@code fireOnce}) are package-visible, so every
 * assertion here is deterministic — no test waits on wall-clock time except the two that
 * deliberately exercise the real executor (each with a generous timeout and a clean {@code stop()}).
 */
public class EvalCronSchedulerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** 2026-07-12T09:00:00Z — a Sunday. */
    private static final Instant NOW = Instant.parse("2026-07-12T09:00:00Z");

    private static Clock fixed() {
        return Clock.fixed(NOW, UTC);
    }

    private static long delayTo(EvalCronScheduler s, String cron, Instant from) {
        return s.nextDelayMillis(CronExpression.parse(cron), from);
    }

    // ---- off by default ----

    @Test
    public void blankCronStartsNothing() {
        EvalCronScheduler s = new EvalCronScheduler("", () -> fail("sweep must not run"), fixed());
        s.start();
        assertFalse("blank cron must not start the cron thread", s.isStarted());
        s.stop();
    }

    @Test
    public void nullCronStartsNothing() {
        EvalCronScheduler s = new EvalCronScheduler(null, () -> fail("sweep must not run"), fixed());
        s.start();
        assertFalse(s.isStarted());
        s.stop();
    }

    @Test
    public void whitespaceOnlyCronIsTreatedAsUnset() {
        EvalCronScheduler s = new EvalCronScheduler("   ", () -> fail("sweep must not run"), fixed());
        s.start();
        assertFalse(s.isStarted());
        assertEquals("", s.cronExpression());
        s.stop();
    }

    @Test
    public void stopOnDisabledSchedulerIsANoOp() {
        EvalCronScheduler s = new EvalCronScheduler("", () -> {}, fixed());
        s.stop(); // must not throw
        s.stop();
        assertFalse(s.isStarted());
    }

    // ---- an invalid expression must not break boot ----

    @Test
    public void invalidCronStartsNothingAndDoesNotThrow() {
        EvalCronScheduler s = new EvalCronScheduler("not a cron", () -> fail("sweep must not run"), fixed());
        s.start(); // must not propagate IllegalArgumentException into Spring's init-method
        assertFalse("an unparseable cron must leave the scheduler inert", s.isStarted());
        s.stop();
    }

    @Test
    public void validCronStartsTheThread() {
        AtomicInteger runs = new AtomicInteger();
        EvalCronScheduler s = new EvalCronScheduler("0 30 3 * * *", runs::incrementAndGet, fixed());
        s.start();
        try {
            assertTrue(s.isStarted());
        } finally {
            s.stop();
        }
    }

    @Test
    public void startIsIdempotent() {
        EvalCronScheduler s = new EvalCronScheduler("0 30 3 * * *", () -> {}, fixed());
        s.start();
        s.start(); // must not throw or spawn a second thread
        try {
            assertTrue(s.isStarted());
        } finally {
            s.stop();
        }
    }

    // ---- next-fire computation ----

    @Test
    public void delayIsTheDistanceToTheNextCronFire() {
        EvalCronScheduler s = new EvalCronScheduler("0 30 3 * * *", () -> {}, fixed());
        // NOW is 09:00 UTC; the next 03:30 is the following day.
        assertEquals(TimeUnit.HOURS.toMillis(18) + TimeUnit.MINUTES.toMillis(30), delayTo(s, "0 30 3 * * *", NOW));
    }

    @Test
    public void delayIsZeroSafeFloored() {
        EvalCronScheduler s = new EvalCronScheduler("* * * * * *", () -> {}, fixed());
        // Every-second cron: the next fire is < 1s away, so the MIN_DELAY_MILLIS floor applies
        // rather than handing the executor a 0ms delay (a hot loop).
        long delay = delayTo(s, "* * * * * *", NOW);
        assertTrue("delay must never be below the floor, was " + delay, delay >= EvalCronScheduler.MIN_DELAY_MILLIS);
    }

    @Test
    public void perMinuteExpressionFiresWithinAMinute() {
        // Spring's CronExpression is strict 6-field (sec min hour dom mon dow) — a 5-field
        // expression is rejected, which parseCron() turns into "stay inert", not a boot failure.
        EvalCronScheduler s = new EvalCronScheduler("0 * * * * *", () -> {}, fixed());
        long delay = delayTo(s, "0 * * * * *", NOW);
        assertTrue("expected < 60s, was " + delay, delay > 0 && delay <= TimeUnit.MINUTES.toMillis(60));
    }

    @Test
    public void fiveFieldUnixCronIsRejectedRatherThanFatal() {
        EvalCronScheduler s = new EvalCronScheduler("0 30 3 * *", () -> fail("sweep must not run"), fixed());
        s.start();
        assertFalse("a 5-field expression must not arm the scheduler", s.isStarted());
        s.stop();
    }

    @Test
    public void cronExpressionIsTrimmed() {
        EvalCronScheduler s = new EvalCronScheduler("  0 30 3 * * *  ", () -> {}, fixed());
        assertEquals("0 30 3 * * *", s.cronExpression());
    }

    // ---- fireOnce: runs the sweep and re-arms, never dies on a throw ----

    @Test
    public void fireOnceRunsTheSweepAndRearms() {
        AtomicInteger runs = new AtomicInteger();
        EvalCronScheduler s = new EvalCronScheduler("0 30 3 * * *", runs::incrementAndGet, fixed());
        s.start();
        try {
            CronExpression cron = CronExpression.parse("0 30 3 * * *");
            s.fireOnce(cron);
            assertEquals(1, runs.get());
            // Re-armed: the sweep must still be pending after firing, not exhausted.
            assertTrue(s.isStarted());
        } finally {
            s.stop();
        }
    }

    @Test
    public void fireOnceSurvivesASweepThatThrows() {
        AtomicInteger runs = new AtomicInteger();
        EvalCronScheduler s = new EvalCronScheduler(
                "0 30 3 * * *",
                () -> {
                    runs.incrementAndGet();
                    throw new IllegalStateException("LLM provider exploded");
                },
                fixed());
        s.start();
        try {
            s.fireOnce(CronExpression.parse("0 30 3 * * *"));
            s.fireOnce(CronExpression.parse("0 30 3 * * *"));
            // A scheduler thread that dies on an exception is never rescheduled — the operator
            // would just see the cron silently stop. Both fires must land.
            assertEquals(2, runs.get());
        } finally {
            s.stop();
        }
    }

    @Test
    public void stopIsIdempotentAfterStart() {
        EvalCronScheduler s = new EvalCronScheduler("0 30 3 * * *", () -> {}, fixed());
        s.start();
        s.stop();
        s.stop();
        assertFalse(s.isStarted());
    }

    // ---- one end-to-end exercise of the real executor: a per-second cron really does fire ----

    @Test
    public void aPerSecondCronActuallyFiresOnTheExecutor() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        EvalCronScheduler s = new EvalCronScheduler("* * * * * *", fired::countDown, Clock.systemUTC());
        s.start();
        try {
            assertTrue("per-second cron never fired", fired.await(30, TimeUnit.SECONDS));
        } finally {
            s.stop();
        }
    }

    @Test
    public void stopPreventsFurtherFires() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        EvalCronScheduler s = new EvalCronScheduler(
                "* * * * * *",
                () -> {
                    runs.incrementAndGet();
                    fired.countDown();
                },
                Clock.systemUTC());
        s.start();
        try {
            assertTrue(fired.await(30, TimeUnit.SECONDS));
        } finally {
            s.stop();
        }
        int afterStop = runs.get();
        // shutdownNow() interrupts the sleeping cron thread; give it a beat to (not) fire again.
        Thread.sleep(3_000L);
        assertEquals("scheduler fired again after stop()", afterStop, runs.get());
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
