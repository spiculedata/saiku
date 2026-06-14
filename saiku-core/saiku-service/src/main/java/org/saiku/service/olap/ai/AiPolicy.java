/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.Locale;
import java.util.function.Function;

/**
 * Operator-level data-exposure tier for the AI surface (saiku#903). Ordered
 * from most restrictive to least; a higher ordinal permits everything a lower
 * one does plus more:
 *
 * <ul>
 *   <li>{@link #SCHEMA_ONLY} — only cube metadata + PII-filtered sample members
 *       may leave the box (Tier-1 features only). The safe default.</li>
 *   <li>{@link #AGGREGATED} — schema + aggregated query result values
 *       ({@code /ai/query} typed cells). Tier-2 unlocked.</li>
 *   <li>{@link #FULL} — everything, including raw drillthrough rows.
 *       Single-tenant trusted environments only.</li>
 * </ul>
 *
 * <p>Companion to {@code saiku.semantic.pii} (saiku#902): the annotation says
 * <em>what</em> is PII; this policy says <em>what tier of data can leave at
 * all</em>. Resolution order is env {@code SAIKU_AI_POLICY} &gt; system property
 * {@code ai.policy} &gt; default {@link #SCHEMA_ONLY}.
 */
public enum AiPolicy {
    SCHEMA_ONLY,
    AGGREGATED,
    FULL;

    /** System property key. */
    public static final String PROP = "ai.policy";

    /** Environment variable key (takes precedence over the property). */
    public static final String ENV = "SAIKU_AI_POLICY";

    /** Default when nothing is configured — fail closed to the safest tier. */
    public static final AiPolicy DEFAULT = SCHEMA_ONLY;

    /**
     * Parse a configured value strictly. Blank/null returns {@link #DEFAULT};
     * an unrecognised value throws {@link IllegalArgumentException} so startup
     * fails fast rather than silently falling back to a wrong posture.
     * Accepts {@code schema-only}/{@code schema_only}, {@code aggregated},
     * {@code full} case-insensitively.
     */
    public static AiPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        switch (norm) {
            case "schema_only":
                return SCHEMA_ONLY;
            case "aggregated":
                return AGGREGATED;
            case "full":
                return FULL;
            default:
                throw new IllegalArgumentException("Invalid " + PROP + " / " + ENV + " value '" + raw
                        + "' — expected one of: schema-only, aggregated, full");
        }
    }

    /**
     * Resolve the active policy from env &gt; property &gt; default. The lookups
     * are injected so this is unit-testable without touching real process
     * state. Throws {@link IllegalArgumentException} on an invalid configured
     * value (fail-fast).
     */
    public static AiPolicy resolve(Function<String, String> env, Function<String, String> prop) {
        String fromEnv = env.apply(ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return parse(fromEnv);
        }
        return parse(prop.apply(PROP));
    }

    /** The wire/display form: lowercase, hyphenated (e.g. {@code schema-only}). */
    public String displayName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
