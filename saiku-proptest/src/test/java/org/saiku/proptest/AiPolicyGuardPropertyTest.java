/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.Locale;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;

/**
 * Property-based tests for the AI data-egress policy ladder (saiku#903).
 *
 * <p>{@link AiPolicyGuard} decides whether a class of data may cross the trust boundary — schema
 * metadata, sample members, aggregated cell values, raw drillthrough rows. It is a two-enum lattice
 * ({@link AiPolicy} tiers x {@link AiDataKind} minimums), and lattices are where off-by-one
 * comparisons hide: a single {@code <} that should be {@code <=} silently leaks a whole data class.
 *
 * <p>Example tests pin specific pairs. These properties assert the ORDERING itself holds across
 * every pair simultaneously, which is the actual security claim:
 *
 * <blockquote>
 * raising the policy tier can only ever grant, never revoke; and no tier grants a kind that
 * requires a higher tier.
 * </blockquote>
 */
class AiPolicyGuardPropertyTest {

    private static final List<AiPolicy> POLICIES = List.of(AiPolicy.values());
    private static final List<AiDataKind> KINDS = List.of(AiDataKind.values());

    /** The defining rule, asserted directly: a kind is sendable exactly when the tier reaches it. */
    @HegelTest
    void canSendIsExactlyTierReachesMinimum(TestCase tc) {
        AiPolicy policy = tc.draw(sampledFrom(POLICIES), "policy");
        AiDataKind kind = tc.draw(sampledFrom(KINDS), "kind");

        boolean expected = kind.minPolicy().ordinal() <= policy.ordinal();

        assertEquals(expected, new AiPolicyGuard(policy).canSend(kind), policy + " vs " + kind);
    }

    /**
     * Monotonicity. If a tier permits a kind, EVERY higher tier permits it too. A violation here
     * means the ladder has a hole — some middle tier refusing what a lower tier allowed.
     */
    @HegelTest
    void raisingThePolicyNeverRevokesPermission(TestCase tc) {
        AiPolicy lower = tc.draw(sampledFrom(POLICIES), "lower");
        AiPolicy higher = tc.draw(sampledFrom(POLICIES), "higher");
        tc.assume(lower.ordinal() <= higher.ordinal());

        AiPolicyGuard low = new AiPolicyGuard(lower);
        AiPolicyGuard high = new AiPolicyGuard(higher);

        for (AiDataKind kind : KINDS) {
            if (low.canSend(kind)) {
                assertTrue(high.canSend(kind), higher + " revoked " + kind + " that " + lower + " allowed");
            }
        }
    }

    /**
     * The floor. {@code schema-only} is the default posture and the one an unconfigured install
     * runs on, so it must never release actual data — only metadata.
     */
    @HegelTest
    void schemaOnlyNeverReleasesDataValues(TestCase tc) {
        AiDataKind kind =
                tc.draw(sampledFrom(List.of(AiDataKind.AGGREGATED_RESULT_VALUES, AiDataKind.RAW_ROW_DATA)), "kind");

        AiPolicyGuard guard = new AiPolicyGuard(AiPolicy.SCHEMA_ONLY);

        assertFalse(guard.canSend(kind), "schema-only released " + kind);
        assertThrows(RuntimeException.class, () -> guard.assertCanSend(kind));
    }

    /** Raw fact rows are the most sensitive kind: only the top tier may release them. */
    @HegelTest
    void rawRowDataRequiresTheTopTier(TestCase tc) {
        AiPolicy policy = tc.draw(sampledFrom(POLICIES), "policy");

        boolean allowed = new AiPolicyGuard(policy).canSend(AiDataKind.RAW_ROW_DATA);

        assertEquals(policy == AiPolicy.FULL, allowed, policy + " on RAW_ROW_DATA");
    }

    /** {@code assertCanSend} throws exactly when {@code canSend} is false — never diverging. */
    @HegelTest
    void assertCanSendAgreesWithCanSend(TestCase tc) {
        AiPolicy policy = tc.draw(sampledFrom(POLICIES), "policy");
        AiDataKind kind = tc.draw(sampledFrom(KINDS), "kind");

        AiPolicyGuard guard = new AiPolicyGuard(policy);

        if (guard.canSend(kind)) {
            guard.assertCanSend(kind); // must not throw
        } else {
            assertThrows(RuntimeException.class, () -> guard.assertCanSend(kind));
        }
    }

    /**
     * Configuration parsing is insensitive to case and to {@code -}/{@code _}, so an operator can't
     * accidentally get a MORE permissive tier than they typed. Generated over every spelling of
     * every tier name.
     */
    @HegelTest
    void policyParsingIsCaseAndSeparatorInsensitive(TestCase tc) {
        AiPolicy expected = tc.draw(sampledFrom(POLICIES), "expected");
        String separator = tc.draw(sampledFrom(List.of("-", "_")), "separator");
        boolean upper = tc.draw(dev.hegel.Generators.booleans(), "upper");

        String raw = expected.name().replace('_', separator.charAt(0));
        raw = upper ? raw.toUpperCase(Locale.ROOT) : raw.toLowerCase(Locale.ROOT);
        String padded = "  " + raw + "  ";

        assertSame(expected, AiPolicy.parse(padded, "ENV", "prop"), "failed to parse " + padded);
    }

    /**
     * Anything that isn't a tier name is rejected outright. Fail-closed matters more than
     * fail-friendly here: silently treating a typo as a default could either break an install or,
     * worse, be read as a permissive tier.
     */
    @HegelTest
    void unrecognisedPolicyValuesAreRejected(TestCase tc) {
        String junk = tc.draw(fromRegex("[a-zA-Z0-9_-]{1,20}"), "junk");
        String norm = junk.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        tc.assume(!norm.equals("schema_only") && !norm.equals("aggregated") && !norm.equals("full"));
        tc.assume(!junk.isBlank());

        assertThrows(IllegalArgumentException.class, () -> AiPolicy.parse(junk, "ENV", "prop"));
    }

    /** An absent or blank setting falls back to the default tier rather than throwing. */
    @HegelTest
    void blankConfigurationFallsBackToTheDefault(TestCase tc) {
        String blank = tc.draw(sampledFrom(List.of("", " ", "\t", "\n", "   ")), "blank");

        assertSame(AiPolicy.DEFAULT, AiPolicy.parse(blank, "ENV", "prop"));
    }

    /** The default posture is the most restrictive tier — an unconfigured install must not leak. */
    @HegelTest
    void theDefaultTierIsTheMostRestrictive(TestCase tc) {
        AiPolicy any = tc.draw(sampledFrom(POLICIES), "any");

        assertTrue(
                AiPolicy.DEFAULT.ordinal() <= any.ordinal(),
                "default " + AiPolicy.DEFAULT + " is more permissive than " + any);
    }

    /** Env beats property beats default — a deployment can always tighten via the environment. */
    @HegelTest
    void environmentOverridesTheSystemProperty(TestCase tc) {
        AiPolicy envPolicy = tc.draw(sampledFrom(POLICIES), "envPolicy");
        AiPolicy propPolicy = tc.draw(sampledFrom(POLICIES), "propPolicy");

        String envValue = envPolicy.name().toLowerCase(Locale.ROOT).replace('_', '-');
        String propValue = propPolicy.name().toLowerCase(Locale.ROOT).replace('_', '-');

        AiPolicyGuard guard = AiPolicyGuard.from(k -> AiPolicy.ENV.equals(k) ? envValue : null, k -> propValue);

        assertSame(envPolicy, guard.current(), "env did not win over property");
    }
}
