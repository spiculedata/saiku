/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures the SQL a single cell query makes Mondrian emit, so the explain panel can show the
 * generated statement next to the MDX that produced it.
 *
 * <p><b>Why a log probe and not a generator.</b> The obvious approach — parse the MDX and call
 * Mondrian's {@code SqlQuery} / {@code SqlGenerator} — would render the statement the LEGACY
 * planner would have run. This fork defaults to Calcite ({@code docs/mondrian-fork.md}), and the
 * two backends produce different SQL from the same query; showing the legacy text on a Calcite
 * deployment would be a confidently wrong answer. There is no supported accessor on the olap4j
 * result path for the string the planner decided on, but the planner does log it at {@code FINE}
 * on {@code mondrian.sql} / {@code mondrian.olap}, so the honest way to surface it is to listen
 * for the duration of one execution.
 *
 * <p><b>Cost and blast radius.</b> A probe only exists between {@link #open()} and {@link
 * #close()}, and closing restores every logger level it touched. {@code FINE} is chatty, which is
 * why the probe is scoped to the one cell query instead of being left on. This is deliberately NOT
 * {@link AutoCloseable}: a {@code close()} inside try-with-resources would hide the fact that
 * level restoration is a global side effect.
 *
 * <p>Threading: {@code java.util.logging} publishes on the thread that logged the record, and the
 * cell query runs on the caller's thread, so no hand-off is involved. The list is synchronized
 * anyway — a probe that silently lost statements would produce a panel that quietly lies, which is
 * worse than the cost.
 */
public final class SqlCaptureProbe {

    /** Loggers beneath which the planner emits its SQL. */
    static final String[] SQL_LOGGERS = {"mondrian.sql", "mondrian.olap", "mondrian.jdbc"};

    /** A driver-level "executing SQL" line rather than a planner statement. */
    private static final int MIN_SQL_LENGTH = 16;

    private static final int DEFAULT_MAX_STATEMENTS = 5;
    private static final int MAX_CAPTURED_LENGTH = 20_000;

    private final List<Logger> touched = new ArrayList<>();
    private final List<Level> previousLevels = new ArrayList<>();
    private final List<String> statements = Collections.synchronizedList(new ArrayList<>());
    private final int maxStatements;
    private volatile boolean open = true;

    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record == null || !open) {
                return;
            }
            if (record.getLevel().intValue() > Level.FINE.intValue()) {
                return;
            }
            String sql = extractSql(record);
            if (sql == null) {
                return;
            }
            synchronized (statements) {
                if (statements.size() < maxStatements && !statements.contains(sql)) {
                    statements.add(sql);
                }
            }
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Level restoration belongs to the probe, not to the handler.
        }
    };

    private SqlCaptureProbe(int maxStatements) {
        this.maxStatements = Math.max(1, maxStatements);
    }

    /** Open a probe, lowering the planner loggers to {@code FINE}. Must be {@link #close()}d. */
    public static SqlCaptureProbe open() {
        return open(DEFAULT_MAX_STATEMENTS);
    }

    public static SqlCaptureProbe open(int maxStatements) {
        SqlCaptureProbe probe = new SqlCaptureProbe(maxStatements);
        for (String name : SQL_LOGGERS) {
            Logger logger = Logger.getLogger(name);
            probe.touched.add(logger);
            probe.previousLevels.add(logger.getLevel());
            logger.setLevel(Level.FINE);
            logger.addHandler(probe.handler);
        }
        return probe;
    }

    /** Statements seen, in the order they were logged; empty when the backend logged nothing. */
    public List<String> statements() {
        synchronized (statements) {
            return List.copyOf(statements);
        }
    }

    /**
     * The statement most likely to be the one that produced the cell, or {@code null} when nothing
     * was captured. The planner logs its statement before the driver logs its round trip, so the
     * first capture is the one the user wants; a longer statement is preferred when both are
     * present, because a driver "executing" line can be a schema probe.
     */
    public String representativeStatement() {
        List<String> all = statements();
        String best = null;
        for (String sql : all) {
            if (best == null || sql.length() > best.length()) {
                best = sql;
            }
        }
        return best;
    }

    /** True when at least one statement was captured. */
    public boolean captured() {
        return !statements().isEmpty();
    }

    /** Detach the handler and restore every logger level. Idempotent. */
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        for (int i = 0; i < touched.size(); i++) {
            Logger logger = touched.get(i);
            logger.removeHandler(handler);
            logger.setLevel(previousLevels.get(i));
        }
    }

    /**
     * Pull a SQL statement out of a planner log record.
     *
     * <p>The record text carries a run id and a label ("SqlQuery: select …", "Executing SQL:
     * select …") whose exact form shifts between Mondrian versions, so rather than strip a format
     * that isn't stable, everything from the first {@code select} onwards is taken and a trailing
     * newline cut. Records without a {@code select} in them are not statements.
     */
    static String extractSql(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return null;
        }
        int select = message.toLowerCase(Locale.ROOT).indexOf("select");
        if (select < 0) {
            return null;
        }
        String sql = message.substring(select).trim();
        int newline = sql.indexOf('\n');
        if (newline >= 0) {
            sql = sql.substring(0, newline).trim();
        }
        if (sql.length() < MIN_SQL_LENGTH) {
            return null;
        }
        if (sql.length() > MAX_CAPTURED_LENGTH) {
            sql = sql.substring(0, MAX_CAPTURED_LENGTH) + "\n-- … truncated by Saiku";
        }
        return sql;
    }
}
