/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central gate for what tier of data the AI surface may expose (saiku#903).
 * Every AI endpoint that returns data consults this guard with the
 * {@link AiDataKind} it is about to send; the guard throws
 * {@link AiPolicyViolation} when the active {@link AiPolicy} is too restrictive.
 *
 * <p>A CISO buys knobs, not chat boxes — this is the knob. Resolved once at
 * construction from env {@code SAIKU_AI_POLICY} &gt; system property
 * {@code ai.policy} &gt; default {@code schema-only}, and an invalid configured
 * value throws here so the application <b>fails fast at startup</b> rather than
 * booting with an ambiguous posture. Pairs with the saiku#902 PII annotation.
 */
public class AiPolicyGuard {

    private static final Logger log = LoggerFactory.getLogger(AiPolicyGuard.class);

    /**
     * Environment variable key for the DEDICATED LLM-egress guard — "may cell
     * data leave the box to a third-party LLM vendor?" — resolved independently
     * of the data-return {@link AiPolicy#ENV} guard. Takes precedence over
     * {@link #LLM_EGRESS_PROP}.
     */
    public static final String LLM_EGRESS_ENV = "SAIKU_AI_LLM_EGRESS";

    /** System property key for the LLM-egress guard (see {@link #LLM_EGRESS_ENV}). */
    public static final String LLM_EGRESS_PROP = "ai.llm.egress";

    private final AiPolicy current;

    /** Production constructor — resolves from the real environment + system
     *  properties and logs the active posture once at boot. Throws if the
     *  configured value is invalid (fail-fast). */
    public AiPolicyGuard() {
        this(AiPolicy.resolve(System::getenv, System::getProperty));
        log.info(
                "AI data policy: {} (set via {} / {}; default schema-only)",
                current.displayName(),
                AiPolicy.ENV,
                AiPolicy.PROP);
    }

    /** Explicit-policy constructor for tests / programmatic wiring. */
    public AiPolicyGuard(AiPolicy policy) {
        this.current = policy == null ? AiPolicy.DEFAULT : policy;
    }

    /** Testable resolution seam (env + property lookups injected). */
    public static AiPolicyGuard from(Function<String, String> env, Function<String, String> prop) {
        return new AiPolicyGuard(AiPolicy.resolve(env, prop));
    }

    /**
     * Production factory for the DEDICATED LLM-egress guard. Resolves from
     * {@link #LLM_EGRESS_ENV} &gt; {@link #LLM_EGRESS_PROP} &gt; default
     * {@code schema-only}, logs the active egress posture once at boot (mirroring
     * the data-policy boot log), and throws on an invalid configured value so the
     * app fails fast at startup. Separate from the data-return {@link AiPolicy}
     * guard: this answers "may cell data egress to a third-party LLM vendor?"
     * independently of "may aggregated data return to the authenticated caller?".
     */
    public static AiPolicyGuard forLlmEgress() {
        AiPolicyGuard guard = fromLlmEgress(System::getenv, System::getProperty);
        log.info(
                "AI LLM egress policy: {} (set via {} / {}; default schema-only)",
                guard.current.displayName(),
                LLM_EGRESS_ENV,
                LLM_EGRESS_PROP);
        return guard;
    }

    /** Testable resolution seam for the LLM-egress guard (env + property injected). */
    public static AiPolicyGuard fromLlmEgress(Function<String, String> env, Function<String, String> prop) {
        return new AiPolicyGuard(AiPolicy.resolve(env, prop, LLM_EGRESS_ENV, LLM_EGRESS_PROP));
    }

    /** The active policy tier. */
    public AiPolicy current() {
        return current;
    }

    /** True if {@code kind} may be exposed under the active policy. */
    public boolean canSend(AiDataKind kind) {
        return kind.minPolicy().ordinal() <= current.ordinal();
    }

    /**
     * Enforce the policy for a piece of data about to leave the box. Call this
     * BEFORE executing/serialising the data — at the top of the endpoint, before
     * any try/catch that would otherwise swallow it into a generic error.
     *
     * @throws AiPolicyViolation if the active policy is below {@code kind}'s
     *     minimum tier.
     */
    public void assertCanSend(AiDataKind kind) {
        if (!canSend(kind)) {
            throw new AiPolicyViolation(kind, current);
        }
    }
}
