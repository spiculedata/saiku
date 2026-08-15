/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.longs;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.function.BiPredicate;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.share.ShareToken;

/**
 * Property-based tests for share- and embed-token validity (saiku#941, saiku-cloud#940).
 *
 * <p>These two records are the server-side authority for unauthenticated access: a share link or an
 * embedded dashboard is honoured precisely when the token resolves to a non-revoked, unexpired
 * record. The whole access-control decision reduces to {@code isValid(now)}, and both types
 * implement it independently — so the properties are asserted against BOTH through one predicate,
 * which is also how a future divergence between them gets caught.
 *
 * <p>The invariants that matter:
 *
 * <ul>
 *   <li><b>Monotone in time.</b> Once expired, always expired. A token that could become valid
 *       again as the clock advances would be a resurrection bug.
 *   <li><b>Revocation is absolute.</b> A revoked token is invalid at every instant, past or future.
 *   <li><b>The boundary is closed.</b> {@code now == expiresAt} is expired, not still-valid — an
 *       off-by-one here grants a final free request.
 *   <li><b>Zero means "never expires"</b>, and must not be read as "expired in 1970".
 * </ul>
 */
class TokenValidityPropertyTest {

    /** One predicate pair per token type, so every property runs against both implementations. */
    private record Subject(String name, BiPredicate<Long, Boolean> expired, BiPredicate<Long, Boolean> valid) {}

    private static ShareToken share(long expiresAt, boolean revoked) {
        ShareToken t = new ShareToken();
        t.expiresAt = expiresAt;
        t.revoked = revoked;
        return t;
    }

    private static EmbedToken embed(long expiresAt, boolean revoked) {
        EmbedToken t = new EmbedToken();
        t.expiresAt = expiresAt;
        t.revoked = revoked;
        return t;
    }

    /** Build both subjects for a given (expiresAt, revoked) pair. */
    private static List<Subject> subjectsFor(long expiresAt, boolean revoked) {
        ShareToken s = share(expiresAt, revoked);
        EmbedToken e = embed(expiresAt, revoked);
        return List.of(
                new Subject("ShareToken", (now, ignored) -> s.isExpired(now), (now, ignored) -> s.isValid(now)),
                new Subject("EmbedToken", (now, ignored) -> e.isExpired(now), (now, ignored) -> e.isValid(now)));
    }

    /** Plausible epoch millis, spanning past and future. */
    private static long drawInstant(TestCase tc, String label) {
        return tc.draw(longs().min(0L).max(4_102_444_800_000L), label); // 1970 .. 2100
    }

    /**
     * Monotone in time: if a token is expired at {@code t}, it is expired at every later instant.
     * A violation would mean a dead link coming back to life.
     */
    @HegelTest
    void expiryIsMonotoneInTime(TestCase tc) {
        long expiresAt = drawInstant(tc, "expiresAt");
        boolean revoked = tc.draw(booleans(), "revoked");
        long earlier = drawInstant(tc, "earlier");
        long later = drawInstant(tc, "later");
        tc.assume(earlier <= later);

        for (Subject s : subjectsFor(expiresAt, revoked)) {
            if (s.expired().test(earlier, revoked)) {
                assertTrue(
                        s.expired().test(later, revoked),
                        s.name() + " un-expired between " + earlier + " and " + later);
            }
        }
    }

    /** Validity is monotone the other way: once invalid through time, never valid later. */
    @HegelTest
    void validityNeverReturnsOnceLostToTime(TestCase tc) {
        long expiresAt = drawInstant(tc, "expiresAt");
        long earlier = drawInstant(tc, "earlier");
        long later = drawInstant(tc, "later");
        tc.assume(earlier <= later);

        for (Subject s : subjectsFor(expiresAt, false)) {
            if (!s.valid().test(earlier, false)) {
                assertFalse(s.valid().test(later, false), s.name() + " became valid again at " + later);
            }
        }
    }

    /** A revoked token is invalid at EVERY instant — revocation outranks any expiry setting. */
    @HegelTest
    void revocationIsAbsolute(TestCase tc) {
        long expiresAt = tc.draw(sampledFrom(List.of(0L, 1L, Long.MAX_VALUE, 4_102_444_800_000L)), "expiresAt");
        long now = drawInstant(tc, "now");

        for (Subject s : subjectsFor(expiresAt, true)) {
            assertFalse(s.valid().test(now, true), s.name() + " honoured a revoked token at " + now);
        }
    }

    /**
     * The boundary is closed: at exactly {@code expiresAt} the token is already expired. An
     * off-by-one here grants one last request after the deadline.
     */
    @HegelTest
    void theExpiryInstantItselfIsAlreadyExpired(TestCase tc) {
        long expiresAt = tc.draw(longs().min(1L).max(4_102_444_800_000L), "expiresAt");

        for (Subject s : subjectsFor(expiresAt, false)) {
            assertTrue(s.expired().test(expiresAt, false), s.name() + " still live at its own expiry instant");
            assertFalse(s.valid().test(expiresAt, false), s.name() + " valid at its own expiry instant");
            // ...and the millisecond before is still good.
            assertFalse(s.expired().test(expiresAt - 1, false), s.name() + " expired a millisecond early");
        }
    }

    /** {@code expiresAt == 0} means "never expires" — not "expired at the epoch". */
    @HegelTest
    void zeroExpiryMeansNeverExpires(TestCase tc) {
        long now = drawInstant(tc, "now");

        for (Subject s : subjectsFor(0L, false)) {
            assertFalse(s.expired().test(now, false), s.name() + " treated 0 as an expiry instant at " + now);
            assertTrue(s.valid().test(now, false), s.name() + " invalidated a never-expiring token at " + now);
        }
    }

    /** {@code isValid} is exactly "not revoked and not expired" — the two never drift apart. */
    @HegelTest
    void validIsExactlyNotRevokedAndNotExpired(TestCase tc) {
        long expiresAt = drawInstant(tc, "expiresAt");
        boolean revoked = tc.draw(booleans(), "revoked");
        long now = drawInstant(tc, "now");

        for (Subject s : subjectsFor(expiresAt, revoked)) {
            boolean expected = !revoked && !s.expired().test(now, revoked);
            assertEquals(expected, s.valid().test(now, revoked), s.name() + " at now=" + now);
        }
    }

    /** Both token types agree — a divergence between them is an access-control inconsistency. */
    @HegelTest
    void bothTokenTypesAgreeOnValidity(TestCase tc) {
        long expiresAt = drawInstant(tc, "expiresAt");
        boolean revoked = tc.draw(booleans(), "revoked");
        long now = drawInstant(tc, "now");

        List<Subject> subjects = subjectsFor(expiresAt, revoked);
        boolean first = subjects.get(0).valid().test(now, revoked);

        for (Subject s : subjects) {
            assertEquals(first, s.valid().test(now, revoked), "token types disagree: " + s.name());
        }
    }
}
