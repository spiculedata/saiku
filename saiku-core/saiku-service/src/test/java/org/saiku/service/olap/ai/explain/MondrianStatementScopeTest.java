/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import mondrian.rolap.RolapConnection;
import mondrian.server.Execution;
import mondrian.server.Locus;
import mondrian.server.StatementImpl;
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

    /** A bare Mondrian statement: only its id matters to the scope. */
    private static final class TestStatement extends StatementImpl {
        @Override
        public void close() {}

        @Override
        public RolapConnection getMondrianConnection() {
            return null;
        }
    }

    /** Run {@code body} with a Locus for {@code statement}'s execution pushed on this thread. */
    private static boolean underLocusOf(StatementImpl statement, SqlCaptureProbe.Scope scope) {
        Locus locus = new Locus(new Execution(statement, 0), "test", "explain");
        Locus.push(locus);
        try {
            return scope.owns();
        } finally {
            Locus.pop(locus);
        }
    }

    @Test
    public void theOpeningThreadIsClaimedEvenUnderAnotherStatementsLocus() {
        // Mondrian parses the cell query on the caller's thread under the connection's internal
        // statement; with a warm segment cache that parse is the only SQL in the window.
        TestStatement explained = new TestStatement();
        TestStatement internal = new TestStatement();
        SqlCaptureProbe.Scope scope = MondrianStatementScope.forStatementId(explained.getId(), Thread.currentThread());
        assertTrue(underLocusOf(internal, scope));
    }

    @Test
    public void aWorkerThreadIsClaimedOnlyUnderTheExplainedStatementsLocus() throws Exception {
        TestStatement explained = new TestStatement();
        TestStatement foreign = new TestStatement();
        SqlCaptureProbe.Scope scope = MondrianStatementScope.forStatementId(explained.getId(), Thread.currentThread());
        AtomicBoolean mine = new AtomicBoolean();
        AtomicBoolean theirs = new AtomicBoolean(true);
        assertFalse(ownsOn(scope, () -> {
            mine.set(underLocusOf(explained, scope));
            theirs.set(underLocusOf(foreign, scope));
        }));
        assertTrue("a worker under the explained statement's Locus is claimed", mine.get());
        assertFalse("a worker under another statement's Locus is dropped", theirs.get());
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
