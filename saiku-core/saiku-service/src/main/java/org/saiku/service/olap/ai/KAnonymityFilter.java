/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * K-anonymity small-cell suppression (saiku#905). Masks any aggregated cell
 * whose underlying row count is below {@code ai.kAnonymity} (default 5) before
 * the result crosses the AI boundary — the standard small-cell control from
 * healthcare/government statistical releases. A "SUM of salary by department"
 * looks anonymised until a department of one makes the sum that person's salary;
 * this closes that leak, which the PII annotation (#902) and policy switch
 * (#903) cannot reach because they operate on schema/policy, not cardinality.
 *
 * <p>Config (env &gt; system property &gt; default), fail-soft to the SAFE
 * default on a bad value:
 * <ul>
 *   <li>{@code SAIKU_AI_KANONYMITY} / {@code ai.kAnonymity} — threshold k
 *       (default 5; {@code 0} disables; negative/invalid → default 5).</li>
 *   <li>{@code SAIKU_AI_KANONYMITY_MASK} / {@code ai.kAnonymity.maskValue} —
 *       {@code null} (default → value null, formatted {@code —}),
 *       a number like {@code -1}, or any token e.g. {@code REDACTED} /
 *       {@code <5}.</li>
 * </ul>
 *
 * <p>This class only decides + masks; deriving the per-cell row count is the
 * caller's job (saiku#905 v1: an in-result count measure; the shadow-count
 * query for arbitrary cubes is the follow-up).
 */
public class KAnonymityFilter {

    private static final Logger log = LoggerFactory.getLogger(KAnonymityFilter.class);

    public static final String ENV_K = "SAIKU_AI_KANONYMITY";
    public static final String PROP_K = "ai.kAnonymity";
    public static final int DEFAULT_K = 5;
    public static final String ENV_MASK = "SAIKU_AI_KANONYMITY_MASK";
    public static final String PROP_MASK = "ai.kAnonymity.maskValue";
    public static final String DEFAULT_MASK = "null";

    private final int threshold;
    private final String maskValue;

    /** Production constructor — resolves config from the real environment +
     *  system properties and logs the active posture once at boot. */
    public KAnonymityFilter() {
        this(resolveThreshold(System::getenv, System::getProperty), resolveMask(System::getenv, System::getProperty));
        if (enabled()) {
            log.info("AI k-anonymity suppression ENABLED (k={}, mask={})", threshold, maskValue);
        } else {
            log.info("AI k-anonymity suppression DISABLED ({}=0)", PROP_K);
        }
    }

    /** Explicit constructor for tests / programmatic wiring. */
    public KAnonymityFilter(int threshold, String maskValue) {
        this.threshold = Math.max(0, threshold);
        this.maskValue = (maskValue == null || maskValue.isBlank()) ? DEFAULT_MASK : maskValue.trim();
    }

    /** Testable factory (env + property lookups injected). */
    public static KAnonymityFilter from(Function<String, String> env, Function<String, String> prop) {
        return new KAnonymityFilter(resolveThreshold(env, prop), resolveMask(env, prop));
    }

    static int resolveThreshold(Function<String, String> env, Function<String, String> prop) {
        String raw = env.apply(ENV_K);
        if (raw == null || raw.isBlank()) {
            raw = prop.apply(PROP_K);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_K;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 0) {
                log.warn("Invalid {} '{}' (negative) — defaulting to {}", PROP_K, raw, DEFAULT_K);
                return DEFAULT_K;
            }
            return v;
        } catch (NumberFormatException e) {
            // Fail SAFE: an unparseable threshold protects at the default rather
            // than silently disabling suppression.
            log.warn("Invalid {} '{}' (not an integer) — defaulting to {}", PROP_K, raw, DEFAULT_K);
            return DEFAULT_K;
        }
    }

    static String resolveMask(Function<String, String> env, Function<String, String> prop) {
        String raw = env.apply(ENV_MASK);
        if (raw == null || raw.isBlank()) {
            raw = prop.apply(PROP_MASK);
        }
        return (raw == null || raw.isBlank()) ? DEFAULT_MASK : raw.trim();
    }

    public int threshold() {
        return threshold;
    }

    public String maskValue() {
        return maskValue;
    }

    /** Suppression is active only when k &gt; 0. */
    public boolean enabled() {
        return threshold > 0;
    }

    /**
     * True when a cell backed by {@code rowCount} underlying records must be
     * masked: enabled, the count is known ({@code &gt; 0}) and below k. A count
     * of exactly k is NOT masked (inclusive), and an unknown count
     * ({@code &lt;= 0}) is left to the caller (we don't mask what we can't
     * measure).
     */
    public boolean shouldSuppress(int rowCount) {
        return enabled() && rowCount > 0 && rowCount < threshold;
    }

    /** Mask a single cell in place per the configured {@code maskValue} and
     *  flag it suppressed. */
    public void mask(AiCell cell) {
        if (cell == null) {
            return;
        }
        cell.setSuppressed(true);
        cell.setUnit(null);
        if ("null".equalsIgnoreCase(maskValue)) {
            cell.setValue(null);
            cell.setFormatted("—");
            return;
        }
        try {
            double num = Double.parseDouble(maskValue);
            cell.setValue(num);
            cell.setFormatted(maskValue);
        } catch (NumberFormatException notNumeric) {
            // A token like REDACTED or "<5": no numeric value, show the token.
            cell.setValue(null);
            cell.setFormatted(maskValue);
        }
    }

    /**
     * Apply suppression to a records-format result (saiku#905 v1). For each row
     * whose {@code countKey} measure cell reports a row count below k, mask
     * every measure cell in that row (including the count cell — a count of 3
     * is itself disclosive). Row-header (String) values are untouched.
     *
     * @param rows the records payload ({@code Map<caption, AiCell|String>} per row)
     * @param countKey the measure caption carrying the underlying row count
     * @param measureKeys the measure-column captions to mask when the row is small
     * @return number of rows suppressed
     */
    public int applyToRecords(List<Map<String, Object>> rows, String countKey, Collection<String> measureKeys) {
        if (!enabled() || rows == null || countKey == null || measureKeys == null) {
            return 0;
        }
        int suppressed = 0;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object countCell = row.get(countKey);
            if (!(countCell instanceof AiCell)) {
                continue;
            }
            Double cv = ((AiCell) countCell).getValue();
            if (cv == null) {
                continue;
            }
            int rowCount = (int) Math.round(cv);
            if (!shouldSuppress(rowCount)) {
                continue;
            }
            for (String mk : measureKeys) {
                Object c = row.get(mk);
                if (c instanceof AiCell) {
                    mask((AiCell) c);
                }
            }
            suppressed++;
        }
        if (suppressed > 0) {
            log.debug("k-anon suppressed {} small cell-row(s) (k={})", suppressed, threshold);
        }
        return suppressed;
    }

    /** Lowercase display name of the configured mask, for {@code /info}. */
    public String describe() {
        return enabled() ? ("k=" + threshold + ",mask=" + maskValue.toLowerCase(Locale.ROOT)) : "disabled";
    }
}
