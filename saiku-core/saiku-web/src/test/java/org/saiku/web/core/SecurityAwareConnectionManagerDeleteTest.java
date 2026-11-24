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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Map;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;

/** Regression coverage for connection eviction when a datasource descriptor is deleted. */
public class SecurityAwareConnectionManagerDeleteTest {

    private static final String NAME = "del-probe";

    private static final class TestManager extends SecurityAwareConnectionManager {
        ISaikuConnection fetchWithoutDescriptor() {
            return getInternalConnection(NAME, null);
        }
    }

    private static final class CachedConnection {
        private boolean closed;
        private final ISaikuConnection saikuConnection;

        CachedConnection() {
            Connection jdbcConnection = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            closed = true;
                            return null;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return closed;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            saikuConnection = (ISaikuConnection) Proxy.newProxyInstance(
                    ISaikuConnection.class.getClassLoader(),
                    new Class<?>[] {ISaikuConnection.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            return jdbcConnection;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ISaikuConnection> cache(SecurityAwareConnectionManager manager) throws Exception {
        Field field = SecurityAwareConnectionManager.class.getDeclaredField("connections");
        field.setAccessible(true);
        return (Map<String, ISaikuConnection>) field.get(manager);
    }

    @Test
    public void deletionEvictsAndClosesBareAndPerUserConnectionsUnderBothNameSpellings() throws Exception {
        TestManager manager = new TestManager();
        Map<String, ISaikuConnection> cache = cache(manager);
        CachedConnection bare = new CachedConnection();
        CachedConnection perUser = new CachedConnection();
        CachedConnection decorated = new CachedConnection();
        CachedConnection decoratedPerUser = new CachedConnection();
        CachedConnection other = new CachedConnection();
        cache.put(NAME, bare.saikuConnection);
        cache.put(SecurityAwareConnectionManager.connectionCacheKey(NAME, "alice"), perUser.saikuConnection);
        cache.put("unknown_" + NAME, decorated.saikuConnection);
        cache.put(
                SecurityAwareConnectionManager.connectionCacheKey("unknown_" + NAME, "bob"),
                decoratedPerUser.saikuConnection);
        cache.put(NAME + "-other", other.saikuConnection);

        manager.removeConnection(NAME);
        manager.removeConnection("unknown_" + NAME);

        assertFalse(cache.containsKey(NAME));
        assertFalse(cache.containsKey(SecurityAwareConnectionManager.connectionCacheKey(NAME, "alice")));
        assertFalse(cache.containsKey("unknown_" + NAME));
        assertFalse(cache.containsKey(SecurityAwareConnectionManager.connectionCacheKey("unknown_" + NAME, "bob")));
        assertTrue("an unrelated datasource must stay cached", cache.containsKey(NAME + "-other"));
        assertTrue(bare.closed);
        assertTrue(perUser.closed);
        assertTrue(decorated.closed);
        assertTrue(decoratedPerUser.closed);
        assertFalse(other.closed);
    }

    @Test
    public void missingDescriptorNeverReturnsAStaleCachedConnection() throws Exception {
        TestManager manager = new TestManager();
        CachedConnection stale = new CachedConnection();
        cache(manager).put(NAME, stale.saikuConnection);

        assertNull(manager.fetchWithoutDescriptor());
    }
}
