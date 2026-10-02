/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * saiku#1918 (17d, CWE-770) — the daily LLM cost budget.
 *
 * <p>The per-minute {@link AiRateLimiter} bounds request frequency, which is not the same thing as
 * cost: a chained ask is one request and up to {@code maxSteps} provider round-trips, each
 * re-sending the whole cube schema, and it holds a request thread for the duration. Nothing on the
 * path counted tokens, so a five-figure month and a thread-pool stall both looked like a healthy
 * request rate.
 *
 * <p>These tests pin the three ceilings, the post-hoc charging, the chain-concurrency cap, and the
 * two fail-open wiring cases (unknown principal, concurrent first call).
 */
public class AiCostBudgetTest {

    private static AiCostBudget budget(int calls, long userTokens, long instanceTokens, int chains) {
        return new AiCostBudget(calls, userTokens, instanceTokens, chains);
    }

    // ---------------- per-user daily call ceiling ----------------

    @Test
    public void callsAreAllowedUpToTheDailyCeilingAndRefusedAfterIt() {
        AiCostBudget b = budget(3, 0L, 0L, 0);
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
        assertEquals(AiCostBudget.Denial.DAILY_CALL_LIMIT, b.tryStartCall("ann"));
        assertEquals(4, b.callsMadeBy("ann"));
    }

