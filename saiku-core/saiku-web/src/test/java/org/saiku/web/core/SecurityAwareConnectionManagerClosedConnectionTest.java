/*
 * Copyright 2026 Spicule Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.web.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Properties;
import org.junit.Test;
import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * saiku#1969 reversion guard for {@link SecurityAwareConnectionManager}'s cache health check — the
 * half of the fix that makes a closed cached connection self-healing.
 *
 * <p>The cache OWNS its connections: it caches one per key and hands the same instance to every
 * caller resolving to that key, and until this fix it never checked whether the instance it was
 * about to hand out was still open. So anything that closed one behind the cache's back — the XMLA
 * fork's unconditional {@code close()} on a shared connection being the historical case — left a
 * closed instance in the map that every later caller received, breaking subsequent queries on that
 * datasource until an admin refreshed it.
 *
 * <p>{@code connect()} is the seam: these tests inject fake connections, so they assert the cache's
 * behaviour (evict-and-rebuild) without a warehouse. Reverting the {@code isClosedOlapConnection}
 * check turns {@link #closedCachedConnectionIsEvictedAndRebuilt()} red — the closed instance is
 * handed back again.
 */
public class SecurityAwareConnectionManagerClosedConnectionTest {

    private static final String DS = "foodmart";

    /** Fake OLAP connection whose closed-state we control. */
    private static final class FakeOlapConnection implements InvocationHandler {
        private boolean closed;

        OlapConnection asConnection() {
            return (OlapConnection) Proxy.newProxyInstance(
                    OlapConnection.class.getClassLoader(), new Class<?>[] {OlapConnection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "isClosed":
                    return closed;
                case "isValid":
                    return !closed;
                case "close":
                    closed = true;
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "FakeOlapConnection";
                default:
                    Class<?> rt = method.getReturnType();
                    if (!rt.isPrimitive() || rt == void.class) {
                        return null;
                    }
                    return rt == boolean.class ? Boolean.FALSE : 0;
            }
        }
    }

    private static final class FakeSaikuConnection implements ISaikuConnection {
        private final Connection connection;
        private final String name;

        FakeSaikuConnection(String name, Connection connection) {
            this.name = name;
            this.connection = connection;
        }

        @Override
        public void setProperties(Properties props) {}

        @Override
        public boolean connect(Properties props) {
            return true;
        }

        @Override
        public boolean connect() {
            return true;
        }

        @Override
        public boolean clearCache() {
            return true;
        }

        @Override
        public boolean initialized() {
            return true;
        }

        @Override
        public String getDatasourceType() {
            return ISaikuConnection.OLAP_DATASOURCE;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Properties getProperties() {
            return new Properties();
        }
    }

    /** Manager whose {@code connect()} returns whatever the test queues up. */
    private static final class TestConnectionManager extends SecurityAwareConnectionManager {
        private final java.util.List<ISaikuConnection> built = new java.util.ArrayList<>();
        private final java.util.function.Function<String, ISaikuConnection> factory;

        TestConnectionManager(java.util.function.Function<String, ISaikuConnection> factory) {
            this.factory = factory;
        }

        @Override
        protected ISaikuConnection connect(String name, SaikuDatasource datasource) {
            ISaikuConnection con = factory.apply(name);
            if (con != null) {
                built.add(con);
            }
            return con;
        }

        /**
         * {@code getInternalConnection} is protected in a different package; this subclass is the
         * legal call site for it.
         */
        ISaikuConnection fetch(SaikuDatasource datasource) {
            return getInternalConnection(DS, datasource);
        }
    }

    private static SaikuDatasource datasource() {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "false");
        return new SaikuDatasource(DS, SaikuDatasource.Type.OLAP, props);
    }

    /**
     * THE fix: a cached connection that has been closed underneath the cache is evicted and a fresh
     * one built, so the caller gets a usable connection instead of a dead one.
     */
    @Test
    public void closedCachedConnectionIsEvictedAndRebuilt() throws Exception {
        FakeOlapConnection first = new FakeOlapConnection();
        FakeOlapConnection second = new FakeOlapConnection();
        OlapConnection firstConnection = first.asConnection();
        FakeOlapConnection[] sequence = {first, second};
        int[] built = {0};

        TestConnectionManager mgr =
                new TestConnectionManager(name -> new FakeSaikuConnection(name, sequence[built[0]++].asConnection()));

        ISaikuConnection initial = mgr.fetch(datasource());
        assertNotNull(initial);
        assertSame("a live connection is cached and reused", initial, mgr.fetch(datasource()));

        // Something closed the cached connection behind the cache's back — the saiku#1969 scenario.
        firstConnection.close();

        ISaikuConnection afterClose = mgr.fetch(datasource());

        assertNotSame("a closed cached connection must not be handed out again", initial, afterClose);
        assertNotSame("and a fresh OLAP connection must back it", firstConnection, afterClose.getConnection());
        assertFalse("the rebuilt connection must be open", ((OlapConnection) afterClose.getConnection()).isClosed());
        assertSame("the rebuild must be cached for the next caller", afterClose, mgr.fetch(datasource()));
        assertEquals("exactly one rebuild, not one per call", 2, built[0]);
    }

    /** Sanity: the sequence above really did build a second connection. */
    @Test
    public void liveCachedConnectionIsStillReused() {
        FakeOlapConnection only = new FakeOlapConnection();
        int[] built = {0};
        TestConnectionManager mgr = new TestConnectionManager(name -> {
            built[0]++;
            return new FakeSaikuConnection(name, only.asConnection());
        });

        ISaikuConnection a = mgr.fetch(datasource());
        ISaikuConnection b = mgr.fetch(datasource());

        assertSame(a, b);
        assertEquals("an open connection must not trigger a rebuild", 1, built[0]);
    }

    /** The health check itself: closed → true, open → false, non-OLAP → false (not our business). */
    @Test
    public void healthCheckReportsOnlyClosedOlapConnections() throws Exception {
        SecurityAwareConnectionManager mgr = new SecurityAwareConnectionManager();
        FakeOlapConnection olap = new FakeOlapConnection();

        FakeSaikuConnection open = new FakeSaikuConnection(DS, olap.asConnection());
        assertFalse(mgr.isClosedOlapConnection(open));
        assertFalse("null is not a dead connection", mgr.isClosedOlapConnection(null));

        olap.asConnection().close();
        assertTrue(mgr.isClosedOlapConnection(open));

        // A non-OLAP connection's lifecycle is out of scope for this check.
        FakeSaikuConnection jdbc = new FakeSaikuConnection(DS, new FakeJdbcConnection().asConnection());
        assertFalse(mgr.isClosedOlapConnection(jdbc));
    }

    /** Bare {@link Connection} stand-in for a non-OLAP datasource. */
    private static final class FakeJdbcConnection implements InvocationHandler {
        Connection asConnection() {
            return (Connection)
                    Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("isClosed".equals(method.getName())) {
                return Boolean.TRUE;
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            if ("toString".equals(method.getName())) {
                return "FakeJdbcConnection";
            }
            Class<?> rt = method.getReturnType();
            if (!rt.isPrimitive() || rt == void.class) {
                return null;
            }
            return rt == boolean.class ? Boolean.FALSE : 0;
        }
    }
}
