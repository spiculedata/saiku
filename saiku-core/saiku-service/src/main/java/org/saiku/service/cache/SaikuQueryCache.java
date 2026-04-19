/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License. You may
 *   obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */
package org.saiku.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.saiku.olap.util.SaikuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disk-backed Arrow query cache with an in-memory Caffeine index of metadata.
 *
 * <p>Layout under {@code <saiku-home>/cache/}:
 * <pre>
 *   &lt;sha&gt;.arrow       // raw Arrow IPC bytes
 *   &lt;sha&gt;.meta.json   // {key, createdAt, runtimeMs, rows, cubeVersion, bytes}
 * </pre>
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #get(String, Supplier)} returns cached bytes on hit, otherwise
 *       invokes the supplier, stores, and returns. TTL-expired entries are
 *       deleted lazily on access.</li>
 *   <li>{@link #invalidate(String)} removes both files and the in-memory entry.</li>
 *   <li>{@link #put(String, Entry)} is a lower-level primitive used when the
 *       caller already has the bytes and wants a write-through.</li>
 *   <li>Eviction: on write, if total on-disk bytes exceed
 *       {@code maxSizeBytes}, the oldest entries (by {@code createdAt}) are
 *       deleted until the total is below the limit.</li>
 * </ul>
 *
 * <p>Thread-safety: the cache is safe for concurrent use. A per-instance lock
 * serialises writes + eviction — Saiku query volumes are low enough that the
 * simplicity is worth the small contention cost.
 */
public class SaikuQueryCache {

    private static final Logger log = LoggerFactory.getLogger(SaikuQueryCache.class);

    private static final String ARROW_SUFFIX = ".arrow";
    private static final String META_SUFFIX = ".meta.json";

    private final Path cacheDir;
    private final long ttlMillis;
    private final long maxSizeBytes;
    private final boolean enabled;

    private final Cache<String, Entry> index;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock writeLock = new ReentrantLock();

    /** Metadata sidecar record. Public for Jackson. */
    public static final class Entry {
        public String key;
        public long createdAt;
        public long runtimeMs;
        public int rows;
        public String cubeVersion;
        public long bytes;

        public Entry() {}

        public Entry(String key, long createdAt, long runtimeMs, int rows, String cubeVersion, long bytes) {
            this.key = key;
            this.createdAt = createdAt;
            this.runtimeMs = runtimeMs;
            this.rows = rows;
            this.cubeVersion = cubeVersion;
            this.bytes = bytes;
        }
    }

    /** Returned by the service layer to describe a cache lookup result. */
    public static final class CachedQueryResult {
        public final byte[] arrowBytes;
        public final long runtimeMs;
        public final int rows;
        public final boolean cacheHit;

        public CachedQueryResult(byte[] arrowBytes, long runtimeMs, int rows, boolean cacheHit) {
            this.arrowBytes = arrowBytes;
            this.runtimeMs = runtimeMs;
            this.rows = rows;
            this.cacheHit = cacheHit;
        }
    }

    public SaikuQueryCache() {
        this(
                resolveDefaultCacheDir(),
                TimeUnit.MINUTES.toMillis(SaikuProperties.cacheTtlMinutes),
                SaikuProperties.cacheMaxSizeBytes,
                SaikuProperties.cacheEnabled);
    }

    public SaikuQueryCache(Path cacheDir, long ttlMillis, long maxSizeBytes, boolean enabled) {
        this.cacheDir = cacheDir;
        this.ttlMillis = ttlMillis;
        this.maxSizeBytes = maxSizeBytes;
        this.enabled = enabled;
        this.index = Caffeine.newBuilder().maximumSize(512).build();
        try {
            Files.createDirectories(cacheDir);
            // Warm the in-memory index from disk so eviction sees existing entries.
            reindexFromDisk();
        } catch (IOException e) {
            log.warn("SaikuQueryCache: cannot prepare cache dir {} — cache disabled: {}", cacheDir, e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    private static Path resolveDefaultCacheDir() {
        String home = System.getProperty("saiku.home");
        Path base = (home != null && !home.isEmpty()) ? Paths.get(home) : Paths.get("saiku-home");
        return base.resolve("cache");
    }

    private void reindexFromDisk() throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir, "*" + META_SUFFIX)) {
            for (Path meta : ds) {
                try {
                    Entry e = mapper.readValue(meta.toFile(), Entry.class);
                    if (e != null && e.key != null) {
                        index.put(e.key, e);
                    }
                } catch (Exception ex) {
                    log.debug("Ignoring malformed meta sidecar {}: {}", meta, ex.toString());
                }
            }
        }
    }

    /**
     * Look up the key and return cached bytes if present (and not expired),
     * otherwise invoke {@code supplier} to produce a fresh
     * {@link CachedQueryResult}, store it, and return it.
     */
    public CachedQueryResult get(String key, String cubeVersion, Supplier<CachedQueryResult> supplier) {
        if (!enabled) {
            return supplier.get();
        }
        Entry hit = lookup(key, cubeVersion);
        if (hit != null) {
            try {
                byte[] bytes = Files.readAllBytes(arrowPath(key));
                return new CachedQueryResult(bytes, hit.runtimeMs, hit.rows, true);
            } catch (IOException e) {
                log.debug("Cache arrow file missing for {} — falling through: {}", key, e.toString());
                invalidate(key);
            }
        }
        CachedQueryResult fresh = supplier.get();
        if (fresh != null && fresh.arrowBytes != null) {
            put(key, cubeVersion, fresh);
        }
        return fresh;
    }

    /** Returns the entry if present, not expired, and cubeVersion matches. */
    private Entry lookup(String key, String cubeVersion) {
        Entry e = index.getIfPresent(key);
        if (e == null) {
            return null;
        }
        long age = System.currentTimeMillis() - e.createdAt;
        if (age > ttlMillis) {
            invalidate(key);
            return null;
        }
        if (cubeVersion != null && e.cubeVersion != null && !cubeVersion.equals(e.cubeVersion)) {
            invalidate(key);
            return null;
        }
        return e;
    }

    public void put(String key, String cubeVersion, CachedQueryResult r) {
        if (!enabled || r == null || r.arrowBytes == null) {
            return;
        }
        writeLock.lock();
        try {
            Path arrow = arrowPath(key);
            Path meta = metaPath(key);
            // Write to tmp then atomic-move to avoid half-written files on crash.
            Path tmp = arrow.resolveSibling(arrow.getFileName().toString() + ".tmp");
            Files.write(tmp, r.arrowBytes);
            Files.move(tmp, arrow, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            Entry e = new Entry(
                    key,
                    System.currentTimeMillis(),
                    r.runtimeMs,
                    r.rows,
                    cubeVersion == null ? "" : cubeVersion,
                    r.arrowBytes.length);
            mapper.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(), e);
            index.put(key, e);

            evictIfOverBudget();
        } catch (IOException ex) {
            log.warn("SaikuQueryCache.put({}) failed: {}", key, ex.toString());
        } finally {
            writeLock.unlock();
        }
    }

    public void invalidate(String key) {
        writeLock.lock();
        try {
            index.invalidate(key);
            Files.deleteIfExists(arrowPath(key));
            Files.deleteIfExists(metaPath(key));
        } catch (IOException e) {
            log.debug("invalidate({}) IO hiccup: {}", key, e.toString());
        } finally {
            writeLock.unlock();
        }
    }

    public void invalidateAll() {
        writeLock.lock();
        try {
            index.invalidateAll();
            if (Files.isDirectory(cacheDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir)) {
                    for (Path p : ds) {
                        String n = p.getFileName().toString();
                        if (n.endsWith(ARROW_SUFFIX) || n.endsWith(META_SUFFIX)) {
                            Files.deleteIfExists(p);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("invalidateAll IO hiccup: {}", e.toString());
        } finally {
            writeLock.unlock();
        }
    }

    public long totalBytesOnDisk() {
        long total = 0L;
        for (Entry e : index.asMap().values()) {
            total += e.bytes;
        }
        return total;
    }

    private void evictIfOverBudget() {
        long total = totalBytesOnDisk();
        if (total <= maxSizeBytes) {
            return;
        }
        List<Entry> all = new ArrayList<>(index.asMap().values());
        all.sort(Comparator.comparingLong(e -> e.createdAt)); // oldest first
        for (Entry victim : all) {
            if (total <= maxSizeBytes) {
                break;
            }
            log.info(
                    "SaikuQueryCache evicting {} ({} bytes, age={}ms) to stay under {} bytes",
                    victim.key,
                    victim.bytes,
                    System.currentTimeMillis() - victim.createdAt,
                    maxSizeBytes);
            invalidate(victim.key);
            total -= victim.bytes;
        }
    }

    Path arrowPath(String key) {
        return cacheDir.resolve(key + ARROW_SUFFIX);
    }

    Path metaPath(String key) {
        return cacheDir.resolve(key + META_SUFFIX);
    }
}
