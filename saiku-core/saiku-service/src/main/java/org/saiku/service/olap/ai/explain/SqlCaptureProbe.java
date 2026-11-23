/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
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
 * query instead of being left on; appenders already attached to those loggers are fenced (see {@link SqlLogFence}) so they do
 * not see the extra records for that window (saiku#2423). This is deliberately NOT {@link AutoCloseable}: a {@code close()}
 * inside try-with-resources would hide the fact that level restoration is a global side effect.
 *
 * <p><b>Overlapping probes.</b> The level is process-wide while each probe is per request, so the
 * level change is shared: the first probe to open records each logger's level and raises it, later
 * probes only attach their own appender, and the last probe to close puts back the level recorded
 * by the first. Probes may open and close in any order. A level a probe set is never recorded as
 * the one to restore, so closing always returns the loggers to the configured level. A probe that
 * is dropped without {@link #close()} is released when it is garbage collected, so a missed close
 * cannot keep the loggers at {@code DEBUG} for the life of the JVM. Opening fails closed: if it
 * throws, nothing it raised stays raised unless another open probe holds it.
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

    /** Name prefix of every probe's appender; the fence must never filter these. */
    static final String APPENDER_PREFIX = "saiku-sql-capture-";

    /** A driver-level "executing SQL" line rather than a planner statement. */
    private static final int MIN_SQL_LENGTH = 16;

    private static final int DEFAULT_MAX_STATEMENTS = 5;
    private static final int MAX_CAPTURED_LENGTH = 20_000;
    private static final String EXECUTING_MARKER = "executing sql [";
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Cleaner CLEANER = Cleaner.create();

    /** Guards the shared level state below; every level change happens under it. */
    private static final Object LEVELS = new Object();

    /** Probes currently open (attached and not yet released). Guarded by {@link #LEVELS}. */
    private static int activeProbes;

    /** The configuration the levels were raised on. Guarded by {@link #LEVELS}. */
    private static Configuration raisedOn;

    /** What the first probe found, to be put back by the last one. Guarded by {@link #LEVELS}. */
    private static final List<RaisedLogger> RAISED = new ArrayList<>();

    /** Keeps the probe's DEBUG records out of existing appenders. Guarded by {@link #LEVELS}. */
    private static SqlLogFence fence;

    /**
     * Records whose restore failed, kept so the next restore retries them and the next raise reuses
     * their recorded level instead of reading back the DEBUG left behind. Guarded by {@link #LEVELS}.
     */
    private static final List<RaisedLogger> UNRESTORED = new ArrayList<>();

    /** One logger's state before the first open probe raised it. */
    private record RaisedLogger(Configuration config, LoggerConfig target, Level previous, boolean created) {}

    private final LoggerContext context;
    private final List<String> statements = Collections.synchronizedList(new ArrayList<>());
    private final CaptureAppender appender;
    private final Cleaner.Cleanable release;

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

    /**
     * Static so that the appender, which the logger configuration holds, does not keep the probe
     * reachable; otherwise a probe that was never closed could never be released by the cleaner.
     */
    private static final class CaptureAppender extends AbstractAppender {
        private final List<String> statements;
        private final int maxStatements;
        /** Never refers to the probe (saiku#2328 scopes capture only a thread or a statement id). */
        private final Scope scope;

        private volatile boolean open = true;

        CaptureAppender(List<String> statements, int maxStatements, Scope scope) {
            super(
                    APPENDER_PREFIX + SEQUENCE.incrementAndGet(),
                    null,
                    null,
                    true,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
            this.statements = statements;
            this.maxStatements = Math.max(1, maxStatements);
            this.scope = scope;
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

        private boolean ownsCurrentEvent() {
            try {
                return scope.owns();
            } catch (Throwable t) {
                // Fail closed: when ownership cannot be established the statement is not shown.
                // An Error from a scope must also be swallowed here — the probe rides the logging
                // pipeline, and a throw per event would flood the log with stack traces for the
                // whole window the probe is open.
                return false;
            }
        }
    }

    /**
     * Detaches one probe's appender and drops its hold on the raised levels. Runs at most once,
     * from {@link #close()} or from the cleaner when a probe was never closed; it must not refer to
     * the probe itself.
     */
    private static final class Release implements Runnable {
        private final LoggerContext context;
        private final CaptureAppender appender;
        private final List<LoggerConfig> attachedTo;

        Release(LoggerContext context, CaptureAppender appender, List<LoggerConfig> attachedTo) {
            this.context = context;
            this.appender = appender;
            this.attachedTo = attachedTo;
        }

        @Override
        public void run() {
            synchronized (LEVELS) {
                try {
                    detach(appender, attachedTo);
                } finally {
                    activeProbes = Math.max(0, activeProbes - 1);
                    try {
                        if (activeProbes == 0) {
                            restoreLevels();
                        }
                    } finally {
                        context.updateLoggers();
                    }
                }
            }
        }
    }

    private SqlCaptureProbe(int maxStatements, Scope scope, BiFunction<Object, Runnable, Cleaner.Cleanable> registrar) {
        this.context = (LoggerContext) LogManager.getContext(SqlCaptureProbe.class.getClassLoader(), false);
        this.appender = new CaptureAppender(statements, maxStatements, scope);
        Release attached = attach(context, appender);
        try {
            this.release = registrar.apply(this, attached);
        } catch (Throwable t) {
            // Without a registered release nothing would ever drop this probe's hold on the level.
            try {
                attached.run();
            } catch (Throwable suppressed) {
                if (suppressed != t) {
                    t.addSuppressed(suppressed);
                }
            }
            throw t;
        }
    }

    /** Open a probe, lowering the planner loggers to {@code DEBUG}. Must be {@link #close()}d. */
    public static SqlCaptureProbe open() {
        return open(DEFAULT_MAX_STATEMENTS);
    }

    /** Open a probe that captures only the events logged by the calling thread. */
    public static SqlCaptureProbe open(int maxStatements) {
        return open(maxStatements, Scope.thread(Thread.currentThread()));
    }

    /** Open a probe that captures only the events {@code scope} claims. */
    public static SqlCaptureProbe open(Scope scope) {
        return open(DEFAULT_MAX_STATEMENTS, scope);
    }

    public static SqlCaptureProbe open(int maxStatements, Scope scope) {
        return new SqlCaptureProbe(maxStatements, scope, CLEANER::register);
    }

    /**
     * Test seam: open with a substitute for {@link Cleaner#register}, scoped to the calling thread.
     */
    static SqlCaptureProbe open(int maxStatements, BiFunction<Object, Runnable, Cleaner.Cleanable> registrar) {
        return new SqlCaptureProbe(maxStatements, Scope.thread(Thread.currentThread()), registrar);
    }

    /** Number of probes currently holding the raised levels. For tests. */
    static int activeProbeCount() {
        synchronized (LEVELS) {
            return activeProbes;
        }
    }

    /**
     * Raise the levels if no probe holds them yet, then attach this probe's appender. Fails closed:
     * if anything here throws, the appender is detached and, unless another open probe holds them,
     * every raised level is put back before the failure propagates.
     */
    private static Release attach(LoggerContext context, CaptureAppender appender) {
        synchronized (LEVELS) {
            List<LoggerConfig> attachedTo = new ArrayList<>();
            try {
                Configuration config = context.getConfiguration();
                if (activeProbes == 0 || raisedOn != config) {
                    // A reconfiguration while probes were open leaves the old raise on a
                    // configuration that is no longer live; raise afresh on the live one.
                    raiseLevels(config);
                }
                appender.start();
                for (RaisedLogger raised : RAISED) {
                    attachedTo.add(raised.target());
                    raised.target().addAppender(appender, Level.DEBUG, null);
                }
                context.updateLoggers();
                // Counted last, so a failure above never leaves a hold with no release behind it.
                Release release = new Release(context, appender, attachedTo);
                activeProbes++;
                return release;
            } catch (Throwable t) {
                try {
                    try {
                        detach(appender, attachedTo);
                    } finally {
                        try {
                            if (activeProbes == 0) {
                                restoreLevels();
                            }
                        } finally {
                            context.updateLoggers();
                        }
                    }
                } catch (Throwable suppressed) {
                    if (suppressed != t) {
                        t.addSuppressed(suppressed);
                    }
                }
                throw t;
            }
        }
    }

    /**
     * Remove one probe's appender from every logger it was attached to. The appender is closed
     * rather than stopped: events already dispatched to it are dropped quietly instead of making
     * log4j report an append to a stopped appender. It holds no resources.
     */
    private static void detach(CaptureAppender appender, List<LoggerConfig> attachedTo) {
        appender.open = false;
        Throwable first = null;
        for (LoggerConfig target : attachedTo) {
            try {
                target.removeAppender(appender.getName());
            } catch (Throwable t) {
                first = collect(first, t);
            }
        }
        rethrow(first);
    }

    /** Record each logger's level and lower it to {@code DEBUG}. Caller holds {@link #LEVELS}. */
    private static void raiseLevels(Configuration config) {
        removeFence();
        RAISED.clear();
        raisedOn = config;
        // saiku#2423: the loggers are process-wide, so fence the appenders that were already there
        // BEFORE any level changes, or another execution's first DEBUG records slip through.
        Map<String, Level> levelsBefore = new HashMap<>();
        List<LoggerConfig> governing = new ArrayList<>();
        for (String name : SQL_LOGGERS) {
            LoggerConfig existing = config.getLoggerConfig(name);
            RaisedLogger unrestored = name.equals(existing.getName()) ? peekUnrestored(existing) : null;
            governing.add(existing);
            levelsBefore.put(
                    name,
                    unrestored == null
                            ? existing.getLevel()
                            : unrestored.previous() != null
                                    ? unrestored.previous()
                                    : config.getRootLogger().getLevel());
        }
        fence = SqlLogFence.install(config, levelsBefore, governing);
        for (String name : SQL_LOGGERS) {
            LoggerConfig existing = config.getLoggerConfig(name);
            boolean ownConfig = name.equals(existing.getName());
            RaisedLogger unrestored = ownConfig ? takeUnrestored(existing) : null;
            if (unrestored != null) {
                // A previous restore of this logger failed: its level now is not the configured one.
                RAISED.add(unrestored);
                existing.setLevel(Level.DEBUG);
            } else if (ownConfig) {
                // Recorded before it is changed, so a failure part-way is still put back.
                RAISED.add(new RaisedLogger(config, existing, existing.getLevel(), false));
                existing.setLevel(Level.DEBUG);
            } else {
                // Only an ancestor (usually the root) configures this logger: give it a config of
                // its own so lowering its level doesn't turn DEBUG on for the whole application.
                LoggerConfig target = new LoggerConfig(name, Level.DEBUG, true);
                RAISED.add(new RaisedLogger(config, target, null, true));
                config.addLogger(name, target);
            }
        }
    }

    private static RaisedLogger peekUnrestored(LoggerConfig target) {
        for (RaisedLogger candidate : UNRESTORED) {
            if (candidate.target() == target) {
                return candidate;
            }
        }
        return null;
    }

    private static void removeFence() {
        SqlLogFence old = fence;
        fence = null;
        if (old != null) {
            old.remove();
        }
    }

    private static RaisedLogger takeUnrestored(LoggerConfig target) {
        for (int i = 0; i < UNRESTORED.size(); i++) {
            if (UNRESTORED.get(i).target() == target) {
                return UNRESTORED.remove(i);
            }
        }
        return null;
    }

    /**
     * Put back what {@link #raiseLevels} recorded, plus anything an earlier restore failed to put
     * back. Each logger is attempted on its own; one that fails is kept for the next restore, and
     * the first failure is re-thrown with the rest suppressed. Caller holds {@link #LEVELS}.
     */
    private static void restoreLevels() {
        List<RaisedLogger> todo = new ArrayList<>(UNRESTORED);
        todo.addAll(RAISED);
        UNRESTORED.clear();
        RAISED.clear();
        raisedOn = null;
        Throwable first = null;
        for (RaisedLogger raised : todo) {
            try {
                restore(raised);
            } catch (Throwable t) {
                UNRESTORED.add(raised);
                first = collect(first, t);
            }
        }
        // Last, and after the loggers have picked the levels up: until then DEBUG records are
        // still being produced and the fence is what keeps them out of the existing appenders.
        try {
            ((LoggerContext) LogManager.getContext(SqlCaptureProbe.class.getClassLoader(), false)).updateLoggers();
        } catch (Throwable t) {
            first = collect(first, t);
        }
        try {
            removeFence();
        } catch (Throwable t) {
            first = collect(first, t);
        }
        rethrow(first);
    }

    private static void restore(RaisedLogger raised) {
        LoggerConfig target = raised.target();
        if (raised.created()) {
            if (raised.config().getLoggers().get(target.getName()) == target) {
                raised.config().removeLogger(target.getName());
            }
        } else {
            target.setLevel(raised.previous());
        }
    }

    /** The first failure wins; later ones ride along as suppressed. */
    private static Throwable collect(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (next != first) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
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

    /**
     * Detach the appender; the last open probe also restores every logger level. Idempotent.
     */
    public void close() {
        release.clean();
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
