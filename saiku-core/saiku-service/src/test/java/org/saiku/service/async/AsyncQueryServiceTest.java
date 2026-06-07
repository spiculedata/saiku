/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.async;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.olap4j.CellSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.util.QueryContext;

/**
 * Unit tests for {@link AsyncQueryService}. Drives a synthetic {@link ThinQueryService}
 * so the lifecycle is exercised without needing a live OLAP connection.
 */
public class AsyncQueryServiceTest {

    private AsyncQueryService svc;

    @After
    public void tearDown() {
        if (svc != null) {
            svc.shutdown();
        }
    }

    @Test(expected = IllegalStateException.class)
    public void submit_throwsWhenThinQueryServiceUnwired() {
        svc = new AsyncQueryService();
        svc.submit(named("anything"));
    }

    @Test
    public void submit_returnsHandleAndTransitionsToDone() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q1"));
        assertNotNull(h);
        assertNotNull(h.getId());
        // wait up to 2s for executor to drain.
        awaitStatus(h, AsyncQueryHandle.Status.DONE, 2000);
        assertEquals(AsyncQueryHandle.Status.DONE, h.getStatus());
        assertEquals(1, stub.executeCount.get());
    }

    @Test
    public void submit_recordsFailureWhenExecuteThrows() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        stub.failure = new RuntimeException("boom");
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q2"));
        awaitStatus(h, AsyncQueryHandle.Status.FAILED, 2000);
        assertEquals(AsyncQueryHandle.Status.FAILED, h.getStatus());
        assertEquals("boom", h.getErrorMessage());
    }

    @Test
    public void submit_recordsFailureWithClassNameWhenMessageIsNull() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        stub.failure = new NullPointerException();
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-npe"));
        awaitStatus(h, AsyncQueryHandle.Status.FAILED, 2000);
        assertEquals("NullPointerException", h.getErrorMessage());
    }

    @Test
    public void submit_propagatesRejectedExecutionAndRemovesHandle() {
        // Single-slot, no-queue, abort-policy executor → first task occupies the
        // worker slot, second submission is rejected.
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(
                1, 1, 1L, TimeUnit.MINUTES, new java.util.concurrent.SynchronousQueue<>(), daemonFactory("test-rej"));
        saturated.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        StubThinQueryService stub = new StubThinQueryService();
        stub.block = new CountDownLatch(1); // first submission parks the worker
        svc = new AsyncQueryService(saturated);
        svc.setThinQueryService(stub);

        AsyncQueryHandle blocking = svc.submit(named("blocker"));
        try {
            svc.submit(named("rejected"));
            fail("expected RejectedExecutionException");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            // rejected handle must NOT linger in the map.
            assertEquals(1, svc.size());
            assertEquals(blocking.getStatus(), svc.get(blocking.getId()).getStatus());
        } finally {
            stub.block.countDown();
        }
    }

    @Test
    public void get_returnsHandleAndTouches() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-get"));
        awaitStatus(h, AsyncQueryHandle.Status.DONE, 2000);

        long before = h.getLastAccessedAt();
        Thread.sleep(5);
        AsyncQueryHandle fetched = svc.get(h.getId());
        assertSame(h, fetched);
        assertTrue("touch should advance lastAccessedAt", fetched.getLastAccessedAt() >= before);
    }

    @Test
    public void get_returnsNullForUnknownId() {
        svc = new AsyncQueryService();
        svc.setThinQueryService(new StubThinQueryService());
        assertNull(svc.get("nope"));
    }

    @Test
    public void register_storesExternalHandle() {
        svc = new AsyncQueryService();
        AsyncQueryHandle h = new AsyncQueryHandle("ext-1", named("x"));
        svc.register(h);
        assertSame(h, svc.get("ext-1"));
        assertEquals(1, svc.size());
    }

    @Test
    public void register_nullIsNoOp() {
        svc = new AsyncQueryService();
        svc.register(null);
        assertEquals(0, svc.size());
    }

    @Test
    public void result_nullUntilDoneThenReturnsCellSetAndMarksFetched() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        stub.block = new CountDownLatch(1);
        CellSet expected = newProxyCellSet(null);
        stub.resultCellSet = expected;
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-res"));
        assertNull("result null while RUNNING/PENDING", svc.result(h.getId()));

        stub.block.countDown();
        awaitStatus(h, AsyncQueryHandle.Status.DONE, 2000);

        CellSet got = svc.result(h.getId());
        assertSame(expected, got);
        assertTrue("markResultFetched should fire", h.isResultFetched());
    }

    @Test
    public void result_returnsNullForUnknownId() {
        svc = new AsyncQueryService();
        svc.setThinQueryService(new StubThinQueryService());
        assertNull(svc.result("nope"));
    }

    @Test
    public void cancel_unknownIdReturnsFalse() {
        svc = new AsyncQueryService();
        svc.setThinQueryService(new StubThinQueryService());
        assertFalse(svc.cancel("nope"));
    }

    @Test
    public void cancel_flipsStatusAndCallsThinCancel() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        stub.block = new CountDownLatch(1);
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-cancel"));
        assertTrue(svc.cancel(h.getId()));
        assertEquals(AsyncQueryHandle.Status.CANCELLED, h.getStatus());

        // cancelExecutor is async — give it time to dispatch.
        stub.block.countDown();
        long deadline = System.currentTimeMillis() + 2000;
        while (stub.cancelCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("ThinQueryService.cancel should be invoked once", 1, stub.cancelCount.get());
    }

    @Test
    public void cancel_swallowsSqlExceptionFromThinCancel() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        stub.cancelFailure = new SQLException("statement gone");
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-cancel-fail"));
        awaitStatus(h, AsyncQueryHandle.Status.DONE, 2000);
        assertTrue(svc.cancel(h.getId()));
        // No exception bubbles. Status remains CANCELLED.
        assertEquals(AsyncQueryHandle.Status.CANCELLED, h.getStatus());
    }

    @Test
    public void sweep_evictsFetchedHandlesAfterIdleWindow() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        stub.resultCellSet = newProxyCellSet(null);
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-sweep"));
        awaitStatus(h, AsyncQueryHandle.Status.DONE, 2000);
        svc.result(h.getId()); // marks fetched
        backdateLastAccessed(h, TimeUnit.MINUTES.toMillis(10));

        svc.sweep();
        assertEquals("fetched + idle > 5m must be evicted", 0, svc.size());
    }

    @Test
    public void sweep_evictsTerminalHandlesAfterFailsafeWindow() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-failsafe"));
        awaitStatus(h, AsyncQueryHandle.Status.DONE, 2000);
        // not fetched — only the 30-min failsafe should reap.
        backdateLastAccessed(h, TimeUnit.MINUTES.toMillis(45));

        svc.sweep();
        assertEquals(0, svc.size());
    }

    @Test
    public void sweep_leavesNonTerminalHandlesAlone() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        stub.block = new CountDownLatch(1);
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-running"));
        // Backdate the timestamp anyway — sweeper must skip non-terminal.
        backdateLastAccessed(h, TimeUnit.MINUTES.toMillis(120));
        svc.sweep();
        assertEquals(1, svc.size());
        stub.block.countDown();
    }

    @Test
    public void sweep_leavesRecentTerminalHandles() throws Exception {
        StubThinQueryService stub = new StubThinQueryService();
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-recent"));
        awaitStatus(h, AsyncQueryHandle.Status.DONE, 2000);
        // No backdating — handle is fresh, idle ≈ 0.
        svc.sweep();
        assertEquals(1, svc.size());
    }

    @Test
    public void size_reflectsHandleMap() {
        svc = new AsyncQueryService();
        svc.setThinQueryService(new StubThinQueryService());
        assertEquals(0, svc.size());
        svc.register(new AsyncQueryHandle("a", named("a")));
        svc.register(new AsyncQueryHandle("b", named("b")));
        assertEquals(2, svc.size());
    }

    @Test
    public void shutdown_stopsExecutors() {
        StubThinQueryService stub = new StubThinQueryService();
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);
        svc.shutdown();
        // After shutdown the work executor should reject new submissions.
        try {
            svc.submit(named("post-shutdown"));
            fail("expected RejectedExecutionException after shutdown");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            // OK
        }
        svc = null; // prevent double-shutdown in @After
    }

    /* ----------------- #1165 audit-3: owner binding (IDOR) ------------------ */

    @Test
    public void handle_ownerDefaultsToNullViaTwoArgCtor() {
        AsyncQueryHandle h = new AsyncQueryHandle("id", named("q"));
        assertNull("two-arg ctor leaves owner unset", h.getOwner());
        assertTrue("null owner matches a null caller", h.isOwnedBy(null));
        assertFalse("null owner does NOT match a named caller", h.isOwnedBy("alice"));
    }

    @Test
    public void handle_isOwnedBy_matchesExactPrincipalOnly() {
        AsyncQueryHandle h = new AsyncQueryHandle("id", named("q"), "alice");
        assertEquals("alice", h.getOwner());
        assertTrue(h.isOwnedBy("alice"));
        assertFalse(h.isOwnedBy("bob"));
        assertFalse(h.isOwnedBy(null));
    }

    @Test
    public void getOwned_returnsHandleForMatchingOwner() {
        svc = new AsyncQueryService();
        AsyncQueryHandle h = new AsyncQueryHandle("owned-1", named("q"), "alice");
        svc.register(h);
        assertSame(h, svc.getOwned("owned-1", "alice", false));
    }

    @Test
    public void getOwned_returnsNullForMismatchedOwner() {
        svc = new AsyncQueryService();
        AsyncQueryHandle h = new AsyncQueryHandle("owned-2", named("q"), "alice");
        svc.register(h);
        // Non-owner must get null (resource then 404s — no existence oracle).
        assertNull(svc.getOwned("owned-2", "bob", false));
        // ...and the handle is still there for its real owner.
        assertSame(h, svc.getOwned("owned-2", "alice", false));
    }

    @Test
    public void getOwned_returnsNullForUnknownId() {
        svc = new AsyncQueryService();
        assertNull(svc.getOwned("nope", "alice", false));
        assertNull("unknown id is null even for admin", svc.getOwned("nope", "alice", true));
    }

    @Test
    public void getOwned_adminBypassesOwnershipCheck() {
        svc = new AsyncQueryService();
        AsyncQueryHandle h = new AsyncQueryHandle("owned-3", named("q"), "alice");
        svc.register(h);
        assertSame("admin may access another user's handle", h, svc.getOwned("owned-3", "bob", true));
    }

    @Test
    public void cancelOwned_cancelsOnlyForOwnerOrAdmin() {
        StubThinQueryService stub = new StubThinQueryService();
        stub.block = new CountDownLatch(1);
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-owned-cancel"));
        h.setOwner("alice");

        // Non-owner cancel is refused and does NOT flip the status.
        assertFalse("non-owner cancel refused", svc.cancelOwned(h.getId(), "bob", false));
        assertNotSame(AsyncQueryHandle.Status.CANCELLED, h.getStatus());

        // Owner cancel proceeds.
        assertTrue("owner cancel proceeds", svc.cancelOwned(h.getId(), "alice", false));
        assertEquals(AsyncQueryHandle.Status.CANCELLED, h.getStatus());

        stub.block.countDown();
    }

    @Test
    public void cancelOwned_adminCancelsForeignHandle() {
        StubThinQueryService stub = new StubThinQueryService();
        stub.block = new CountDownLatch(1);
        svc = new AsyncQueryService();
        svc.setThinQueryService(stub);

        AsyncQueryHandle h = svc.submit(named("q-admin-cancel"));
        h.setOwner("alice");
        assertTrue("admin may cancel another user's handle", svc.cancelOwned(h.getId(), "bob", true));
        assertEquals(AsyncQueryHandle.Status.CANCELLED, h.getStatus());
        stub.block.countDown();
    }

    @Test
    public void cancelOwned_unknownIdReturnsFalse() {
        svc = new AsyncQueryService();
        svc.setThinQueryService(new StubThinQueryService());
        assertFalse(svc.cancelOwned("nope", "alice", false));
        assertFalse(svc.cancelOwned("nope", "alice", true));
    }

    // ---------------------------------------------------------------- helpers

    private static ThinQuery named(String name) {
        ThinQuery q = new ThinQuery();
        q.setName(name);
        return q;
    }

    private static void awaitStatus(AsyncQueryHandle h, AsyncQueryHandle.Status want, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (h.getStatus() != want && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (h.getStatus() != want) {
            throw new AssertionError("timed out waiting for status " + want + ", saw " + h.getStatus());
        }
    }

    private static void backdateLastAccessed(AsyncQueryHandle h, long deltaMs) throws Exception {
        Field f = AsyncQueryHandle.class.getDeclaredField("lastAccessedAt");
        f.setAccessible(true);
        long current = f.getLong(h);
        f.setLong(h, current - deltaMs);
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger i = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + i.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** A stub ThinQueryService that drives the async lifecycle without any OLAP. */
    private static final class StubThinQueryService extends ThinQueryService {
        final AtomicInteger executeCount = new AtomicInteger();
        final AtomicInteger cancelCount = new AtomicInteger();
        volatile RuntimeException failure;
        volatile SQLException cancelFailure;
        volatile CellSet resultCellSet;
        volatile CountDownLatch block;

        @Override
        public org.saiku.olap.dto.resultset.CellDataSet execute(ThinQuery tq) {
            executeCount.incrementAndGet();
            if (block != null) {
                try {
                    block.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted", ie);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return null; // AsyncQueryService doesn't read this — it pulls from getContext().
        }

        @Override
        public QueryContext getContext(String name) {
            QueryContext ctx = new QueryContext(QueryContext.Type.OLAP, named(name));
            if (resultCellSet != null) {
                ctx.store(QueryContext.ObjectKey.RESULT, resultCellSet);
            }
            return ctx;
        }

        @Override
        public void cancel(String name) throws SQLException {
            cancelCount.incrementAndGet();
            if (cancelFailure != null) {
                throw cancelFailure;
            }
        }
    }

    /**
     * Build a {@link CellSet} proxy. Returns sensible default values for
     * primitive return types and {@code null} for everything else — sufficient
     * for the AsyncQueryService surface, which never inspects the cellSet
     * beyond {@code close()}.
     */
    private static CellSet newProxyCellSet(Object unusedCloseFlag) {
        return (CellSet) Proxy.newProxyInstance(
                CellSet.class.getClassLoader(), new Class<?>[] {CellSet.class}, (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == float.class) return 0f;
                    if (rt == double.class) return 0d;
                    if (rt == char.class) return '\0';
                    return null;
                });
    }
}
