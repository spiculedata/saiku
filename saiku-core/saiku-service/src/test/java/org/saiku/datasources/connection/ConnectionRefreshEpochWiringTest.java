/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.cache.CubeMetadataVersions;
import org.saiku.service.datasource.IDatasourceManager;

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
}
