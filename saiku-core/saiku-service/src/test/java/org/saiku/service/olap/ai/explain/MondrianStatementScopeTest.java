/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** saiku#2328: which logging threads a cell explain may claim as its own. */
public class MondrianStatementScopeTest {

    private static boolean ownsOn(SqlCaptureProbe.Scope scope, Runnable setup) throws Exception {
        AtomicBoolean result = new AtomicBoolean();
        Thread t = new Thread(() -> {
            setup.run();
            result.set(scope.owns());
        });
        t.start();
        t.join();
        return result.get();
    }

    @Test
    public void withoutAStatementIdOnlyTheOpeningThreadIsClaimed() throws Exception {
        SqlCaptureProbe.Scope scope = MondrianStatementScope.forStatementId(null, Thread.currentThread());
        assertTrue(scope.owns());
        assertFalse(ownsOn(scope, () -> {}));
    }

    @Test
    public void aNonMondrianStatementDegradesToTheOpeningThread() throws Exception {
        SqlCaptureProbe.Scope scope = MondrianStatementScope.forStatement(null);
        assertTrue(scope.owns());
        assertFalse(ownsOn(scope, () -> {}));
    }

    @Test
    public void aThreadWithNoMondrianExecutionIsClaimedOnlyIfItIsTheOpeningThread() throws Exception {
        SqlCaptureProbe.Scope scope = MondrianStatementScope.forStatementId(7L, Thread.currentThread());
        assertTrue(scope.owns());
        assertFalse(ownsOn(scope, () -> {}));
    }
}
