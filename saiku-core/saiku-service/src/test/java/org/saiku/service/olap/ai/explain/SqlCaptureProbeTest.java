/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
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

    private static LoggerContext context() {
        return (LoggerContext) LogManager.getContext(SqlCaptureProbe.class.getClassLoader(), false);
    }

    private static Configuration configuration() {
        return context().getConfiguration();
    }

    /** Give {@code mondrian.sql} its own config at WARN, the shape the webapp's log4j2.xml ships. */
    private static void configureSqlLoggerAtWarn() {
        configuration().addLogger("mondrian.sql", new LoggerConfig("mondrian.sql", Level.WARN, true));
        context().updateLoggers();
    }

    private static void removeSqlLoggerConfig() {
        configuration().removeLogger("mondrian.sql");
        context().updateLoggers();
    }

    private static Level sqlLoggerLevel() {
        return configuration().getLoggerConfig("mondrian.sql").getLevel();
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

    @Test
    public void aSingleProbeRaisesAndRestoresAConfiguredLevel() {
        configureSqlLoggerAtWarn();
        try {
            SqlCaptureProbe probe = SqlCaptureProbe.open();
            try {
                assertEquals(Level.DEBUG, sqlLoggerLevel());
                MONDRIAN_SQL.debug("4: executing sql [select \"a\" from \"SINGLE\"]");
                assertEquals("select \"a\" from \"SINGLE\"", probe.representativeStatement());
            } finally {
                probe.close();
            }
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());
        } finally {
            removeSqlLoggerConfig();
        }
    }

    /** Two captures that overlap and close in the order they opened end at the configured level. */
    @Test
    public void overlappingProbesClosedInOpeningOrderEndAtTheConfiguredLevel() {
        configureSqlLoggerAtWarn();
        try {
            SqlCaptureProbe first = SqlCaptureProbe.open();
            SqlCaptureProbe second = SqlCaptureProbe.open();
            first.close();
            second.close();
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());
        } finally {
            removeSqlLoggerConfig();
        }
    }

    /**
     * Two captures that overlap and close in the order they opened. The second must keep capturing
     * after the first closes, and the level left behind must not depend on the closing order.
     */
    @Test
    public void overlappingProbesClosedInOpeningOrderRestoreTheConfiguredLevel() {
        configureSqlLoggerAtWarn();
        try {
            SqlCaptureProbe first = SqlCaptureProbe.open();
            SqlCaptureProbe second = SqlCaptureProbe.open();
            first.close();

            // The second capture is still open, so it must still see statements.
            assertEquals(Level.DEBUG, sqlLoggerLevel());
            MONDRIAN_SQL.debug("5: executing sql [select \"b\" from \"STILL_OPEN\"]");
            assertEquals("select \"b\" from \"STILL_OPEN\"", second.representativeStatement());
            assertFalse(first.captured());

            second.close();
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());

            // A later capture on its own still ends at the configured level.
            SqlCaptureProbe.open().close();
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());
        } finally {
            removeSqlLoggerConfig();
        }
    }

    @Test
    public void overlappingProbesClosedInReverseOrderRestoreTheConfiguredLevel() {
        configureSqlLoggerAtWarn();
        try {
            SqlCaptureProbe first = SqlCaptureProbe.open();
            SqlCaptureProbe second = SqlCaptureProbe.open();
            second.close();
            assertEquals(Level.DEBUG, sqlLoggerLevel());
            first.close();
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());
        } finally {
            removeSqlLoggerConfig();
        }
    }

    /** Same overlap where only an ancestor configures the logger, so the probe creates a config. */
    @Test
    public void overlappingProbesOnAnUnconfiguredLoggerKeepCapturingAndCleanUp() {
        Level inherited = sqlLoggerLevel();
        SqlCaptureProbe first = SqlCaptureProbe.open();
        SqlCaptureProbe second = SqlCaptureProbe.open();
        first.close();

        MONDRIAN_SQL.debug("6: executing sql [select \"c\" from \"CREATED\"]");
        assertEquals("select \"c\" from \"CREATED\"", second.representativeStatement());

        second.close();
        for (String name : SqlCaptureProbe.SQL_LOGGERS) {
            LoggerConfig config = configuration().getLoggerConfig(name);
            assertFalse("no probe-created config left for " + name, name.equals(config.getName()));
        }
        assertEquals(inherited, sqlLoggerLevel());
        assertFalse(MONDRIAN_SQL.isDebugEnabled());
    }

    // ---------------------------------------------------------------- failures while opening

    /** A config for {@code mondrian.olap}, the second logger a probe touches, that fails on demand. */
    private static final class FaultyLoggerConfig extends LoggerConfig {
        private final String mode;
        private volatile boolean armed = true;

        FaultyLoggerConfig(String mode) {
            super("mondrian.olap", Level.WARN, true);
            this.mode = mode;
        }

        @Override
        public void addAppender(Appender appender, Level level, Filter filter) {
            if (armed && mode.equals("addAppender-error")) {
                throw new InjectedError();
            }
            super.addAppender(appender, level, filter);
        }

        @Override
        public void setLevel(Level level) {
            if (armed && mode.equals("setLevel") && level == Level.DEBUG) {
                throw new IllegalStateException("injected");
            }
            if (armed && mode.equals("restore") && level != Level.DEBUG) {
                throw new IllegalStateException("injected");
            }
            super.setLevel(level);
        }
    }

    private static final class InjectedError extends Error {
        InjectedError() {
            super("injected");
        }
    }

    private static void removeOlapLoggerConfig() {
        configuration().removeLogger("mondrian.olap");
        context().updateLoggers();
    }

    /** No probe holds anything: configured levels, no capture appender, and a clean cycle after. */
    private static void assertNothingRaised() {
        assertEquals(0, SqlCaptureProbe.activeProbeCount());
        assertEquals(Level.WARN, sqlLoggerLevel());
        assertEquals(
                Level.WARN, configuration().getLoggerConfig("mondrian.olap").getLevel());
        assertFalse(MONDRIAN_SQL.isDebugEnabled());
        for (String name : SqlCaptureProbe.SQL_LOGGERS) {
            assertTrue(configuration().getLoggerConfig(name).getAppenders().values().stream()
                    .noneMatch(a -> a.getName().startsWith("saiku-sql-capture")));
        }
        SqlCaptureProbe.open().close();
        assertEquals(0, SqlCaptureProbe.activeProbeCount());
        assertEquals(Level.WARN, sqlLoggerLevel());
        assertFalse(MONDRIAN_SQL.isDebugEnabled());
    }

    /** Runs {@code body} with mondrian.sql at WARN and a faulty mondrian.olap config at WARN. */
    private static void withFaultyOlapLogger(String mode, Runnable body) {
        configureSqlLoggerAtWarn();
        FaultyLoggerConfig faulty = new FaultyLoggerConfig(mode);
        configuration().addLogger("mondrian.olap", faulty);
        context().updateLoggers();
        try {
            body.run();
            faulty.armed = false;
            assertNothingRaised();
        } finally {
            faulty.armed = false;
            removeOlapLoggerConfig();
            removeSqlLoggerConfig();
        }
    }

    /** Arms a listener that throws on the next {@code updateLoggers()} only. */
    private static AtomicBoolean failNextLoggerUpdate(PropertyChangeListener[] holder) {
        AtomicBoolean armed = new AtomicBoolean(true);
        holder[0] = evt -> {
            if (armed.compareAndSet(true, false)) {
                throw new IllegalStateException("injected");
            }
        };
        context().addPropertyChangeListener(holder[0]);
        return armed;
    }

    @Test
    public void anOpenThatFailsWhileRaisingLevelsLeavesNothingRaised() {
        withFaultyOlapLogger("setLevel", () -> {
            try {
                SqlCaptureProbe.open();
                fail("open should have failed");
            } catch (IllegalStateException expected) {
                // the first logger was already raised when the second one failed
            }
        });
    }

    @Test
    public void anOpenThatFailsWithAnErrorLeavesNothingRaised() {
        withFaultyOlapLogger("addAppender-error", () -> {
            try {
                SqlCaptureProbe.open();
                fail("open should have failed");
            } catch (InjectedError expected) {
                // an Error, not a RuntimeException
            }
        });
    }

    @Test
    public void anOpenThatFailsInTheFinalLoggerUpdateLeavesNothingRaised() {
        withFaultyOlapLogger("none", () -> {
            PropertyChangeListener[] listener = new PropertyChangeListener[1];
            failNextLoggerUpdate(listener);
            try {
                SqlCaptureProbe.open();
                fail("open should have failed");
            } catch (IllegalStateException expected) {
                // thrown by the logger update after the appender was attached
            } finally {
                context().removePropertyChangeListener(listener[0]);
            }
        });
    }

    @Test
    public void anOpenThatCannotRegisterItsReleaseLeavesNothingRaised() {
        withFaultyOlapLogger("none", () -> {
            try {
                SqlCaptureProbe.open(5, (probe, action) -> {
                    throw new InjectedError();
                });
                fail("open should have failed");
            } catch (InjectedError expected) {
                // the probe was attached and counted, but never got a release action
            }
        });
    }

    @Test
    public void aFailedOpenLeavesAnotherOpenProbesLevelInPlace() {
        configureSqlLoggerAtWarn();
        FaultyLoggerConfig faulty = new FaultyLoggerConfig("addAppender-error");
        faulty.armed = false;
        configuration().addLogger("mondrian.olap", faulty);
        context().updateLoggers();
        try {
            SqlCaptureProbe open = SqlCaptureProbe.open();
            faulty.armed = true;
            try {
                SqlCaptureProbe.open();
                fail("open should have failed");
            } catch (InjectedError expected) {
                // the rollback must not take back a level the open probe still holds
            }
            faulty.armed = false;
            assertEquals(1, SqlCaptureProbe.activeProbeCount());
            assertEquals(Level.DEBUG, sqlLoggerLevel());
            MONDRIAN_SQL.debug("7: executing sql [select \"d\" from \"HELD\"]");
            assertEquals("select \"d\" from \"HELD\"", open.representativeStatement());
            open.close();
            assertNothingRaised();
        } finally {
            faulty.armed = false;
            removeOlapLoggerConfig();
            removeSqlLoggerConfig();
        }
    }

    @Test
    public void aCloseThatFailsInTheFinalLoggerUpdateStillRestoresTheLevel() {
        configureSqlLoggerAtWarn();
        PropertyChangeListener[] listener = new PropertyChangeListener[1];
        try {
            SqlCaptureProbe probe = SqlCaptureProbe.open();
            failNextLoggerUpdate(listener);
            try {
                probe.close();
                fail("close should have failed");
            } catch (IllegalStateException expected) {
                // thrown by the logger update after the level was restored
            }
            context().removePropertyChangeListener(listener[0]);
            context().updateLoggers();
            assertEquals(0, SqlCaptureProbe.activeProbeCount());
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());
        } finally {
            context().removePropertyChangeListener(listener[0]);
            removeSqlLoggerConfig();
        }
    }

    @Test
    public void aFailedRestoreOfOneLoggerStillRestoresTheOthersAndIsRetried() {
        configureSqlLoggerAtWarn();
        FaultyLoggerConfig faulty = new FaultyLoggerConfig("restore");
        faulty.armed = false;
        configuration().addLogger("mondrian.olap", faulty);
        context().updateLoggers();
        try {
            SqlCaptureProbe probe = SqlCaptureProbe.open();
            faulty.armed = true;
            try {
                probe.close();
                fail("close should have failed");
            } catch (IllegalStateException expected) {
                // mondrian.olap could not be put back
            }
            faulty.armed = false;
            // The loggers before and after the failing one were still restored.
            assertEquals(0, SqlCaptureProbe.activeProbeCount());
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(
                    "probe-created config for mondrian.jdbc removed",
                    "mondrian.jdbc"
                            .equals(configuration()
                                    .getLoggerConfig("mondrian.jdbc")
                                    .getName()));
            assertEquals(Level.DEBUG, faulty.getLevel());
            // The next cycle puts it back to its configured level, not the DEBUG it was left at.
            SqlCaptureProbe.open().close();
            assertNothingRaised();
        } finally {
            faulty.armed = false;
            removeOlapLoggerConfig();
            removeSqlLoggerConfig();
        }
    }

    /** A rollback that fails with the very same throwable must not replace it with another one. */
    @Test
    public void aRollbackFailingWithTheSameThrowableRethrowsItUnchanged() {
        configureSqlLoggerAtWarn();
        InjectedError shared = new InjectedError();
        AtomicBoolean armed = new AtomicBoolean(false);
        PropertyChangeListener listener = evt -> {
            if (armed.compareAndSet(true, false)) {
                throw shared;
            }
        };
        context().addPropertyChangeListener(listener);
        try {
            try {
                SqlCaptureProbe.open(5, (probe, action) -> {
                    armed.set(true);
                    throw shared;
                });
                fail("open should have failed");
            } catch (InjectedError thrown) {
                assertTrue(thrown == shared);
            }
            context().removePropertyChangeListener(listener);
            context().updateLoggers();
            assertEquals(0, SqlCaptureProbe.activeProbeCount());
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());
        } finally {
            context().removePropertyChangeListener(listener);
            removeSqlLoggerConfig();
        }
    }

    // ---------------------------------------------------------------- shape the cleaner relies on

    /**
     * Follows every instance field reachable from {@code root}, lambdas and method references
     * included, and reports a path to {@code target} or {@code null}. Fields of JDK classes this
     * test may not open are skipped.
     */
    private static String pathTo(Object root, Object target) throws IllegalAccessException {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> todo = new ArrayDeque<>();
        todo.push(new Object[] {root, root.getClass().getSimpleName()});
        while (!todo.isEmpty()) {
            Object[] current = todo.pop();
            Object o = current[0];
            String path = (String) current[1];
            if (o == target) {
                return path;
            }
            if (o == null || !seen.add(o)) {
                continue;
            }
            Class<?> type = o.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (Object element : (Object[]) o) {
                        todo.push(new Object[] {element, path + "[]"});
                    }
                }
                continue;
            }
            for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field field : k.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                    } catch (RuntimeException closedModule) {
                        continue;
                    }
                    todo.push(new Object[] {field.get(o), path + "." + field.getName()});
                }
            }
        }
        return null;
    }

    /**
     * The same property on live objects: no path from the appender or the release action back to
     * the probe, whatever form it takes (an outer instance, a lambda, a method reference).
     */
    @Test
    public void theProbeIsNotReachableFromItsAppenderOrReleaseAction() throws Exception {
        Runnable[] action = new Runnable[1];
        SqlCaptureProbe probe = SqlCaptureProbe.open(5, (owner, release) -> {
            action[0] = release;
            return release::run;
        });
        try {
            Field appenderField = SqlCaptureProbe.class.getDeclaredField("appender");
            appenderField.setAccessible(true);
            Object appender = appenderField.get(probe);
            assertNull("appender reaches the probe", pathTo(appender, probe));
            assertNull("release action reaches the probe", pathTo(action[0], probe));
        } finally {
            probe.close();
        }
    }

    /**
     * The logger configuration holds each probe's appender, and the cleaner holds each release
     * action. If either refers back to the probe, an unclosed probe can never be collected and its
     * release never runs. This must keep holding after any merge that touches these classes.
     */
    @Test
    public void theAppenderAndReleaseActionHoldNoReferenceToTheProbe() throws Exception {
        for (String simpleName : new String[] {"CaptureAppender", "Release", "RaisedLogger"}) {
            Class<?> type = Class.forName(SqlCaptureProbe.class.getName() + "$" + simpleName);
            assertTrue(simpleName + " must be a static nested class", Modifier.isStatic(type.getModifiers()));
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertFalse(
                        simpleName + "." + field.getName() + " refers back to the probe",
                        SqlCaptureProbe.class.isAssignableFrom(field.getType())
                                || field.getType() == Object.class
                                || field.isSynthetic());
            }
        }
    }

    // ---------------------------------------------------------------- whose statements (saiku#2328)

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

    /** saiku#2442: an Error from a scope is also dropped, not fatal to the logging pipeline. */
    @Test
    public void aScopeThatThrowsAnErrorIsTreatedAsNotMine() {
        SqlCaptureProbe probe = SqlCaptureProbe.open(() -> {
            throw new InjectedError();
        });
        try {
            MONDRIAN_SQL.debug("8: executing sql [select 1 from ERROR_SCOPE]");
            assertFalse(probe.captured());
        } finally {
            probe.close();
        }
    }

    // ---------------------------------------------------------------- a probe dropped without close

    /** Opens a probe the caller never keeps a reference to, so it can be garbage collected. */
    private static void openAndDropProbe() {
        SqlCaptureProbe.open();
    }

    /**
     * saiku#2442: the field-walking tests pin that nothing reachable holds the probe, but only a
     * real drop proves the release actually runs — a probe dropped without {@code close()} must be
     * released when it is collected, putting the raised levels back.
     */
    @Test
    public void aProbeDroppedWithoutCloseIsReleasedAndItsLevelIsRestored() throws Exception {
        configureSqlLoggerAtWarn();
        try {
            int beforeProbes = SqlCaptureProbe.activeProbeCount();
            openAndDropProbe();
            assertEquals(beforeProbes + 1, SqlCaptureProbe.activeProbeCount());

            // Wait for the cleaner: the release runs after the object becomes unreachable, so give
            // the GC and the cleaner thread a bounded number of chances before declaring failure.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (SqlCaptureProbe.activeProbeCount() > beforeProbes && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(50);
            }

            assertEquals(
                    "a dropped probe must be released without close()",
                    beforeProbes,
                    SqlCaptureProbe.activeProbeCount());
            assertEquals(Level.WARN, sqlLoggerLevel());
            assertFalse(MONDRIAN_SQL.isDebugEnabled());
            for (String name : SqlCaptureProbe.SQL_LOGGERS) {
                assertTrue(configuration().getLoggerConfig(name).getAppenders().values().stream()
                        .noneMatch(a -> a.getName().startsWith("saiku-sql-capture")));
            }
        } finally {
            removeSqlLoggerConfig();
        }
    }

    // ---------------------------------------------------------------- the registrar seam's scope

    /**
     * saiku#2442: the package-private registrar seam substitutes only the release registration —
     * its default scope must still claim the opening thread alone, so a statement logged on
     * another thread is not captured through it.
     */
    @Test
    public void theRegistrarSeamKeepsTheDefaultThreadScope() throws Exception {
        SqlCaptureProbe probe = SqlCaptureProbe.open(5, (owner, action) -> action::run);
        try {
            MONDRIAN_SQL.debug("9: executing sql [select 'seam_thread' from T]");
            Thread stranger = new Thread(() -> MONDRIAN_SQL.debug("10: executing sql [select 'seam_stranger' from T]"));
            stranger.start();
            stranger.join();
            assertEquals(List.of("select 'seam_thread' from T"), probe.statements());
        } finally {
            probe.close();
        }
    }

    // ----------------------------------------------- saiku#2423: existing appenders are fenced

    /** Records every message it is given, standing in for a file or console appender. */
    private static final class RecordingAppender extends org.apache.logging.log4j.core.appender.AbstractAppender {
        final List<String> messages = Collections.synchronizedList(new java.util.ArrayList<>());

        RecordingAppender(String name) {
            super(name, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }

    /**
     * Opening a probe lowers the shared Mondrian loggers to DEBUG, which must not make another
     * execution's SQL appear in the appenders that were already attached (file, console): they keep
     * seeing exactly what the configured WARN level let through, before, during and after.
     */
    @Test
    public void existingAppendersDoNotSeeDebugSqlWhileAProbeIsOpen() throws Exception {
        configureSqlLoggerAtWarn();
        RecordingAppender onSqlLogger = new RecordingAppender("test-on-sql-logger");
        RecordingAppender onRoot = new RecordingAppender("test-on-root");
        onSqlLogger.start();
        onRoot.start();
        configuration().getLoggerConfig("mondrian.sql").addAppender(onSqlLogger, null, null);
        configuration().getRootLogger().addAppender(onRoot, null, null);
        context().updateLoggers();
        try {
            SqlCaptureProbe probe = SqlCaptureProbe.open();
            try {
                MONDRIAN_SQL.debug("1: executing sql [select 'own_statement' from MINE]");
                Thread stranger = new Thread(() -> {
                    MONDRIAN_SQL.debug("2: executing sql [select 'strangers_filter' from THEIRS]");
                    MONDRIAN_SQL.warn("a real warning the configuration always logged");
                });
                stranger.start();
                stranger.join();
                assertEquals(List.of("select 'own_statement' from MINE"), probe.statements());
            } finally {
                probe.close();
            }
            for (RecordingAppender appender : List.of(onSqlLogger, onRoot)) {
                assertEquals(
                        appender.getName(),
                        List.of("a real warning the configuration always logged"),
                        appender.messages);
            }
            MONDRIAN_SQL.debug("3: executing sql [select 'after_close' from T]");
            assertEquals(1, onSqlLogger.messages.size());
        } finally {
            configuration().getLoggerConfig("mondrian.sql").removeAppender(onSqlLogger.getName());
            configuration().getRootLogger().removeAppender(onRoot.getName());
            removeSqlLoggerConfig();
        }
    }

    /** The fence is a filter the probe adds, so it must be gone, not just inert, after the last close. */
    @Test
    public void theFenceIsRemovedFromExistingAppendersOnClose() {
        configureSqlLoggerAtWarn();
        RecordingAppender existing = new RecordingAppender("test-fence-removal");
        existing.start();
        configuration().getLoggerConfig("mondrian.sql").addAppender(existing, null, null);
        context().updateLoggers();
        try {
            assertFalse(existing.hasFilter());
            SqlCaptureProbe probe = SqlCaptureProbe.open();
            assertTrue(existing.hasFilter());
            probe.close();
            assertFalse(existing.hasFilter());
        } finally {
            configuration().getLoggerConfig("mondrian.sql").removeAppender(existing.getName());
            removeSqlLoggerConfig();
        }
    }
}
