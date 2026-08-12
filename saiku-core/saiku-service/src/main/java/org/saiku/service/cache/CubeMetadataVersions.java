/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License. You may
 *   obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */
package org.saiku.service.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cube-metadata version registry (saiku#1483) — the "real cube metadata version service"
 * the Phase 5 placeholder in {@link QueryCacheKey} pointed at.
 *
 * <p>Tracks a monotonically increasing epoch per connection name. Every schema-reload
 * path in Saiku funnels through {@code AbstractConnectionManager.refreshConnection(name)}
 * (admin datasource refresh, datasource save/update via {@code RepositoryDatasourceManager},
 * the XMLA servlet's refresh, and {@code refreshAllConnections}), and that chokepoint bumps
 * the connection's epoch here. {@link QueryCacheKey#cubeVersion} folds the epoch into the
 * cellset cache key, so a schema reload naturally busts every cached entry for that
 * connection — the stale-cellset-after-model-edit bug.
 *
 * <p>Epochs are in-JVM (they reset to 0 on restart). That matches the exposure: within a
 * running JVM, Mondrian serves the reloaded schema while the cache would have kept serving
 * pre-reload cellsets. Across a restart Mondrian re-reads the schema anyway; disk-cache
 * entries persisted with epoch 0 remain valid unless the schema was edited offline — the
 * same (much smaller) exposure the placeholder had, now confined to offline edits.
 *
 * <p>Thread-safe. No Spring wiring needed: both the bump site and the key builder live in
 * saiku-service, and the cellset cache this feeds is per-JVM too.
 */
public final class CubeMetadataVersions {

    private static final ConcurrentMap<String, AtomicLong> EPOCHS = new ConcurrentHashMap<>();

    private CubeMetadataVersions() {}

    /** Current epoch for a connection; 0 until the first {@link #bump}. Null-safe. */
    public static long epoch(String connectionName) {
        if (connectionName == null) {
            return 0L;
        }
        AtomicLong e = EPOCHS.get(connectionName);
        return e == null ? 0L : e.get();
    }

    /** Advance the epoch for a connection — call whenever its schema/metadata is reloaded. */
    public static void bump(String connectionName) {
        if (connectionName == null) {
            return;
        }
        EPOCHS.computeIfAbsent(connectionName, k -> new AtomicLong()).incrementAndGet();
    }

    /** Test seam: clear all epochs so tests are order-independent. */
    static void resetForTests() {
        EPOCHS.clear();
    }
}
