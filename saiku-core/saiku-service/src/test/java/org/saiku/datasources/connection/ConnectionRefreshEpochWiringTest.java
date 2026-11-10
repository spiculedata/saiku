/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.cache.CubeMetadataVersions;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.springframework.beans.factory.ObjectFactory;

/**
 * saiku#1483 — call-site/wiring test: {@link AbstractConnectionManager#refreshConnection}
 * must bump the connection's cube-metadata epoch. The unit tests on
 * {@code QueryCacheKeyTest} prove the epoch participates in the cache key; this proves the
 * production reload chokepoint actually advances it — the bug lives at the call-site, not
 * in the helper.
 *
 * <p>Assertions are delta-based (before/after) so the test is order-independent without
 * needing to reset the global epoch registry.
 */
public class ConnectionRefreshEpochWiringTest {

    /** Minimal concrete manager: connection plumbing stubbed out, refresh flow intact. */
    private static final class StubConnectionManager extends AbstractConnectionManager {
        @Override
        public void init() {}

        @Override
        protected ISaikuConnection getInternalConnection(String name, SaikuDatasource datasource)
                throws SaikuOlapException {
            return null;
        }

        @Override
        protected ISaikuConnection refreshInternalConnection(String name, SaikuDatasource datasource) {
            return null;
        }
    }

    /** refreshConnection only calls {@code ds.getDatasource(name)} — a dynamic proxy keeps the
     *  stub at three lines instead of the 40-method interface. */
    private static IDatasourceManager datasourceManagerServing(String name) {
        return (IDatasourceManager) Proxy.newProxyInstance(
                IDatasourceManager.class.getClassLoader(), new Class<?>[] {IDatasourceManager.class}, (p, m, a) -> {
                    if ("getDatasource".equals(m.getName())) {
                        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, new Properties());
                    }
                    return null;
                });
    }

    @Test
    public void refreshConnectionBumpsThatConnectionsEpoch() {
        StubConnectionManager mgr = new StubConnectionManager();
        mgr.setDataSourceManager(datasourceManagerServing("wiring-conn"));

        long before = CubeMetadataVersions.epoch("wiring-conn");
        long otherBefore = CubeMetadataVersions.epoch("wiring-other");

        mgr.refreshConnection("wiring-conn");

        assertEquals(
                "reload must advance the connection's epoch", before + 1, CubeMetadataVersions.epoch("wiring-conn"));
        assertEquals(
                "an unrelated connection's epoch must not move",
                otherBefore,
                CubeMetadataVersions.epoch("wiring-other"));
    }

    @Test
    public void repeatedRefreshKeepsAdvancing() {
        StubConnectionManager mgr = new StubConnectionManager();
        mgr.setDataSourceManager(datasourceManagerServing("wiring-repeat"));

        long before = CubeMetadataVersions.epoch("wiring-repeat");
        mgr.refreshConnection("wiring-repeat");
        mgr.refreshConnection("wiring-repeat");
        assertEquals(before + 2, CubeMetadataVersions.epoch("wiring-repeat"));
    }

    /** saiku#2326 — a refresh must drop the connection's role-scoped AI schema
     *  snapshots, resolved through the LAZY source (saiku#2326 wiring follow-up:
     *  a direct bean reference created a connectionManager → aiCubeMetadata →
     *  olapDiscover → datasourceService → connectionManager cycle whose early
     *  references handed olapDiscoverService a half-built datasourceService, so
     *  OlapMetaExplorer was constructed with a final null connection manager and
     *  every getAllCubes() NPEd — saiku#2326's own IT gate caught it). The lazy
     *  ObjectFactory defers the lookup to refresh time, and this test pins the
     *  funnel: the invalidation still reaches the cache with the refreshed name. */
    @Test
    public void refreshConnectionInvalidatesTheAiSchemaCacheThroughTheLazySource() {
        StubConnectionManager mgr = new StubConnectionManager();
        mgr.setDataSourceManager(datasourceManagerServing("lazy-ai-conn"));
        RecordingAiCache cache = new RecordingAiCache();
        mgr.setAiSchemaCacheSource((ObjectFactory<AiCubeMetadataService>) () -> {
            cache.resolved++;
            return cache;
        });

        mgr.refreshConnection("lazy-ai-conn");

        assertEquals(1, cache.resolved);
        assertEquals(java.util.Collections.singletonList("lazy-ai-conn"), cache.invalidated);
    }

    /** No source wired (unit tests, deployments without the AI surface) — refresh
     *  must be unaffected. */
    @Test
    public void aMissingAiSchemaCacheSourceIsAHardNoOp() {
        StubConnectionManager mgr = new StubConnectionManager();
        mgr.setDataSourceManager(datasourceManagerServing("lazy-ai-none"));

        long before = CubeMetadataVersions.epoch("lazy-ai-none");
        mgr.refreshConnection("lazy-ai-none");

        assertEquals(before + 1, CubeMetadataVersions.epoch("lazy-ai-none"));
    }

    /** A source that explodes must not break the refresh — fail-soft, matching
     *  the hook's own catch. */
    @Test
    public void aFailingAiSchemaCacheSourceMustNotBreakTheRefresh() {
        StubConnectionManager mgr = new StubConnectionManager();
        mgr.setDataSourceManager(datasourceManagerServing("lazy-ai-boom"));
        mgr.setAiSchemaCacheSource((ObjectFactory<AiCubeMetadataService>) () -> {
            throw new IllegalStateException("cache down");
        });

        long before = CubeMetadataVersions.epoch("lazy-ai-boom");
        mgr.refreshConnection("lazy-ai-boom");

        assertEquals("the refresh must still bump the epoch", before + 1, CubeMetadataVersions.epoch("lazy-ai-boom"));
    }

    /** Counts how often the lazy source is resolved and what it was asked to drop. */
    private static final class RecordingAiCache implements AiCubeMetadataService {
        int resolved;
        final List<String> invalidated = new ArrayList<>();

        @Override
        public org.saiku.service.olap.ai.AiSchema getSchema(org.saiku.service.olap.ai.AiCubeRef ref) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void invalidateSchemaCache(String connectionName) {
            invalidated.add(connectionName);
        }
    }
}
