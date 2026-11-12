/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Captures the SQL a single cell query makes Mondrian emit, so the explain panel can show the
 * generated statement next to the MDX that produced it.
 *
 * <p><b>Why a log probe and not a generator.</b> The obvious approach — parse the MDX and call
 * Mondrian's {@code SqlQuery} / {@code SqlGenerator} — would render the statement the LEGACY
 * planner would have run. This fork defaults to Calcite ({@code docs/mondrian-fork.md}), and the
 * two backends produce different SQL from the same query; showing the legacy text on a Calcite
 * deployment would be a confidently wrong answer. There is no supported accessor on the olap4j
 * result path for the string the planner decided on, but {@code SqlStatement} logs every statement
 * it executes at {@code DEBUG} on {@code mondrian.sql} ("&lt;id&gt;: executing sql [select …]"),
 * so the honest way to surface it is to listen for the duration of one execution.
 *
 * <p><b>Which logging system.</b> Mondrian logs through slf4j, which this webapp binds to log4j2
 * ({@code log4j-slf4j2-impl}); it does NOT use {@code java.util.logging}, so a JUL handler never
 * sees a record (saiku#2193). The probe therefore attaches a log4j2 appender to the Mondrian
 * loggers and lowers their level to {@code DEBUG} while it is open.
 *
 * <p><b>Cost and blast radius.</b> A probe only exists between {@link #open()} and {@link
 * #close()}, and closing detaches the appender, removes any logger config it created and restores
 * every level it touched. {@code DEBUG} is chatty, which is why the probe is scoped to the one cell
 * query instead of being left on; appenders already attached to those loggers will also see the
 * extra records for that window. This is deliberately NOT {@link AutoCloseable}: a {@code close()}
 * inside try-with-resources would hide the fact that level restoration is a global side effect.
 *
 * <p><b>Whose statements (saiku#2328).</b> The loggers are process-global, so while a probe is
 * open every concurrent user's SQL reaches its appender. The appender therefore accepts an event
 * only when the probe's {@link Scope} claims it. A scope is evaluated on the LOGGING thread, so it
 * can recognise Mondrian worker threads (shepherd, segment loading) that are running the explained
 * execution; the default scope claims only the thread that opened the probe. A statement that is
 * not provably this execution's is dropped: a panel that misses a line is acceptable, one that
 * shows another user's SQL (filter literals, role predicates) is not.
 *
 * <p>The list is synchronized — a probe that silently lost its own statements would produce a
 * panel that quietly lies, which is worse than the cost.
 */
public final class SqlCaptureProbe {

    /** Loggers beneath which the planner emits its SQL. */
    static final String[] SQL_LOGGERS = {"mondrian.sql", "mondrian.olap", "mondrian.jdbc"};

    /** A driver-level "executing SQL" line rather than a planner statement. */
    private static final int MIN_SQL_LENGTH = 16;

    private static final int DEFAULT_MAX_STATEMENTS = 5;
    private static final int MAX_CAPTURED_LENGTH = 20_000;
    private static final String EXECUTING_MARKER = "executing sql [";
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final LoggerContext context;
    private final List<LoggerConfig> touched = new ArrayList<>();
    private final List<Level> previousLevels = new ArrayList<>();
    private final List<Boolean> created = new ArrayList<>();
    private final List<String> statements = Collections.synchronizedList(new ArrayList<>());
    private final int maxStatements;
    private final CaptureAppender appender;
    private final Scope scope;
    private volatile boolean open = true;

    /**
     * Decides, on the thread that is logging, whether the event being delivered belongs to the
     * execution this probe was opened for.
     */
    @FunctionalInterface
    public interface Scope {
        boolean owns();

        /** Claims only events logged by {@code thread} (the default for a probe). */
        static Scope thread(Thread thread) {
            return () -> Thread.currentThread() == thread;
        }
    }

    private final class CaptureAppender extends AbstractAppender {
        CaptureAppender() {
            super(
                    "saiku-sql-capture-" + SEQUENCE.incrementAndGet(),
                    null,
                    null,
                    true,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (!open || event == null || event.getMessage() == null || !ownsCurrentEvent()) {
                return;
            }
            String sql = extractSql(event.getMessage().getFormattedMessage());
            if (sql == null) {
                return;
            }
            synchronized (statements) {
                if (statements.size() < maxStatements && !statements.contains(sql)) {
                    statements.add(sql);
                }
            }
        }
    }

    private boolean ownsCurrentEvent() {
        try {
            return scope.owns();
        } catch (RuntimeException e) {
            // Fail closed: when ownership cannot be established the statement is not shown.
            return false;
        }
    }

    private SqlCaptureProbe(int maxStatements, Scope scope) {
        this.maxStatements = Math.max(1, maxStatements);
        this.scope = scope;
        this.context = (LoggerContext) LogManager.getContext(SqlCaptureProbe.class.getClassLoader(), false);
        this.appender = new CaptureAppender();
    }

    /** Open a probe, lowering the planner loggers to {@code DEBUG}. Must be {@link #close()}d. */
    public static SqlCaptureProbe open() {
        return open(Scope.thread(Thread.currentThread()));
    }

    /** Open a probe that captures only the events {@code scope} claims. */
    public static SqlCaptureProbe open(Scope scope) {
        return open(DEFAULT_MAX_STATEMENTS, scope);
    }

    public static SqlCaptureProbe open(int maxStatements, Scope scope) {
        SqlCaptureProbe probe = new SqlCaptureProbe(maxStatements, scope);
        probe.attach();
        return probe;
    }

    private synchronized void attach() {
        Configuration config = context.getConfiguration();
        appender.start();
        for (String name : SQL_LOGGERS) {
            LoggerConfig existing = config.getLoggerConfig(name);
            boolean ownConfig = name.equals(existing.getName());
            LoggerConfig target = existing;
            if (!ownConfig) {
                // Only an ancestor (usually the root) configures this logger: give it a config of
                // its own so lowering its level doesn't turn DEBUG on for the whole application.
                target = new LoggerConfig(name, Level.DEBUG, true);
                config.addLogger(name, target);
            }
            touched.add(target);
            previousLevels.add(ownConfig ? target.getLevel() : null);
            created.add(!ownConfig);
            target.setLevel(Level.DEBUG);
            target.addAppender(appender, Level.DEBUG, null);
        }
        context.updateLoggers();
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

    /** Detach the appender and restore every logger level. Idempotent. */
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        Configuration config = context.getConfiguration();
        for (int i = 0; i < touched.size(); i++) {
            LoggerConfig target = touched.get(i);
            target.removeAppender(appender.getName());
            if (created.get(i)) {
                config.removeLogger(target.getName());
            } else {
                target.setLevel(previousLevels.get(i));
            }
        }
        appender.stop();
        context.updateLoggers();
    }

    /**
     * Pull a SQL statement out of a planner log message.
     *
     * <p>Mondrian logs "&lt;id&gt;: executing sql [select …]", possibly across several lines, and
     * the label differs between versions. When the bracketed form is present the whole bracket body
     * is the statement; otherwise everything from the first {@code select} to the end of its line is
     * taken. Messages without a {@code select} in them are not statements.
     */
    static String extractSql(String message) {
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        String sql;
        int marker = lower.indexOf(EXECUTING_MARKER);
        if (marker >= 0) {
            sql = message.substring(marker + EXECUTING_MARKER.length()).trim();
            if (sql.endsWith("]")) {
                sql = sql.substring(0, sql.length() - 1).trim();
            }
        } else {
            int select = lower.indexOf("select");
            if (select < 0) {
                return null;
            }
            sql = message.substring(select).trim();
            int newline = sql.indexOf('\n');
            if (newline >= 0) {
                sql = sql.substring(0, newline).trim();
            }
        }
        if (sql.length() < MIN_SQL_LENGTH || !sql.toLowerCase(Locale.ROOT).startsWith("select")) {
            return null;
        }
        if (sql.length() > MAX_CAPTURED_LENGTH) {
            sql = sql.substring(0, MAX_CAPTURED_LENGTH) + "\n-- … truncated by Saiku";
        }
        return sql;
    }
}
