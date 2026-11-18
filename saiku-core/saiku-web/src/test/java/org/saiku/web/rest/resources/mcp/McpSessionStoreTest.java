/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** saiku#2325: the session map must be bounded and idle entries must not live forever. */
public class McpSessionStoreTest {

    @Test
    public void createEvictsIdleSessionsWhenFull() throws Exception {
        McpSessionStore store = new McpSessionStore(10L, 3);
        store.create("v");
        store.create("v");
        store.create("v");
        Thread.sleep(30L);

        store.create("v");

        assertEquals(1, store.size());
    }

    @Test
    public void sizeNeverExceedsMax() {
        McpSessionStore store = new McpSessionStore(60_000L, 5);
        String last = null;
        for (int i = 0; i < 50; i++) {
            last = store.create("v");
        }

        assertTrue("size=" + store.size(), store.size() <= 5);
        assertNotNull("most recent session must survive", store.touch(last));
    }

    @Test
    public void leastRecentlyActiveSessionIsEvictedWhenNothingIsIdle() throws Exception {
        McpSessionStore store = new McpSessionStore(60_000L, 2);
        String oldest = store.create("v");
        Thread.sleep(5L);
        String middle = store.create("v");
        Thread.sleep(5L);
        store.touch(oldest); // oldest is now the most recently active

        store.create("v");

        assertNotNull(store.touch(oldest));
        assertNull(store.touch(middle));
    }

    @Test
    public void untouchedIdleSessionsAreSweptOnTouchOfAnotherId() throws Exception {
        McpSessionStore store = new McpSessionStore(20L, 100);
        store.create("v"); // A, never touched again
        Thread.sleep(40L);
        String b = store.create("v");
        Thread.sleep(5L);

        store.touch(b);

        assertEquals("idle session A must be swept", 1, store.size());
    }
}
