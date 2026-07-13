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
