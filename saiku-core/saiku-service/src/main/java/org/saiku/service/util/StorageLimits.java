/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util;

import org.saiku.olap.util.SaikuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side size caps for stored objects (issue #2535, CWE-400): repository
 * objects, dashboard history entries and comments.
 *
 * <p>Before this class the only caps on the REST surface were per-endpoint ones
 * (preferences 64 KB, images 2 MiB, zip upload 10/50 MB — all applied after the
 * body has been read) and the Jetty form-parse backstop
 * ({@code saiku.http.maxFormContentSize}, 2 MB, form parsing only). Jersey reads
 * request entities itself, so JSON/text/multipart REST bodies had no ceiling
 * before the resource ran, the repository stored whatever a {@code POST
 * /saiku/api/repository/resource} carried, and dashboard history and comments
 * grew without bound.
 *
 * <p>The REST request-body ceilings themselves are the {@code saiku.http.*}
 * keys read by {@code org.saiku.web.servlet.RestBodySizeFilter} (they are a
 * web-layer concern); this class holds the caps on what gets <em>stored</em>:
 *
 * <ul>
 *   <li>{@code saiku.repo.maxObjectBytes} — a single repository object written
 *       through {@code DatasourceService.saveFile} (a {@code .saiku}, {@code
 *       .saikudash}, upload entry, image asset, ...).</li>
 *   <li>{@code saiku.history.maxEntryBytes} — one archived dashboard version
 *       ({@code DashboardHistoryService.archive}); an oversized dashboard is
 *       simply not archived, the save itself still succeeds.</li>
 *   <li>{@code saiku.comments.maxBodyBytes} — one comment body.</li>
 *   <li>{@code saiku.comments.maxCount} — live (non-deleted) comments per
 *       dashboard.</li>
 * </ul>
 *
 * <p>Values are read live (system property first, then {@code saiku.properties})
 * so a test's {@code System.setProperty} and an operator override both take
 * effect; a missing, unparseable or out-of-range value falls back to the
 * default rather than silently disabling the cap.
 */
public final class StorageLimits {

    private static final Logger log = LoggerFactory.getLogger(StorageLimits.class);

    public static final String KEY_REPO_OBJECT_BYTES = "saiku.repo.maxObjectBytes";
    public static final String KEY_HISTORY_ENTRY_BYTES = "saiku.history.maxEntryBytes";
    public static final String KEY_COMMENT_BODY_BYTES = "saiku.comments.maxBodyBytes";
    public static final String KEY_COMMENT_MAX_COUNT = "saiku.comments.maxCount";

    /** Default cap on one repository object: {@value #DEFAULT_REPO_OBJECT_BYTES} bytes (10 MiB). */
    public static final long DEFAULT_REPO_OBJECT_BYTES = 10L * 1024 * 1024;
    /** Default cap on one archived dashboard version: {@value #DEFAULT_HISTORY_ENTRY_BYTES} bytes (4 MiB). */
    public static final long DEFAULT_HISTORY_ENTRY_BYTES = 4L * 1024 * 1024;
    /** Default cap on one comment body: {@value #DEFAULT_COMMENT_BODY_BYTES} bytes (8 KiB). */
    public static final long DEFAULT_COMMENT_BODY_BYTES = 8L * 1024;
    /** Default cap on live comments per dashboard: {@value #DEFAULT_COMMENT_MAX_COUNT}. */
    public static final int DEFAULT_COMMENT_MAX_COUNT = 500;

    /** Floors and ceilings so a bad config can neither disable nor absurdly widen a cap. */
    static final long MIN_REPO_OBJECT_BYTES = 64L * 1024;

    static final long MAX_REPO_OBJECT_BYTES = 512L * 1024 * 1024;
    static final long MIN_HISTORY_ENTRY_BYTES = 1024;
    static final long MAX_HISTORY_ENTRY_BYTES = 512L * 1024 * 1024;
    static final long MIN_COMMENT_BODY_BYTES = 1024;
    static final long MAX_COMMENT_BODY_BYTES = 1024 * 1024;
    static final int MIN_COMMENT_MAX_COUNT = 10;
    static final int MAX_COMMENT_MAX_COUNT = 10_000;

    private StorageLimits() {}

    /** Cap on one repository object written through the save-file path. */
    public static long repoObjectBytes() {
        return longProp(KEY_REPO_OBJECT_BYTES, DEFAULT_REPO_OBJECT_BYTES, MIN_REPO_OBJECT_BYTES, MAX_REPO_OBJECT_BYTES);
    }

    /** Cap on one archived dashboard version (an oversized version is not archived). */
    public static long historyEntryBytes() {
        return longProp(
                KEY_HISTORY_ENTRY_BYTES, DEFAULT_HISTORY_ENTRY_BYTES, MIN_HISTORY_ENTRY_BYTES, MAX_HISTORY_ENTRY_BYTES);
    }

    /** Cap on one comment body (an oversized comment is refused). */
    public static long commentBodyBytes() {
        return longProp(
                KEY_COMMENT_BODY_BYTES, DEFAULT_COMMENT_BODY_BYTES, MIN_COMMENT_BODY_BYTES, MAX_COMMENT_BODY_BYTES);
    }

    /** Cap on live (non-deleted) comments per dashboard. */
    public static int commentMaxCount() {
        String raw = rawProp(KEY_COMMENT_MAX_COUNT);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_COMMENT_MAX_COUNT;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < MIN_COMMENT_MAX_COUNT) {
                log.warn(
                        "{}={} is below the floor {}; using the floor",
                        KEY_COMMENT_MAX_COUNT,
                        v,
                        MIN_COMMENT_MAX_COUNT);
                return MIN_COMMENT_MAX_COUNT;
            }
            return Math.min(v, MAX_COMMENT_MAX_COUNT);
        } catch (NumberFormatException e) {
            log.warn(
                    "{}={} is not an integer; using default {}", KEY_COMMENT_MAX_COUNT, raw, DEFAULT_COMMENT_MAX_COUNT);
            return DEFAULT_COMMENT_MAX_COUNT;
        }
    }

    /**
     * Raw value for {@code key}: live system property first, then
     * {@code saiku.properties}. The system property is read live so an operator
     * override applied after startup (and a unit test's {@code System.setProperty})
     * both take effect.
     */
    private static String rawProp(String key) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        return SaikuProperties.getPropString(key, null);
    }

    private static long longProp(String key, long defaultValue, long min, long max) {
        String raw = rawProp(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long v = Long.parseLong(raw.trim());
            if (v < min) {
                log.warn("{}={} is below the floor {}; using the floor", key, v, min);
                return min;
            }
            if (v > max) {
                log.warn("{}={} is above the ceiling {}; using the ceiling", key, v, max);
                return max;
            }
            return v;
        } catch (NumberFormatException e) {
            log.warn("{}={} is not an integer; using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }
}
