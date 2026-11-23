/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.util.exception.SaikuAccessDeniedException;

/**
 * saiku#1968 (CWE-863) F2: a per-datasource access denial must NOT abort whole-datasource
 * enumeration.
 *
 * <p>{@code getAllConnections()} iterates every datasource the caller's roles permit and acquires a
 * connection for each. With the fail-closed guard now throwing {@link SaikuAccessDeniedException}
 * when a caller holds no Mondrian role on a security-enabled datasource, an un-caught throw would
 * abort the whole loop — so the FIRST denied datasource would blank out enumeration of every OTHER
 * datasource the user CAN access (REST discover returns {@code []}, XMLA faults on every request).
 *
 * <p>These tests drive the real {@link AbstractConnectionManager#getAllConnections()} /
 * {@link AbstractConnectionManager#getAllOlapConnections()} with a concrete subclass whose
 * {@code getInternalConnection} denies one datasource and serves another, proving the denied one is
 * silently omitted while the accessible one survives. The single-datasource {@code getConnection}
 * path is asserted to still propagate the denial (fail-closed for a directly-requested cube).
 */
public class AbstractConnectionManagerEnumerationTest {

    private static final String DENIED = "secured-a";
    private static final String ALLOWED = "secured-b";

    /** Denied datasource is dropped; the accessible one is still enumerated. */
    @Test
    public void getAllConnections_omitsDeniedDatasource_keepsAccessible() throws Exception {
        TestConnectionManager mgr = new TestConnectionManager();

        Map<String, ISaikuConnection> all = mgr.getAllConnections();

        assertFalse("denied datasource must be omitted, not abort the loop", all.containsKey(DENIED));
        assertTrue("accessible datasource must still be enumerated", all.containsKey(ALLOWED));
        assertEquals(1, all.size());
    }

    /** getAllOlapConnections delegates to getAllConnections, so it inherits the omission. */
    @Test
    public void getAllOlapConnections_omitsDeniedDatasource() throws Exception {
        TestConnectionManager mgr = new TestConnectionManager();

        // The accessible connection here is not an OlapConnection, so the olap map is empty — the
        // point is only that the denied datasource did not abort enumeration (no exception thrown).
        assertEquals(0, mgr.getAllOlapConnections().size());
    }

    /** The single-datasource path still fails closed for a directly-requested denied cube. */
    @Test
    public void getConnection_singleDeniedDatasource_stillDenies() {
        TestConnectionManager mgr = new TestConnectionManager();

        assertThrows(SaikuAccessDeniedException.class, () -> mgr.getConnection(DENIED));
    }

    // ---- test manager ---------------------------------------------------------------------------

    /** Concrete AbstractConnectionManager: denies {@link #DENIED}, serves {@link #ALLOWED}. */
    private static final class TestConnectionManager extends AbstractConnectionManager {
        TestConnectionManager() {
            setDataSourceManager(fakeDatasourceManager());
        }

        @Override
        public void init() {}

        @Override
        protected ISaikuConnection getInternalConnection(String name, SaikuDatasource datasource) {
            if (DENIED.equals(name)) {
                throw new SaikuAccessDeniedException("Access denied: no role on \"" + name + "\".");
            }
            return fakeConnection(name);
        }

        @Override
        protected ISaikuConnection refreshInternalConnection(String name, SaikuDatasource datasource) {
            return getInternalConnection(name, datasource);
        }
    }

    private static IDatasourceManager fakeDatasourceManager() {
        return (IDatasourceManager) Proxy.newProxyInstance(
                AbstractConnectionManagerEnumerationTest.class.getClassLoader(),
                new Class<?>[] {IDatasourceManager.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getDatasources": {
                            // Denied FIRST: the pre-fix abort would then drop ALLOWED too.
                            Map<String, SaikuDatasource> m = new LinkedHashMap<>();
                            m.put(DENIED, datasource(DENIED));
                            m.put(ALLOWED, datasource(ALLOWED));
                            return m;
                        }
                        case "getDatasource":
                            return datasource((String) args[0]);
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static SaikuDatasource datasource(String name) {
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, new Properties());
    }

    private static ISaikuConnection fakeConnection(String name) {
        return (ISaikuConnection) Proxy.newProxyInstance(
                AbstractConnectionManagerEnumerationTest.class.getClassLoader(),
                new Class<?>[] {ISaikuConnection.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("initialized".equals(method.getName())) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