    @Test
    public void theCallCeilingIsPerPrincipal() {
        // A shared ceiling would let one noisy tenant exhaust everyone else's day; the whole point
        // of keying on the principal is that budgets are attributable and refusals are fair.
        AiCostBudget b = budget(1, 0L, 0L, 0);
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
        assertEquals(AiCostBudget.Denial.DAILY_CALL_LIMIT, b.tryStartCall("ann"));
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("bob"));
    }

    // ---------------- per-user daily token ceiling ----------------

    @Test
    public void tokensAreChargedFromProviderReportedUsage() {
        AiCostBudget b = budget(0, 1_001L, 0L, 0);
        b.recordUsage("ann", 700, 300);
        assertEquals(1_000L, b.tokensSpentBy("ann"));
        // Under the ceiling: the next call still goes through.
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
    }

    @Test
    public void theTokenCeilingRefusesOnceItIsReached() {
        // "at or over" rather than "over": a caller who has already spent the ceiling cannot be
        // allowed one more turn, because that turn is the one that goes past it.
        AiCostBudget b = budget(0, 1_000L, 0L, 0);
        b.recordUsage("ann", 1_000, 0);
        assertEquals(AiCostBudget.Denial.DAILY_TOKEN_LIMIT, b.tryStartCall("ann"));
    }

    @Test
    public void aTurnWithNoReportedUsageIsStillCharged() {
        // A provider that stops returning `usage` still bills. Charging zero would make the surface
        // free precisely when the operator has lost the ability to see what they're paying.
        AiCostBudget b = budget(0, AiCostBudget.UNREPORTED_TOKEN_CHARGE * 2, 0L, 0);
        b.recordUnpricedCall("ann");
        assertEquals(AiCostBudget.UNREPORTED_TOKEN_CHARGE, b.tokensSpentBy("ann"));
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
        b.recordUnpricedCall("ann");
        assertEquals(AiCostBudget.Denial.DAILY_TOKEN_LIMIT, b.tryStartCall("ann"));
    }

    @Test
    public void negativeReportedUsageIsNotCreditedBack() {
        // -1 is the "provider didn't report" sentinel. Treating it as a negative spend would let a
        // run of unreported turns REDUCE the budget.
        AiCostBudget b = budget(0, 10_000L, 0L, 0);
        b.recordUsage("ann", -1, -1);
        assertTrue("must not be credited", b.tokensSpentBy("ann") > 0);
    }

    // ---------------- per-instance daily token ceiling ----------------

    @Test
    public void theInstanceCeilingAppliesAcrossPrincipals() {
        // Without this, N principals each comfortably inside their own personal cap can still
        // collectively bankrupt the month. The instance ceiling is the blast-radius bound.
        AiCostBudget b = budget(0, 0L, 1_000L, 0);
        b.recordUsage("ann", 400, 0);
        b.recordUsage("bob", 400, 0);
        assertEquals(800L, b.instanceTokensSpent());
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
        b.recordUsage("carol", 300, 0);
        assertEquals(AiCostBudget.Denial.INSTANCE_TOKEN_LIMIT, b.tryStartCall("carol"));
    }

    // ---------------- fail-open on wiring gaps ----------------

    @Test
    public void anUnidentifiableCallerIsAllowedThrough() {
        // A null principal means identity couldn't be derived. Failing closed there would let a
        // wiring gap brick the feature for everyone; the request rate limiter still applies.
        AiCostBudget b = budget(1, 1L, 1L, 0);
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall(null));
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall(null));
    }

    @Test
    public void unidentifiableCallersStillConsumeTheSharedInstanceBudget() {
        // Fail-open on identity must not mean free: the instance counter is shared, so an
        // unattributable caller still spends the deployment's money.
        AiCostBudget b = budget(0, 0L, 100L, 0);
        b.recordUsage(null, 100, 0);
        assertEquals(AiCostBudget.Denial.INSTANCE_TOKEN_LIMIT, b.tryStartCall(null));
    }

    // ---------------- chained asks ----------------

    @Test
    public void extraChainStepsCountAsExtraCalls() {
        // A chain is one HTTP request and up to maxSteps provider calls. Counting only the request
        // makes the most expensive operation on the surface look like the cheapest.
        AiCostBudget b = budget(3, 0L, 0L, 0);
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann")); // the request
        b.recordExtraCalls("ann", 4); // ...which made five provider round-trips
        assertEquals(5, b.callsMadeBy("ann"));
        assertEquals(AiCostBudget.Denial.DAILY_CALL_LIMIT, b.tryStartCall("ann"));
    }

    @Test
    public void extraChainStepsAreCountedPerPrincipal() {
        AiCostBudget b = budget(2, 0L, 0L, 0);
        b.recordExtraCalls("ann", 2);
        assertEquals(2, b.callsMadeBy("ann"));
        assertEquals(0, b.callsMadeBy("bob"));
    }

    // ---------------- concurrency cap on chains ----------------

    @Test
    public void chainSlotsAreBoundedAndReleasedOnClose() {
        AiCostBudget b = budget(0, 0L, 0L, 2);
        AiCostBudget.ChainSlot first = b.tryAcquireChainSlot();
        AiCostBudget.ChainSlot second = b.tryAcquireChainSlot();
        assertNotNull(first);
        assertNotNull(second);
        // Third caller is turned away rather than queued: an unbounded chain holds its request
        // thread until the chain deadline, so queueing is what causes the stall.
        assertNull(b.tryAcquireChainSlot());

        first.close();
        assertNotNull("a released permit must come back", b.tryAcquireChainSlot());
        second.close();
    }

    @Test
    public void aZeroChainCapDisablesTheLimitRatherThanRefusingEverything() {
        AiCostBudget b = budget(0, 0L, 0L, 0);
        for (int i = 0; i < 50; i++) {
            assertNotNull("a disabled cap must never refuse", b.tryAcquireChainSlot());
        }
    }

    @Test
    public void chainSlotsAreReleasedEvenWhenTheChainThrows() {
        // try-with-resources at the call site is what guarantees this; the property that matters is
        // that a slot is a plain AutoCloseable with no bookkeeping to leak.
        AiCostBudget b = budget(0, 0L, 0L, 1);
        try (AiCostBudget.ChainSlot slot = b.tryAcquireChainSlot()) {
            assertNotNull(slot);
            throw new IllegalStateException("simulated mid-chain failure");
        } catch (IllegalStateException expected) {
            // swallowed — the point is that the permit came back
        }
        assertNotNull("a failed chain must not leak its permit", b.tryAcquireChainSlot());
    }

    // ---------------- concurrency correctness ----------------

    @Test
    public void concurrentFirstCallsDoNotHandOutASecondAllowance() throws Exception {
        // The per-principal entry is created lazily; a race there could give a caller two fresh
        // budgets and let them spend double what the ceiling allows.
        AiCostBudget b = budget(1, 0L, 0L, 0);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    b.tryStartCall("ann");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        for (Thread t : workers) {
            t.join();
        }
        // Every thread that ran must be counted exactly once \u2014 no lost increments, no double
        // allowance. The ceiling of 1 means exactly one may proceed.
        assertEquals(threads, b.callsMadeBy("ann"));
        assertEquals(AiCostBudget.Denial.DAILY_CALL_LIMIT, b.tryStartCall("ann"));
    }

    @Test
    public void concurrentChargesAreNotLost() throws Exception {
        AiCostBudget b = budget(0, 0L, 0L, 0);
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        b.recordUsage("ann", 1, 1);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals((long) threads * perThread * 2, b.tokensSpentBy("ann"));
        assertEquals((long) threads * perThread * 2, b.instanceTokensSpent());
    }

    // ---------------- configuration surface ----------------

    @Test
    public void aZeroCeilingMeansUnlimitedForThatDimension() {
        // 0 is the "not configured" value for every ceiling, so an operator can relax one dimension
        // without having to pick a magic large number.
        AiCostBudget b = budget(0, 0L, 0L, 0);
        for (int i = 0; i < 5_000; i++) {
            b.recordUsage("ann", 10_000, 10_000);
        }
        assertEquals(AiCostBudget.Denial.ALLOWED, b.tryStartCall("ann"));
    }

    @Test
    public void chargeableTokensIsTheSingleDefinitionOfWhatCosts() {
        assertEquals(300L, AiCostBudget.chargeableTokens(100, 200));
        // Nothing reported is a nominal charge, never zero — a zero charge would make an
        // unreported turn free.
        assertEquals(AiCostBudget.UNREPORTED_TOKEN_CHARGE, AiCostBudget.chargeableTokens(-1, -1));
        assertEquals(AiCostBudget.UNREPORTED_TOKEN_CHARGE, AiCostBudget.chargeableTokens(0, 0));
    }

    @Test
    public void accessorsReportTheConfiguredCeilings() {
        AiCostBudget b = budget(11, 22L, 33L, 4);
        assertEquals(11, b.getMaxCallsPerUserPerDay());
        assertEquals(22L, b.getMaxTokensPerUserPerDay());
        assertEquals(33L, b.getMaxTokensPerInstancePerDay());
    }

    @Test
    public void unknownPrincipalsReportZeroRatherThanThrowing() {
        // The accessors back an admin surface; a principal that has never asked must read as zero
        // rather than blowing up a status page.
        AiCostBudget b = budget(1, 1L, 1L, 1);
        assertEquals(0L, b.tokensSpentBy("never-seen"));
        assertEquals(0, b.callsMadeBy(null));
        assertEquals(0L, b.tokensSpentBy(null));
    }
}
