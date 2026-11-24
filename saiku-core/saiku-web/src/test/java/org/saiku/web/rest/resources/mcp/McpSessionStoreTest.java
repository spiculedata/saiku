/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** saiku#2325: the session map must be bounded and idle entries must not live forever. */
public class McpSessionStoreTest {

    @Test
    public void createEvictsIdleSessionsWhenFull() throws Exception {
        McpSessionStore store = new McpSessionStore(10L, 3);
        store.create("v", "admin");
        store.create("v", "admin");
        store.create("v", "admin");
        Thread.sleep(30L);

        store.create("v", "admin");

        assertEquals(1, store.size());
    }

    @Test
    public void sizeNeverExceedsMax() {
        McpSessionStore store = new McpSessionStore(60_000L, 5);
        String last = null;
        for (int i = 0; i < 50; i++) {
            last = store.create("v", "admin");
        }

        assertTrue("size=" + store.size(), store.size() <= 5);
        assertNotNull("most recent session must survive", store.touch(last, "admin"));
    }

    @Test
    public void leastRecentlyActiveSessionIsEvictedWhenNothingIsIdle() throws Exception {
        McpSessionStore store = new McpSessionStore(60_000L, 2);
        String oldest = store.create("v", "admin");
        Thread.sleep(5L);
        String middle = store.create("v", "admin");
        Thread.sleep(5L);
        store.touch(oldest, "admin"); // oldest is now the most recently active

        store.create("v", "admin");

        assertNotNull(store.touch(oldest, "admin"));
        assertNull(store.touch(middle, "admin"));
    }

    @Test
    public void untouchedIdleSessionsAreSweptOnTouchOfAnotherId() throws Exception {
        McpSessionStore store = new McpSessionStore(20L, 100);
        store.create("v", "admin"); // A, never touched again
        Thread.sleep(40L);
        String b = store.create("v", "admin");
        Thread.sleep(5L);

        store.touch(b, "admin");

        assertEquals("idle session A must be swept", 1, store.size());
    }

    /* --------------------- saiku#2542: principal binding --------------------- */

    @Test
    public void sessionIsBoundToItsCreatingPrincipal() {
        McpSessionStore store = new McpSessionStore(60_000L, 10);
        String id = store.create("v", "alice");

        assertNotNull(store.touch(id, "alice"));
        // A different principal cannot use it — reads as unknown.
        assertNull(store.touch(id, "bob"));
        assertNull(store.touch(id, null));
        // ...and the session still works for its owner afterwards.
        assertNotNull(store.touch(id, "alice"));
    }

    @Test
    public void foreignTouchDoesNotRefreshActivity() throws Exception {
        McpSessionStore store = new McpSessionStore(30L, 10);
        String id = store.create("v", "alice");
        Thread.sleep(20L);
        assertNull("foreign probe is rejected", store.touch(id, "bob"));
        // 40ms have now elapsed since the owner's ONLY activity — past the 30ms idle timeout. If
        // the foreign probe had refreshed last-activity, the owner's session would still be alive.
        Thread.sleep(20L);
        assertNull("a foreign touch must not refresh last-activity", store.touch(id, "alice"));
    }

    @Test
    public void onlyTheOwnerCanDropASession() {
        McpSessionStore store = new McpSessionStore(60_000L, 10);
        String id = store.create("v", "alice");

        assertFalse("a foreign DELETE must not drop the session", store.drop(id, "bob"));
        assertNotNull("session survives the foreign delete", store.touch(id, "alice"));
        assertFalse("an unknown id reports false", store.drop("nope", "alice"));
        assertTrue("the owner can drop their session", store.drop(id, "alice"));
        assertNull(store.touch(id, "alice"));
    }

    @Test
    public void sessionKeepsItsNegotiatedProtocolAndPrincipal() {
        McpSessionStore store = new McpSessionStore(60_000L, 10);
        String id = store.create("2025-03-26", "alice");
        McpSessionStore.McpSession s = store.touch(id, "alice");
        assertEquals("2025-03-26", s.protocolVersion());
        assertEquals("alice", s.principal());
    }
}
