/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;

/** {@link SqlCaptureProbe} — what counts as a statement, and that a probe cleans up after itself. */
public class SqlCaptureProbeTest {

    @Test
    public void takesTheStatementFromAPlanerRecord() {
        LogRecord record = new LogRecord(Level.FINE, "SqlQuery: select \"t1\".\"c0\" from \"FOODMART\".\"SALES\"");
        assertEquals("select \"t1\".\"c0\" from \"FOODMART\".\"SALES\"", SqlCaptureProbe.extractSql(record));
    }

    @Test
    public void takesTheStatementFromADriverRecord() {
        LogRecord record = new LogRecord(Level.FINE, "Executing SQL: select count(*) from SALES");
        assertEquals("select count(*) from SALES", SqlCaptureProbe.extractSql(record));
    }

    @Test
    public void cutsAtTheFirstNewline() {
        LogRecord record = new LogRecord(Level.FINE, "SqlQuery: select 1 from SALES\nrows: 12");
        assertEquals("select 1 from SALES", SqlCaptureProbe.extractSql(record));
    }

    @Test
    public void ignoresRecordsThatCarryNoStatement() {
        assertNull(SqlCaptureProbe.extractSql(new LogRecord(Level.FINE, "Registered connection FoodMart")));
        assertNull(SqlCaptureProbe.extractSql(new LogRecord(Level.FINE, null)));
        assertNull(SqlCaptureProbe.extractSql(new LogRecord(Level.FINE, "select")));
    }

    @Test
    public void truncatesAnEnormousStatementRatherThanSendingItWhole() {
        String huge = "select " + "x".repeat(30_000);
        String captured = SqlCaptureProbe.extractSql(new LogRecord(Level.FINE, huge));
        assertTrue(captured.length() < huge.length());
        assertTrue(captured.endsWith("-- … truncated by Saiku"));
    }

    /**
     * The probe raises a global log level, so restoring it is the whole contract: a probe that
     * leaked would leave Mondrian logging every statement at FINE for the life of the JVM.
     */
    @Test
    public void restoresEveryLoggerLevelOnClose() {
        Logger planner = Logger.getLogger(SqlCaptureProbe.SQL_LOGGERS[0]);
        Level before = planner.getLevel();
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        assertEquals(Level.FINE, planner.getLevel());
        probe.close();
        assertEquals(before, planner.getLevel());
        // Idempotent: closing twice must not resurrect the level of a second probe.
        probe.close();
        assertEquals(before, planner.getLevel());
    }

    @Test
    public void capturesNothingWhenTheBackendLogsNoSql() {
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            assertFalse(probe.captured());
            assertNull(probe.representativeStatement());
        } finally {
            probe.close();
        }
    }

    @Test
    public void keepsTheStatementTheProbeActuallySaw() {
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            Logger.getLogger("mondrian.sql")
                    .log(new LogRecord(Level.FINE, "SqlQuery: select \"a\" from \"T\" where \"x\" = 1"));
            assertTrue(probe.captured());
            assertEquals("select \"a\" from \"T\" where \"x\" = 1", probe.representativeStatement());
        } finally {
            probe.close();
        }
    }
}
