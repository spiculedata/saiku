/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.ossie.OssieQueryService;

/**
 * IDOR tests for {@link OssieAsyncQueryService#getOwned(String, String, boolean)} — a foreign
 * caller must not be able to poll another user's queryId, whether the id exists or not (issue
 * #1403).
 *
 * <p>Uses a stub {@link OssieQueryService} so the async submit path executes synchronously
 * without a warehouse connection — we only care about ownership semantics here, not query
 * execution.
 */
public class OssieAsyncQueryServiceTest {

    private OssieAsyncQueryService async;

    @Before
    public void setUp() {
        async = new OssieAsyncQueryService(new StubOssieQueryService());
    }

    @After
    public void tearDown() {
        if (async != null) async.shutdown();
    }

    @Test
    public void ownerCanRetrieveTheirOwnHandle() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        // Give the worker a moment to run.
        Thread.sleep(200);
        OssieAsyncQueryService.Handle result = async.getOwned(h.getId(), "alice", false);
        assertNotNull("owner poll should return the handle", result);
        assertEquals(h.getId(), result.getId());
    }

    @Test
    public void foreignPollReturnsNull() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        Thread.sleep(200);
        OssieAsyncQueryService.Handle result = async.getOwned(h.getId(), "bob", false);
        assertNull("bob must not see alice's handle", result);
    }

    @Test
    public void unknownIdReturnsNullSameShapeAsForeignPoll() {
        // Same null-shape means the caller can't distinguish "not yours" from "doesn't exist"
        // — closes the IDOR-oracle vector.
        assertNull(async.getOwned("does-not-exist", "alice", false));
    }

    @Test
    public void adminCanRetrieveAnyHandle() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        Thread.sleep(200);
        OssieAsyncQueryService.Handle result = async.getOwned(h.getId(), "root", true);
        assertNotNull("admin poll should return the handle", result);
    }

    @Test
    public void cancelObeysOwnership() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        Thread.sleep(200);
        // Bob's cancel: 404-shape (returns false) — must NOT flip status to CANCELLED.
        assertTrue("bob's cancel should be rejected", !async.cancel(h.getId(), "bob", false));
        assertTrue(
                "handle status must not be CANCELLED after foreign cancel",
                h.getStatus() != OssieAsyncQueryService.Status.CANCELLED);
        // Alice's cancel succeeds (returns true).
        assertTrue("owner cancel should succeed", async.cancel(h.getId(), "alice", false));
    }

    private static ThinQuery newQuery() {
        ThinQuery tq = new ThinQuery();
        tq.setName("stub-" + System.nanoTime());
        tq.setQueryType("OSSIE");
        return tq;
    }

    /**
     * Minimal query service that returns null and completes fast. We're testing ownership, not
     * execution.
     */
    static final class StubOssieQueryService extends OssieQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            return null;
        }
    }
}
