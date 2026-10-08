/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daily cost budget for the LLM-backed ask surface (saiku#1918 17d, CWE-770).
 *
 * <p><b>What this closes.</b> {@link AiRateLimiter} caps CALL FREQUENCY: 30 requests per minute,
 * per (user, IP). Frequency is not cost. A single chained ask is up to {@code maxSteps} provider
 * round-trips, each re-sending the whole cube schema and the accumulated tool transcript, and the
 * whole loop runs inside one request that pins a Jetty thread until the chain deadline. Thirty of
 * those a minute is a five-figure monthly bill and a thread-pool stall, and neither the limiter nor
 * anything else on the path notices: no counter ever reaches a ceiling, because nothing was
 * counting tokens.
 *
 * <p><b>Three independent ceilings</b>, because a per-minute call cap catches none of the three
 * failure modes on its own:
 *
 * <ul>
 *   <li><b>Per-principal, per-day request count</b> — the "chatty user" ceiling. Cheap to enforce
 *       (it never has to wait for a provider response) and it bounds the tail before the spend
 *       happens.</li>
 *   <li><b>Per-principal, per-day tokens</b> — the real money ceiling, charged from the usage the
 *       provider itself reports back on each turn. Keyed on the principal, not on (user, IP), so
 *       walking between addresses doesn't mint a fresh allowance.</li>
 *   <li><b>Per-instance, per-day tokens</b> — the shared blast-radius ceiling. Without it, N
 *       principals each under their own personal cap can still collectively bankrupt the month.</li>
 * </ul>
 *
 * <p><b>Why the day window is calendar-anchored, not rolling.</b> A rolling 24h window from first
 * use lets an attacker shift their spend into a quieter hour and never hit the ceiling at the same
 * wall-clock moment. The window here resets at UTC midnight, so the ceiling is the same number an
 * operator can reason about ("yesterday cost this much").
 *
 * <p><b>Charging is post-hoc; the gate is pre-flight.</b> Token spend is only knowable after the
 * provider answers, so {@link #tryStartCall} is a pre-flight check against what's already been
 * spent, and {@link #recordUsage} charges the real amount afterwards. A turn that starts just under
 * the ceiling can therefore overshoot it by one turn's cost — bounded, and the right trade against
 * refusing legitimate work we can't price in advance.
 *
 * <p><b>Fail-open on wiring gaps, fail-closed on exhaustion.</b> A {@code null} principal is
 * allowed through (a wiring gap must not brick the feature); a principal that has actually exceeded
 * a ceiling is refused. Unreported usage ({@code <= 0}) is charged as a nominal unit so an
 * unreported turn still costs the request counter — otherwise a provider that stopped returning
 * usage would be free.
 *
 * <p>Single-node, in-memory, and lazy like {@link AiRateLimiter}: no background thread, no shared
 * store. A multi-node deployment gets per-node budgets, which is the same trade-off the rate
 * limiter already makes and the right one for a guardrail whose job is to bound the worst case.
 */
public class AiCostBudget {

    private static final Logger log = LoggerFactory.getLogger(AiCostBudget.class);

    /** Nominal tokens charged for a provider turn that reported no usage. */
    static final long UNREPORTED_TOKEN_CHARGE = 1_000L;

    private final int maxCallsPerUserPerDay;
    private final long maxTokensPerUserPerDay;
    private final long maxTokensPerInstancePerDay;

    /** Per-principal counters. A single shared map for both, so one lookup serves the gate. */
    private final Map<String, PrincipalCounters> perPrincipal = new ConcurrentHashMap<>();

    /** Instance-wide token counter for the current UTC day. */
    private final InstanceCounters instance = new InstanceCounters();

    /** Configured chain-concurrency cap; {@code <= 0} disables the limit. */
    private final int maxConcurrentChains;

    /** Guards {@link #tryAcquireChainSlot()}; {@code null} when the cap is disabled. */
    private final java.util.concurrent.Semaphore chainSlots;

    public AiCostBudget() {
        this(
                Integer.getInteger("saiku.ai.budget.maxCallsPerUserPerDay", 500),
                Long.getLong("saiku.ai.budget.maxTokensPerUserPerDay", 2_000_000L),
                Long.getLong("saiku.ai.budget.maxTokensPerInstancePerDay", 20_000_000L),
                Integer.getInteger("saiku.ai.budget.maxConcurrentChains", 4));
    }

    public AiCostBudget(
            int maxCallsPerUserPerDay,
            long maxTokensPerUserPerDay,
            long maxTokensPerInstancePerDay,
            int maxConcurrentChains) {
        this.maxCallsPerUserPerDay = maxCallsPerUserPerDay;
        this.maxTokensPerUserPerDay = maxTokensPerUserPerDay;
        this.maxTokensPerInstancePerDay = maxTokensPerInstancePerDay;
        this.maxConcurrentChains = maxConcurrentChains;
        this.chainSlots =
                maxConcurrentChains > 0 ? new java.util.concurrent.Semaphore(maxConcurrentChains, true) : null;
    }

    /** Why a {@link #tryStartCall} gate refused, so the caller can log something actionable. */
    public enum Denial {
        ALLOWED,
        DAILY_CALL_LIMIT,
        DAILY_TOKEN_LIMIT,
        INSTANCE_TOKEN_LIMIT
    }

    /**
     * Pre-flight gate for a cost-bearing ask. Counts the call and reports whether the principal is
     * still inside every ceiling.
     *
     * <p>Counts the call even when it then refuses, so a client hammering a refused endpoint still
     * advances its own counter rather than getting an infinite stream of "try again" from a state
     * that never changes.
     *
     * @param principal the authenticated caller; {@code null} is allowed through (fail-open on a
     *     wiring gap, matching {@link AiRateLimiter#tryAcquire(String)})
     */
    public Denial tryStartCall(String principal) {
        long now = System.currentTimeMillis();
        // A null principal skips the PER-PRINCIPAL ceilings only — it can still be refused by the
        // instance ceiling below. Failing open on identity is not the same as failing open on
        // money: an unattributable caller spends the deployment's budget like anyone else.
        if (principal != null) {
            PrincipalCounters c = countersFor(principal, now);
            c.calls.incrementAndGet();
            if (maxCallsPerUserPerDay > 0 && c.calls.get() > maxCallsPerUserPerDay) {
                log.warn(
                        "AI cost budget: daily call limit reached principal={} calls={} max={}",
                        principal,
                        c.calls.get(),
                        maxCallsPerUserPerDay);
                return Denial.DAILY_CALL_LIMIT;
            }
            if (maxTokensPerUserPerDay > 0 && c.tokens.get() >= maxTokensPerUserPerDay) {
                log.warn(
                        "AI cost budget: daily token limit reached principal={} tokens={} max={}",
                        principal,
                        c.tokens.get(),
                        maxTokensPerUserPerDay);
                return Denial.DAILY_TOKEN_LIMIT;
            }
        }
        if (maxTokensPerInstancePerDay > 0 && instance.tokens(now) >= maxTokensPerInstancePerDay) {
            log.warn(
                    "AI cost budget: instance daily token limit reached tokens={} max={}",
                    instance.tokens(now),
                    maxTokensPerInstancePerDay);
            return Denial.INSTANCE_TOKEN_LIMIT;
        }
        return Denial.ALLOWED;
    }

    /**
     * Charge the real cost of a completed ask. The CALL was already counted by
     * {@link #tryStartCall(String)}; this charges only the money, from the usage the provider
     * reported on the turn.
     *
     * @param principal the authenticated caller; {@code null} charges the instance counter only
     * @param inputTokens provider-reported prompt tokens ({@code <= 0} means "not reported")
     * @param outputTokens provider-reported completion tokens ({@code <= 0} means "not reported")
     */
    public void recordUsage(String principal, long inputTokens, long outputTokens) {
        long tokens = chargeableTokens(inputTokens, outputTokens);
        long now = System.currentTimeMillis();
        if (principal != null) {
            countersFor(principal, now).tokens.addAndGet(tokens);
        }
        instance.add(now, tokens);
    }

    /**
     * Charge the EXTRA provider round-trips a multi-call operation made beyond the one already
     * counted by {@link #tryStartCall(String)}.
     *
     * <p>A chained ask is billed as one request by the endpoint, but it is up to {@code maxSteps}
     * provider round-trips. Counting only the request would make the chain — the single most
     * expensive operation on the surface — look like the cheapest one, which is exactly backwards
     * for a ceiling whose job is to stop the worst case.
     *
     * @param principal the authenticated caller; {@code null} is a no-op (the instance counter is
     *     charged through {@link #recordUsage})
     * @param extraCalls round-trips beyond the first; {@code <= 0} is a no-op
     */
    public void recordExtraCalls(String principal, int extraCalls) {
        if (principal == null || extraCalls <= 0) return;
        countersFor(principal, System.currentTimeMillis()).calls.addAndGet(extraCalls);
    }

    /**
     * Charge one provider round-trip whose usage the provider did not report. A nominal amount is
     * charged rather than nothing, so a provider that silently stops returning {@code usage} can't
     * make the surface free while still costing real money on the provider's invoice.
     */
    public void recordUnpricedCall(String principal) {
        recordUsage(principal, 0L, 0L);
    }

    /** Tokens actually charged: what the provider reported, or a nominal amount if it reported none. */
    static long chargeableTokens(long inputTokens, long outputTokens) {
        long reported = Math.max(0L, inputTokens) + Math.max(0L, outputTokens);
        return reported > 0 ? reported : UNREPORTED_TOKEN_CHARGE;
    }

    /**
     * Take one of the concurrent-chain slots, bounding how many chained asks can be mid-flight.
     *
     * <p>A chain pins a request thread for up to the chain deadline and re-sends the schema on every
     * step, so unbounded concurrency is both a thread-pool and a spend problem. The permit is
     * {@link java.util.concurrent.Semaphore#close()}d-shaped ({@link ChainSlot#close()} releases
     * it) so a caller can't forget on an exception path.
     *
     * @return a slot to hold for the duration of the chain, or {@code null} when the cap is
     *     disabled or every slot is taken
     */
    public ChainSlot tryAcquireChainSlot() {
        if (chainSlots == null) return ChainSlot.NOOP;
        if (!chainSlots.tryAcquire()) {
            log.warn("AI cost budget: concurrent chain slots exhausted ({})", maxConcurrentChains);
            return null;
        }
        return chainSlots::release;
    }

    /** Tokens {@code principal} has spent today; used by tests and the admin surface. */
    public long tokensSpentBy(String principal) {
        if (principal == null) return 0L;
        PrincipalCounters c = perPrincipal.get(principal);
        return c == null ? 0L : c.tokens.get();
    }

    /** Calls {@code principal} has made today. */
    public int callsMadeBy(String principal) {
        if (principal == null) return 0;
        PrincipalCounters c = perPrincipal.get(principal);
        return c == null ? 0 : c.calls.get();
    }

    /** Tokens the whole instance has spent today. */
    public long instanceTokensSpent() {
        return instance.tokens(System.currentTimeMillis());
    }

    public int getMaxCallsPerUserPerDay() {
        return maxCallsPerUserPerDay;
    }

    public long getMaxTokensPerUserPerDay() {
        return maxTokensPerUserPerDay;
    }

    public long getMaxTokensPerInstancePerDay() {
        return maxTokensPerInstancePerDay;
    }

    /** Configured chain-concurrency cap; {@code 0} means unlimited. */
    public int getMaxConcurrentChains() {
        return maxConcurrentChains;
    }

    /** A held chain-concurrency permit. {@link #close()} is idempotent and always releases. */
    @FunctionalInterface
    public interface ChainSlot extends AutoCloseable {
        /** A slot that imposes no limit — returned when the cap is disabled. */
        ChainSlot NOOP = () -> {};

        @Override
        void close();
    }

    /**
     * Fetch (or lazily create) today's counters for a principal.
     *
     * <p>{@code compute} rather than get-then-put: the map is written from every request thread,
     * and a get/put pair lets two threads each install their own fresh counter, so one of them
     * charges into an object that is no longer in the map. That loses an increment (a caller
     * spending slightly more than the ceiling allows) and, worse, briefly hands out two
     * independent allowances for the same principal. {@code compute} is atomic per key, so the
     * counter a caller gets is always the one that is in the map.
     */
    private PrincipalCounters countersFor(String principal, long now) {
        long today = dayIndex(now);
        return perPrincipal.compute(principal, (k, existing) -> {
            if (existing != null && existing.day == today) {
                return existing;
            }
            return new PrincipalCounters(today);
        });
    }

    /** Days since the epoch — a monotonic, UTC-anchored bucket id. */
    private static long dayIndex(long nowMs) {
        return nowMs / 86_400_000L;
    }

    private static final class PrincipalCounters {
        final long day;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong tokens = new AtomicLong();

        PrincipalCounters(long day) {
            this.day = day;
        }
    }

    private static final class InstanceCounters {
        private final AtomicLong tokens = new AtomicLong();
        private volatile long day = -1L;

        long tokens(long nowMs) {
            rollIfNeeded(nowMs);
            return tokens.get();
        }

        void add(long nowMs, long amount) {
            rollIfNeeded(nowMs);
            tokens.addAndGet(amount);
        }

        /** Reset at UTC midnight. Best-effort under concurrency: a racing writer may charge a few
         *  tokens to yesterday's bucket, which is the safe direction to be wrong in (a slightly
         *  stricter instance ceiling, never a looser one). */
        private void rollIfNeeded(long nowMs) {
            long today = dayIndex(nowMs);
            if (day == today) return;
            synchronized (this) {
                if (day == today) return;
                if (day != -1L) {
                    log.info("AI cost budget: instance token usage for the day ended at {}", tokens.get());
                }
                tokens.set(0L);
                day = today;
            }
        }
    }
}
