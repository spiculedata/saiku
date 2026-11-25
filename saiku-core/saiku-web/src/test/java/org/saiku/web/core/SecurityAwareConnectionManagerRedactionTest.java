/*
 * Copyright 2026 Spicule Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.saiku.web.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * saiku#2547: a failed connection for a datasource carrying a password must not log the password
 * value at any level.
 *
 * <p>{@link SecurityAwareConnectionManager#connect(String, SaikuDatasource)} logs the exception
 * the connection factory threw. For a passthrough datasource that exception's message carries the
 * connect string — {@code JdbcUser=} and the signed-in user's <b>plaintext password</b> — and for
 * a stored datasource the warehouse password. Anyone who can read {@code saiku.log} (log shippers,
 * {@code GET /rest/saiku/admin/log/{logname}}) used to see it.
 *
 * <p>The test stands up a datasource whose (test-supplied) JDBC driver fails while opening and
 * puts the connect string in its failure message — exactly the shape a real warehouse driver or
 * Mondrian's wrapper produces — captures the log4j2 appender output of the real {@code connect()}
 * catch block, and asserts the output does not contain the secret while the datasource name and
 * the root cause survive.
 */
public class SecurityAwareConnectionManagerRedactionTest {

    private static final String DS = "foodmart";
    private static final String URL = "jdbc:postgresql://warehouse.example/x";
    private static final ThreadLocal<String> DRIVER_PASSWORD = new ThreadLocal<>();

    private CapturingAppender appender;

    @Before
    public void captureLogs() {
        appender = CapturingAppender.on(SecurityAwareConnectionManager.class.getName());
    }

    @After
    public void releaseLogs() {
        DRIVER_PASSWORD.remove();
        CapturingAppender.off(appender, SecurityAwareConnectionManager.class.getName());
    }

    @Test
    public void failedConnectionLogsNoPasswordValue() {
        TestManager mgr = new TestManager();
        for (String password : new String[] {
            "the-users-login-password", "correct horse battery staple", "ab;cd", "a&b", "p'a", "\"dq;x\""
        }) {
            DRIVER_PASSWORD.set(password);
            appender.clear();

            // The real connect() runs, the factory throws, and the catch block logs.
            ISaikuConnection con = mgr.fetch(datasource(password));

            assertNull("a failed datasource yields no connection", con);

            String logged = appender.rendered();
            assertFalse("the secret must never appear in the log:\n" + logged, logged.contains(password));
            // saiku#2547 C3: the log still names the datasource and the root cause.
            assertTrue("the log must name the datasource:\n" + logged, logged.contains("Error connecting: " + DS));
            assertTrue(
                    "the log must carry the root cause:\n" + logged,
                    logged.contains("Error while creating SQL connection"));
            // Non-secret fields and the suppressed exception survive the formatted stack trace.
            assertTrue("the jdbc url must survive:\n" + logged, logged.contains(URL));
            assertTrue("the login name must survive:\n" + logged, logged.contains("JdbcUser=bob"));
            assertTrue("the masked value must appear:\n" + logged, logged.contains("JdbcPassword=***"));
            assertTrue("suppressed failures must be rendered:\n" + logged, logged.contains("Suppressed:"));
        }
    }

    /** The datasource under test: stored warehouse credentials, a test driver that fails. */
    private static SaikuDatasource datasource(String password) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.DRIVER_KEY, SecretThrowingDriver.class.getName());
        props.setProperty(ISaikuConnection.URL_KEY, URL);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "bob");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, password);
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "false");
        return new SaikuDatasource(DS, SaikuDatasource.Type.OLAP, props);
    }

    /** A subclass is the legal call site for the protected {@code getInternalConnection}. */
    private static final class TestManager extends SecurityAwareConnectionManager {
        ISaikuConnection fetch(SaikuDatasource datasource) {
            return getInternalConnection(DS, datasource);
        }
    }

    /**
     * A stand-in warehouse driver: fails while opening and puts the connect string in its message —
     * the shape the issue shows. Registered with {@link DriverManager} from its static
     * initialiser, which {@code JdbcUrlPolicy.loadDriverClass} triggers before the open.
     */
    public static class SecretThrowingDriver implements Driver {
        static {
            try {
                DriverManager.registerDriver(new SecretThrowingDriver());
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (url.startsWith("jdbc:postgresql://")) {
                String password = DRIVER_PASSWORD.get();
                SQLException error = new SQLException("Error while creating SQL connection: Jdbc=" + url
                        + "; JdbcUser=bob; JdbcPassword=" + password + "; Catalog=foodmart");
                error.addSuppressed(new SQLException("suppressed JdbcPassword=" + password));
                throw error;
            }
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:postgresql://");
        }

        @Override
        public int getMajorVersion() {
            return 0;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("org.saiku.web.core.SecretThrowingDriver");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }
    }

    /** Captures the log4j2 events of one logger so the tests can assert what was actually logged. */
    private static final class CapturingAppender extends AbstractAppender {
        private static final PatternLayout LOG_LAYOUT =
                PatternLayout.newBuilder().withPattern("%m%n%throwable").build();
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("capture-2547", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        static CapturingAppender on(String loggerName) {
            LoggerContext ctx =
                    (LoggerContext) LogManager.getContext(SecurityAwareConnectionManager.class.getClassLoader(), false);
            Configuration config = ctx.getConfiguration();
            CapturingAppender appender = new CapturingAppender();
            appender.start();
            config.addAppender(appender);
            // additive=false keeps the captured events out of the console appenders.
            LoggerConfig loggerConfig = new LoggerConfig(loggerName, Level.DEBUG, false);
            loggerConfig.addAppender(appender, Level.DEBUG, null);
            config.addLogger(loggerName, loggerConfig);
            ctx.updateLoggers();
            return appender;
        }

        static void off(CapturingAppender appender, String loggerName) {
            LoggerContext ctx =
                    (LoggerContext) LogManager.getContext(SecurityAwareConnectionManager.class.getClassLoader(), false);
            Configuration config = ctx.getConfiguration();
            config.removeLogger(loggerName);
            ctx.updateLoggers();
            appender.stop();
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        void clear() {
            events.clear();
        }

        /** Render the same message and throwable stack trace that log4j writes to a file. */
        String rendered() {
            StringBuilder out = new StringBuilder();
            for (LogEvent event : events) {
                out.append(LOG_LAYOUT.toSerializable(event));
            }
            return out.toString();
        }
    }
}
