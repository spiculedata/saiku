/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SqlCaptureProbe} — what counts as a statement, that it sees what Mondrian really logs
 * (slf4j, bound to log4j2 — saiku#2193), and that a probe cleans up after itself.
 */
public class SqlCaptureProbeTest {

    /** The logger Mondrian's {@code SqlStatement} writes to, obtained the way Mondrian obtains it. */
    private static final Logger MONDRIAN_SQL = LoggerFactory.getLogger("mondrian.sql");

    private static Configuration configuration() {
        return ((LoggerContext) LogManager.getContext(SqlCaptureProbe.class.getClassLoader(), false))
                .getConfiguration();
    }

    @Test
    public void takesTheStatementFromAMondrianRecord() {
        assertEquals(
                "select \"t1\".\"c0\" from \"FOODMART\".\"SALES\"",
                SqlCaptureProbe.extractSql("42: executing sql [select \"t1\".\"c0\" from \"FOODMART\".\"SALES\"]"));
    }

    @Test
    public void keepsAMultiLineBracketedStatementWhole() {
        assertEquals(
                "select a\nfrom SALES\nwhere x = 1",
                SqlCaptureProbe.extractSql("7: executing sql [select a\nfrom SALES\nwhere x = 1]"));
    }

    @Test
    public void takesTheStatementFromAnUnbracketedRecord() {
        assertEquals(
                "select count(*) from SALES", SqlCaptureProbe.extractSql("Executing SQL: select count(*) from SALES"));
    }

    @Test
    public void cutsAnUnbracketedStatementAtTheFirstNewline() {
        assertEquals("select 1 from SALES", SqlCaptureProbe.extractSql("SqlQuery: select 1 from SALES\nrows: 12"));
    }

    @Test
    public void ignoresRecordsThatCarryNoStatement() {
        assertNull(SqlCaptureProbe.extractSql("Registered connection FoodMart"));
        assertNull(SqlCaptureProbe.extractSql(null));
        assertNull(SqlCaptureProbe.extractSql("select"));
        assertNull(SqlCaptureProbe.extractSql("3: executing sql [commit]"));
    }

    @Test
    public void truncatesAnEnormousStatementRatherThanSendingItWhole() {
        String huge = "select " + "x".repeat(30_000);
        String captured = SqlCaptureProbe.extractSql(huge);
        assertTrue(captured.length() < huge.length());
        assertTrue(captured.endsWith("-- … truncated by Saiku"));
    }

    /** The regression: a record logged through slf4j, as Mondrian does, must reach the probe. */
    @Test
    public void capturesWhatMondrianLogsThroughSlf4j() {
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            MONDRIAN_SQL.debug("1: executing sql [select \"a\" from \"T\" where \"x\" = 1]");
            assertTrue(probe.captured());
            assertEquals("select \"a\" from \"T\" where \"x\" = 1", probe.representativeStatement());
        } finally {
            probe.close();
        }
    }

    @Test
    public void capturesFromAChildOfAProbedLogger() {
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            LoggerFactory.getLogger("mondrian.sql.execute").debug("2: executing sql [select 1 from DUAL_TABLE]");
            assertEquals("select 1 from DUAL_TABLE", probe.representativeStatement());
        } finally {
            probe.close();
        }
    }

    @Test
    public void capturesNothingWhenTheBackendLogsNoSql() {
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            MONDRIAN_SQL.debug("Registered connection FoodMart");
            assertFalse(probe.captured());
            assertNull(probe.representativeStatement());
        } finally {
            probe.close();
        }
    }

    @Test
    public void stopsCapturingOnceClosed() {
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        probe.close();
        MONDRIAN_SQL.debug("3: executing sql [select 1 from AFTER_CLOSE]");
        assertFalse(probe.captured());
    }

    /**
     * The probe raises a global log level, so restoring it is the whole contract: a probe that
     * leaked would leave Mondrian logging every statement at DEBUG for the life of the JVM.
     */
    @Test
    public void restoresEveryLoggerLevelAndLeavesNoAppenderOnClose() {
        Configuration config = configuration();
        Level before = config.getLoggerConfig(SqlCaptureProbe.SQL_LOGGERS[0]).getLevel();
        int appendersBefore = config.getLoggerConfig(SqlCaptureProbe.SQL_LOGGERS[0])
                .getAppenders()
                .size();

        SqlCaptureProbe probe = SqlCaptureProbe.open();
        assertEquals(
                Level.DEBUG,
                configuration().getLoggerConfig(SqlCaptureProbe.SQL_LOGGERS[0]).getLevel());
        assertTrue(MONDRIAN_SQL.isDebugEnabled());
        probe.close();
        // Idempotent: closing twice must not disturb a second probe's state.
        probe.close();

        Configuration after = configuration();
        assertEquals(
                before, after.getLoggerConfig(SqlCaptureProbe.SQL_LOGGERS[0]).getLevel());
        assertEquals(
                appendersBefore,
                after.getLoggerConfig(SqlCaptureProbe.SQL_LOGGERS[0])
                        .getAppenders()
                        .size());
        for (String name : SqlCaptureProbe.SQL_LOGGERS) {
            assertTrue(after.getLoggerConfig(name).getAppenders().values().stream()
                    .noneMatch(a -> a.getName().startsWith("saiku-sql-capture")));
        }
    }

    @Test
    public void loweringAProbedLoggerDoesNotTurnOnDebugForTheRestOfTheApplication() {
        Logger unrelated = LoggerFactory.getLogger("org.saiku.unrelated.probe.test");
        boolean before = unrelated.isDebugEnabled();
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            assertEquals(before, unrelated.isDebugEnabled());
        } finally {
            probe.close();
        }
    }

    /** saiku#2328: another user's statement, logged on another thread, must never be captured. */
    @Test
    public void ignoresAStatementLoggedByAnotherThread() throws Exception {
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            MONDRIAN_SQL.debug("1: executing sql [select \"mine\" from \"T\" where \"x\" = 1]");
            Thread stranger = new Thread(() -> MONDRIAN_SQL.debug(
                    "2: executing sql [select \"other_users_secret\" from \"T\" where \"country\" = 'Italy' and 1=1]"));
            stranger.start();
            stranger.join();
            assertEquals(1, probe.statements().size());
            assertEquals("select \"mine\" from \"T\" where \"x\" = 1", probe.representativeStatement());
        } finally {
            probe.close();
        }
    }

    /** Mondrian logs from worker pools, so a scope may claim a worker thread's event as its own. */
    @Test
    public void capturesAWorkerThreadStatementWhenTheScopeClaimsIt() throws Exception {
        AtomicBoolean mine = new AtomicBoolean(true);
        SqlCaptureProbe probe = SqlCaptureProbe.open(mine::get);
        try {
            Thread worker = new Thread(() -> MONDRIAN_SQL.debug("3: executing sql [select 1 from OWN_WORKER_TABLE]"));
            worker.start();
            worker.join();
            mine.set(false);
            Thread other = new Thread(() -> MONDRIAN_SQL.debug("4: executing sql [select 1 from FOREIGN_TABLE]"));
            other.start();
            other.join();
            assertEquals(List.of("select 1 from OWN_WORKER_TABLE"), probe.statements());
        } finally {
            probe.close();
        }
    }

    @Test
    public void interleavedProbesDoNotCrossFeed() throws Exception {
        SqlCaptureProbe.Scope onlyA = () -> "A".equals(Thread.currentThread().getName());
        SqlCaptureProbe.Scope onlyB = () -> "B".equals(Thread.currentThread().getName());
        SqlCaptureProbe probeA = SqlCaptureProbe.open(onlyA);
        SqlCaptureProbe probeB = SqlCaptureProbe.open(onlyB);
        try {
            Thread a = new Thread(() -> MONDRIAN_SQL.debug("5: executing sql [select 'from_a' from TABLE_A]"), "A");
            Thread b = new Thread(() -> MONDRIAN_SQL.debug("6: executing sql [select 'from_b' from TABLE_B]"), "B");
            a.start();
            b.start();
            a.join();
            b.join();
            assertEquals(List.of("select 'from_a' from TABLE_A"), probeA.statements());
            assertEquals(List.of("select 'from_b' from TABLE_B"), probeB.statements());
        } finally {
            probeB.close();
            probeA.close();
        }
    }

    @Test
    public void aScopeThatThrowsIsTreatedAsNotMine() {
        SqlCaptureProbe probe = SqlCaptureProbe.open(() -> {
            throw new IllegalStateException("boom");
        });
        try {
            MONDRIAN_SQL.debug("7: executing sql [select 1 from SOMEBODY_ELSE]");
            assertFalse(probe.captured());
        } finally {
            probe.close();
        }
    }
}
