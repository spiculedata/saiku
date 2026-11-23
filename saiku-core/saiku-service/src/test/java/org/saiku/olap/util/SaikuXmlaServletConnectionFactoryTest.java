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

package org.saiku.olap.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mondrian.xmla.XmlaHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.IDatasourceManager;

/**
 * saiku#1969 reversion guard for {@link SaikuXmlaServlet}'s {@link XmlaHandler.ConnectionFactory} —
 * the hand-off point between the XMLA endpoint and the shared OLAP connection cache.
 *
 * <p>The factory used to return the cached connection ITSELF. The XMLA fork closes the connection it
 * is given at the end of every query ({@code XmlaHandler.executeQuery} /
 * {@code executeDrillThroughQuery}, on the failure path and the success path alike), so a bad MDX
 * over {@code /xmla} closed a connection that {@code IConnectionManager} still cached and shared —
 * and the manager never re-opened or health-checked it, so every later query on that datasource
 * failed. Each test here drives the REAL factory against a fake manager holding a recording
 * connection and asserts the XMLA handler gets a borrowed view: closing it leaves the cached
 * connection open.
 *
 * <p>Reverting {@code borrow()} to return the cached connection turns {@link
 * #xmlaCloseDoesNotCloseTheCachedConnection()} red.
 */
public class SaikuXmlaServletConnectionFactoryTest {

    /** Records lifecycle calls and answers everything else with a type-appropriate default. */
    private static final class RecordingConnection implements InvocationHandler {
        private final List<String> calls = new ArrayList<>();
        private boolean closed;

        OlapConnection asConnection() {
            return (OlapConnection) Proxy.newProxyInstance(
                    OlapConnection.class.getClassLoader(), new Class<?>[] {OlapConnection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            calls.add(name);
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("isValid".equals(name)) {
                return !closed;
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            if ("toString".equals(name)) {
                return "RecordingConnection";
            }
            Class<?> rt = method.getReturnType();
            if (!rt.isPrimitive()) {
                return null;
            }
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == void.class) {
                return null;
            }
            return 0;
        }
    }

    /** Minimal {@link IConnectionManager} that hands out the connections it is given. */
    private static final class FakeConnectionManager implements IConnectionManager {
        private final Map<String, OlapConnection> cached = new LinkedHashMap<>();

        @Override
        public void init() {}

        @Override
        public void setDataSourceManager(IDatasourceManager ds) {}

        @Override
        public IDatasourceManager getDataSourceManager() {
            return null;
        }

        @Override
        public void refreshConnection(String name) {}

        @Override
        public void refreshAllConnections() {}

        @Override
        public OlapConnection getOlapConnection(String name) throws SaikuOlapException {
            return cached.get(name);
        }

        @Override
        public Map<String, OlapConnection> getAllOlapConnections() throws SaikuOlapException {
            return cached;
        }

        @Override
        public ISaikuConnection getConnection(String name) throws SaikuOlapException {
            return null;
        }

        @Override
        public Map<String, ISaikuConnection> getAllConnections() throws SaikuOlapException {
            return Collections.emptyMap();
        }
    }

    private FakeConnectionManager manager;

    @Before
    public void installManager() throws Exception {
        manager = new FakeConnectionManager();
        Field field = SaikuXmlaServlet.class.getDeclaredField("connections");
        field.setAccessible(true);
        field.set(null, manager);
    }

    @After
    public void clearManager() throws Exception {
        Field field = SaikuXmlaServlet.class.getDeclaredField("connections");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static XmlaHandler.ConnectionFactory factory() throws Exception {
        return new SaikuXmlaServlet().createConnectionFactory(null);
    }

    /**
     * THE fix: whatever the XMLA handler does with the connection it is handed — including the
     * {@code close()} it performs on every failure path — the connection the cache still holds must
     * survive, so the next query can use it.
     */
    @Test
    public void xmlaCloseDoesNotCloseTheCachedConnection() throws Exception {
        RecordingConnection cached = new RecordingConnection();
        manager.cached.put("foodmart", cached.asConnection());

        OlapConnection handedOut = factory().getConnection("foodmart", null, null, null);
        assertNotNull("factory must hand out a connection", handedOut);
        handedOut.close(); // what XmlaHandler does on the failure path

        assertFalse("the cached connection must not be closed by an XMLA caller", cached.closed);
        assertFalse(cached.calls.contains("close"));
        assertFalse("and the view must not report itself closed", handedOut.isClosed());

        // The next caller — e.g. a REST query on the same cache slot — still gets a usable connection.
        OlapConnection next = factory().getConnection("foodmart", null, null, null);
        assertNotNull(next);
        assertFalse(next.isClosed());
    }

    /** The success path closes too, so this is not only about failures. */
    @Test
    public void successfulQueryPathCloseIsAlsoHarmless() throws Exception {
        RecordingConnection cached = new RecordingConnection();
        manager.cached.put("foodmart", cached.asConnection());

        OlapConnection handedOut = factory().getConnection("foodmart", null, null, null);
        handedOut.close();
        handedOut.close();

        assertFalse(cached.closed);
    }

    /** The handler must never be handed the cached instance itself, only a borrowed view. */
    @Test
    public void handlerNeverHoldsTheCachedInstance() throws Exception {
        OlapConnection cached = new RecordingConnection().asConnection();
        manager.cached.put("foodmart", cached);

        OlapConnection handedOut = factory().getConnection("foodmart", null, null, null);

        assertTrue(
                "identity must be broken so the handler cannot close the cached connection",
                cached != (Object) handedOut);
        assertTrue(handedOut instanceof NonClosingOlapConnection);
    }

    /** Catalog matching stays case-insensitive, and the matched connection is still wrapped. */
    @Test
    public void catalogMatchIsCaseInsensitiveAndWrapped() throws Exception {
        RecordingConnection cached = new RecordingConnection();
        manager.cached.put("FoodMart", cached.asConnection());

        OlapConnection handedOut = factory().getConnection("FOODMART", null, null, null);

        assertNotNull(handedOut);
        assertTrue(handedOut instanceof NonClosingOlapConnection);
        handedOut.close();
        assertFalse(cached.closed);
    }

    /** No catalog supplied → first cached connection, wrapped the same way. */
    @Test
    public void nullCatalogReturnsFirstConnectionWrapped() throws Exception {
        RecordingConnection cached = new RecordingConnection();
        manager.cached.put("foodmart", cached.asConnection());

        OlapConnection handedOut = factory().getConnection(null, null, null, null);

        assertNotNull(handedOut);
        assertTrue(handedOut instanceof NonClosingOlapConnection);
        handedOut.close();
        assertFalse(cached.closed);
    }

    /** An unknown catalog still yields null (the fork turns that into an XMLA error), not a NPE. */
    @Test
    public void unknownCatalogStillReturnsNull() throws Exception {
        manager.cached.put("foodmart", new RecordingConnection().asConnection());

        assertNull(factory().getConnection("nope", null, null, null));
    }

    /** No connections at all → null, not a wrapper around nothing. */
    @Test
    public void emptyCacheReturnsNull() throws Exception {
        assertNull(factory().getConnection("foodmart", null, null, null));
    }
}
