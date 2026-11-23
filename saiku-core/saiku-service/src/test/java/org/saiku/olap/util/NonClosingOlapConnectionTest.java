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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.olap4j.OlapConnection;

/**
 * saiku#1969 reversion guard for {@link NonClosingOlapConnection} — the borrowed view of a shared
 * cached OLAP connection that keeps the XMLA fork's {@code close()} from taking the connection down
 * for every other caller.
 *
 * <p>The XMLA server fork closes the connection it is handed at the end of EVERY query
 * ({@code XmlaHandler.executeQuery} / {@code executeDrillThroughQuery}, success and failure alike).
 * Because {@code IConnectionManager} caches and shares those connections, an unwrapped hand-off made
 * one bad MDX over {@code /xmla} break every subsequent query on that datasource. Reverting the
 * wrapper to a bare delegate turns {@link #closeIsANoOp_delegateSurvives()} red.
 *
 * <p>{@link #everyOtherMethodDelegates()} sweeps the whole class reflectively so the wrapper cannot
 * silently drop a method — a delegation typo in {@code getOlapDatabase()} or {@code createStatement()}
 * would otherwise only show up as a runtime XMLA failure.
 */
public class NonClosingOlapConnectionTest {

    /** A recording {@link OlapConnection} stand-in; only the calls the tests care about matter. */
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
            switch (name) {
                case "close":
                    closed = true;
                    return null;
                case "isClosed":
                    return closed;
                case "isValid":
                    return !closed;
                case "toString":
                    return "RecordingConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "unwrap":
                    return "unwrapped:" + ((Class<?>) args[0]).getSimpleName();
                case "isWrapperFor":
                    return Boolean.TRUE;
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    /**
     * THE fix: {@code close()} on the borrowed view must not reach the shared connection. This is
     * exactly what the XMLA handler does after a query, on both the failure and success paths.
     */
    @Test
    public void closeIsANoOp_delegateSurvives() throws Exception {
        RecordingConnection delegate = new RecordingConnection();
        NonClosingOlapConnection borrowed = new NonClosingOlapConnection(delegate.asConnection());

        borrowed.close();

        assertFalse("close() must not reach the shared connection (saiku#1969)", delegate.closed);
        assertFalse("the wrapper must not report itself closed", borrowed.isClosed());
        assertFalse("delegate.close() must never have been invoked", delegate.calls.contains("close"));
    }

    /** Repeated closes (retry, drill-through, then a plain query) are equally harmless. */
    @Test
    public void repeatedClosesStillHarmless() throws Exception {
        RecordingConnection delegate = new RecordingConnection();
        NonClosingOlapConnection borrowed = new NonClosingOlapConnection(delegate.asConnection());

        borrowed.close();
        borrowed.close();
        borrowed.close();

        assertFalse(delegate.closed);
    }

    /**
     * A connection genuinely closed by something else must STILL look closed through the wrapper —
     * otherwise the view would mask the outage the manager's saiku#1969 rebuild is there to detect.
     */
    @Test
    public void isClosedStillReportsTheSharedConnectionsState() throws Exception {
        RecordingConnection delegate = new RecordingConnection();
        NonClosingOlapConnection borrowed = new NonClosingOlapConnection(delegate.asConnection());

        assertFalse(borrowed.isClosed());

        delegate.asConnection().close(); // closed behind the wrapper's back
        assertTrue(borrowed.isClosed());
    }

    /** {@code abort()} destroys the connection for every other caller too, so it is a no-op here. */
    @Test
    public void abortIsANoOp() throws Exception {
        RecordingConnection delegate = new RecordingConnection();
        NonClosingOlapConnection borrowed = new NonClosingOlapConnection(delegate.asConnection());

        borrowed.abort(Runnable::run);

        assertFalse("abort() must not reach the shared connection", delegate.closed);
        assertFalse(delegate.calls.contains("abort"));
    }

    /** {@code unwrap} must still reach the real connection — the fork has OlapWrapper.unwrap paths. */
    @Test
    public void unwrapDelegates() throws Exception {
        RecordingConnection delegate = new RecordingConnection();
        NonClosingOlapConnection borrowed = new NonClosingOlapConnection(delegate.asConnection());

        assertEquals("unwrapped:String", borrowed.unwrap(String.class));
        assertTrue(borrowed.isWrapperFor(String.class));
    }

    /** The wrapper is not the cached object itself — the handler must never hold it. */
    @Test
    public void wrapperIsNotTheDelegate() {
        RecordingConnection delegate = new RecordingConnection();
        OlapConnection cached = delegate.asConnection();
        NonClosingOlapConnection borrowed = new NonClosingOlapConnection(cached);

        assertSame(cached, borrowed.getDelegate());
        assertTrue(
                "identity must be broken so the handler cannot close the cached connection",
                cached != (Object) borrowed);
    }

    /** A null delegate is a programming error, not a silently broken wrapper. */
    @Test
    public void nullDelegateRejected() {
        try {
            new NonClosingOlapConnection(null);
            fail("expected IllegalArgumentException for a null delegate");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /**
     * Reflective sweep: every public method of the wrapper other than the three intentional
     * lifecycle/identity overrides must forward to the delegate. Guards the whole delegation table
     * against typos and against a method being forgotten when the olap4j interface moves.
     */
    @Test
    public void everyOtherMethodDelegates() throws Exception {
        RecordingConnection delegate = new RecordingConnection();
        NonClosingOlapConnection borrowed = new NonClosingOlapConnection(delegate.asConnection());

        List<String> notDelegating = new ArrayList<>();
        int checked = 0;
        for (Method m : NonClosingOlapConnection.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            String name = m.getName();
            if ("close".equals(name)
                    || "abort".equals(name)
                    || "isClosed".equals(name)
                    || "isValid".equals(name)
                    || "unwrap".equals(name)
                    || "isWrapperFor".equals(name)
                    || "getDelegate".equals(name)
                    || "toString".equals(name)) {
                continue; // covered by the dedicated tests above
            }
            checked++;
            int before = delegate.calls.size();
            try {
                m.invoke(borrowed, dummyArgs(m));
            } catch (InvocationTargetException e) {
                fail(name + " threw " + e.getCause());
            } catch (IllegalAccessException e) {
                fail(name + " is not accessible: " + e);
            }
            if (delegate.calls.size() != before + 1 || !name.equals(delegate.calls.get(before))) {
                notDelegating.add(name);
            }
        }

        assertTrue("the sweep must actually exercise the wrapper", checked > 40);
        assertEquals(
                "these methods must delegate to the shared connection: " + notDelegating,
                Arrays.asList(),
                notDelegating);
    }

    /** Nulls for object params, zero/false for primitives — the proxy accepts all of them. */
    private static Object[] dummyArgs(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = defaultValue(types[i]);
        }
        return args;
    }

    /** Compile-time reference so the test fails loudly if the signature ever changes. */
    @Test
    public void wrapperSatisfiesTheOlapConnectionContract() throws SQLException {
        OlapConnection borrowed = new NonClosingOlapConnection(new RecordingConnection().asConnection());
        borrowed.close();
        assertEquals(true, borrowed.isValid(1));
    }
}
