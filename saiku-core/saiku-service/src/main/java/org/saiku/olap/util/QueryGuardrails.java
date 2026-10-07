/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */
package org.saiku.olap.util;

import java.sql.SQLException;
import java.sql.Statement;
import org.olap4j.OlapStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side guardrails for OLAP execution (saiku#1914, CWE-400 / CWE-770).
 *
 * <p>Before this class every execution path was unbounded: Mondrian ran with
 * {@code mondrian.rolap.queryTimeout=0}, {@code mondrian.result.limit=0} and
 * {@code mondrian.rolap.iterationLimit=0}, and no code path called
 * {@code setQueryTimeout}. Any authenticated user could therefore submit an MDX
 * {@code CROSSJOIN} over high-cardinality hierarchies (or a {@code DRILLTHROUGH}
 * without {@code MAXROWS}) that ran until it exhausted the query thread pool and
 * the heap — a single-request DoS.
 *
 * <p>Values are read from {@code saiku.properties} (with the usual
 * {@code -Dsaiku.*} system-property override, see {@link SaikuProperties}):
 *
 * <ul>
 *   <li>{@code saiku.olap.query.timeout.seconds} — default {@value #DEFAULT_QUERY_TIMEOUT_SECONDS}.
 *       Applied to every {@link OlapStatement} this class touches.</li>
 *   <li>{@code saiku.olap.max.rows} — default {@value #DEFAULT_MAX_ROWS}. Server-side
 *       ceiling on drillthrough / export rows. A client asking for more (or for
 *       "everything", i.e. {@code maxrows <= 0}) gets this value, so the cap
 *       cannot be bypassed by omitting or inflating the request parameter.</li>
 *   <li>{@code saiku.olap.arrow.max.bytes} — default {@value #DEFAULT_ARROW_ALLOCATOR_BYTES}.
 *       Byte budget handed to the Arrow {@code RootAllocator}s that back the
 *       cellset / drillthrough IPC writers. Previously {@code new RootAllocator()}
 *       with {@code Long.MAX_VALUE}, so a huge result could allocate without limit.</li>
 * </ul>
 *
 * <p>Set {@code saiku.olap.query.timeout.seconds=0} to disable the statement
 * timeout (Mondrian's own {@code mondrian.rolap.queryTimeout} in
 * {@code mondrian.properties} is the coarser, deployment-wide backstop).
 */
public final class QueryGuardrails {

    private static final Logger log = LoggerFactory.getLogger(QueryGuardrails.class);

    public static final String KEY_QUERY_TIMEOUT_SECONDS = "saiku.olap.query.timeout.seconds";
    public static final String KEY_MAX_ROWS = "saiku.olap.max.rows";
    public static final String KEY_ARROW_ALLOCATOR_BYTES = "saiku.olap.arrow.max.bytes";

    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_MAX_ROWS = 100_000;
    public static final long DEFAULT_ARROW_ALLOCATOR_BYTES = 268_435_456L; // 256 MiB

    /** Hard ceiling for {@link #clampMaxRows(int)} so a bad config can't disable the cap. */
    private static final int MAX_ROWS_CEILING = 10_000_000;

    private QueryGuardrails() {}

    /**
     * Statement timeout in seconds; {@code 0} means "no timeout" (operator opt-out).
     * A negative or unparseable configured value falls back to the default rather
     * than silently disabling the guardrail.
     */
    public static int queryTimeoutSeconds() {
        int v = intProp(KEY_QUERY_TIMEOUT_SECONDS, DEFAULT_QUERY_TIMEOUT_SECONDS);
        if (v < 0) {
            log.warn(
                    "{}={} is negative; using default {}", KEY_QUERY_TIMEOUT_SECONDS, v, DEFAULT_QUERY_TIMEOUT_SECONDS);
            return DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
        return v;
    }

    /** Server-side row ceiling for drillthrough / export ({@value #DEFAULT_MAX_ROWS} by default). */
    public static int maxRows() {
        int v = intProp(KEY_MAX_ROWS, DEFAULT_MAX_ROWS);
        if (v <= 0) {
            log.warn("{}={} is not positive; using default {}", KEY_MAX_ROWS, v, DEFAULT_MAX_ROWS);
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(v, MAX_ROWS_CEILING);
    }

    /** Byte budget for Arrow allocators ({@value #DEFAULT_ARROW_ALLOCATOR_BYTES} by default). */
    public static long arrowAllocatorBytes() {
        long v = longProp(KEY_ARROW_ALLOCATOR_BYTES, DEFAULT_ARROW_ALLOCATOR_BYTES);
        if (v <= 0) {
            log.warn(
                    "{}={} is not positive; using default {}",
                    KEY_ARROW_ALLOCATOR_BYTES,
                    v,
                    DEFAULT_ARROW_ALLOCATOR_BYTES);
            return DEFAULT_ARROW_ALLOCATOR_BYTES;
        }
        return v;
    }

    /**
     * Clamp a client-supplied row cap to the server-side ceiling (saiku#1914).
     *
     * <p>{@code requested <= 0} means "no cap" (the pre-#1914 behaviour) and maps
     * to the configured ceiling; a request above the ceiling is truncated down to
     * it. The result is always in {@code [1, maxRows()]}.
     */
    public static int clampMaxRows(int requested) {
        int cap = maxRows();
        if (requested <= 0) {
            return cap;
        }
        return Math.min(requested, cap);
    }

    /**
     * Apply the configured query timeout to an OLAP statement. Never throws: a
     * driver that doesn't implement {@code setQueryTimeout} must not fail the
     * query (Mondrian's own timeout still applies). A timeout of 0 is a no-op.
     */
    public static void applyQueryTimeout(OlapStatement stmt) {
        applyQueryTimeout((Statement) stmt);
    }

    /** @see #applyQueryTimeout(OlapStatement) */
    public static void applyQueryTimeout(Statement stmt) {
        if (stmt == null) {
            return;
        }
        int seconds = queryTimeoutSeconds();
        if (seconds <= 0) {
            return;
        }
        try {
            stmt.setQueryTimeout(seconds);
        } catch (SQLException | RuntimeException e) {
            // SQLFeatureNotSupportedException on drivers without the hook; the
            // deployment-wide mondrian.rolap.queryTimeout still bounds the query.
            log.debug("setQueryTimeout({}) unsupported on this statement", seconds, e);
        }
    }

    /**
     * Raw value for {@code key}: live system property first, then
     * {@code saiku.properties}.
     *
     * <p>The system property is read live rather than relying on
     * {@link SaikuProperties}' one-shot class-init copy, so an operator override
     * applied after startup (and a unit test's {@code System.setProperty}) both take
     * effect. {@code saiku.properties} remains the deployment default.
     */
    private static String rawProp(String key) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        return SaikuProperties.getPropString(key, null);
    }

    private static int intProp(String key, int defaultValue) {
        String raw = rawProp(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("{}={} is not an integer; using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static long longProp(String key, long defaultValue) {
        String raw = rawProp(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("{}={} is not a long; using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }
}
