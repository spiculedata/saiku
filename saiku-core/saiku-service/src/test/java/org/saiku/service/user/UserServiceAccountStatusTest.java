/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;

/** saiku#2345: the H2 enabled flag and roles, as the web layer needs them to revoke access. */
public class UserServiceAccountStatusTest {

    /** Captures log4j2 events for {@code UserService} so the tests can assert what was logged. */
    private static final class CapturingAppender extends AbstractAppender {
        final List<LogEvent> events = new ArrayList<>();

        CapturingAppender() {
            super("capture-2431", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static CapturingAppender captureUserServiceLogs() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(UserService.class.getClassLoader(), false);
        Configuration config = ctx.getConfiguration();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        config.addAppender(appender);
        // additive=false keeps the captured events out of the console appenders.
        LoggerConfig loggerConfig = new LoggerConfig(UserService.class.getName(), Level.DEBUG, false);
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        config.addLogger(UserService.class.getName(), loggerConfig);
        ctx.updateLoggers();
        return appender;
    }

    private static void stopCapturing(CapturingAppender appender) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(UserService.class.getClassLoader(), false);
        Configuration config = ctx.getConfiguration();
        config.removeLogger(UserService.class.getName());
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    public void unknownUser_isUnmanaged() {
        AccountStatus status = usersOf(user("alice", true, "ROLE_USER")).accountStatus("bob");

        assertFalse(status.managed());
    }

    @Test
    public void disabledUser_isManagedAndNotEnabled() {
        AccountStatus status = usersOf(user("alice", false, "ROLE_USER")).accountStatus("alice");

        assertTrue(status.managed());
        assertFalse(status.enabled());
    }

    @Test
    public void caseVariantRows_areDeniedIfAnyIsDisabled_saiku2432() {
        AccountStatus status = usersOf(user("Alice", true, "ROLE_USER"), user("alice", false, "ROLE_USER"))
                .accountStatus("alice");

        assertTrue(status.managed());
        assertFalse("a disabled sibling row must deny the account", status.enabled());
    }

    @Test
    public void caseVariantRows_unionTheirRoles_saiku2432() {
        AccountStatus status = usersOf(user("Alice", true, "ROLE_USER"), user("alice", true, "ROLE_ADMIN"))
                .accountStatus("ALICE");

        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), status.roles());
    }

    @Test
    public void rolesComeFromTheUserRolesRow_andUsernameIsCaseInsensitive() {
        AccountStatus status =
                usersOf(user("alice", true, "ROLE_USER", "ROLE_ADMIN")).accountStatus("ALICE");

        assertTrue(status.managed());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), status.roles());
    }

    @Test
    public void rowWithoutRoles_hasEmptyRoleSet() {
        AccountStatus status = usersOf(user("alice", true)).accountStatus("alice");

        assertTrue(status.roles().isEmpty());
    }

    @Test
    public void lookupError_isUnmanagedNotAnException() {
        UserService us = new UserService() {
            @Override
            protected List<SaikuUser> usersNamed(String username) {
                throw new RuntimeException("db down");
            }
        };

        assertFalse(us.accountStatus("alice").managed());
    }

    /**
     * saiku#2431: {@code ROLE_USER,ROLE_USER} is the NORMAL shape of a console/SCIM-created row
     * ({@code JdbcUserDAO.insert} prepends {@code ROLE_USER} to a caller-supplied list that already
     * names it). The strict {@code Set.of(...)} parse threw on exactly that row and the catch turned
     * a found account into {@link AccountStatus#UNMANAGED} — silently discarding its enabled flag.
     * A found row must always be managed, whatever its role column contains.
     */
    @Test
    public void duplicateRoleEntries_rowIsStillFoundAndManaged() {
        AccountStatus status =
                usersOf(user("alice", true, "ROLE_USER", "ROLE_USER")).accountStatus("alice");

        assertTrue("a row the store found is managed, not unparseable", status.managed());
        assertTrue(status.enabled());
        assertEquals(Set.of("ROLE_USER"), status.roles());
    }

    @Test
    public void disabledRowWithDuplicateRoles_reportsDisabled_notUnmanaged() {
        AccountStatus status =
                usersOf(user("alice", false, "ROLE_USER", "ROLE_USER")).accountStatus("alice");

        assertTrue(status.managed());
        assertFalse("the found row's enabled flag drives revocation", status.enabled());
    }

    @Test
    public void disabledConsoleCreatedAdmin_reportsDisabled() {
        // JdbcUserDAO.insert with {"ROLE_USER","ROLE_ADMIN"} stores ROLE_USER,ROLE_USER,ROLE_ADMIN.
        AccountStatus status = usersOf(user("alice", false, "ROLE_USER", "ROLE_USER", "ROLE_ADMIN"))
                .accountStatus("alice");

        assertTrue(status.managed());
        assertFalse(status.enabled());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), status.roles());
    }

    @Test
    public void nullAndBlankRoleEntries_areSkipped_notFatal() {
        AccountStatus status = usersOf(user("alice", true, "ROLE_USER", null, "  ", "ROLE_ADMIN"))
                .accountStatus("alice");

        assertTrue(status.managed());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), status.roles());
    }

    /** saiku#2431: a store read failure is logged at ERROR with the exception, never dropped. */
    @Test
    public void lookupError_isLoggedAtErrorWithTheException() {
        RuntimeException boom = new RuntimeException("db down");
        UserService us = new UserService() {
            @Override
            protected List<SaikuUser> usersNamed(String username) {
                throw boom;
            }
        };
        CapturingAppender appender = captureUserServiceLogs();
        try {
            assertFalse(us.accountStatus("alice").managed());
        } finally {
            stopCapturing(appender);
        }

        assertTrue("the lookup failure must be logged", !appender.events.isEmpty());
        assertTrue(
                "the failure must be logged at ERROR (it used to be dropped, so an outage read as 'unmanaged')",
                appender.events.stream().anyMatch(e -> e.getLevel() == Level.ERROR));
        assertTrue(
                "the logged event must carry the exception",
                appender.events.stream().anyMatch(e -> e.getLevel() == Level.ERROR && e.getThrown() == boom));
    }

    @Test
    public void blankUsername_isUnmanaged() {
        assertFalse(usersOf(user("alice", true)).accountStatus("  ").managed());
        assertFalse(usersOf(user("alice", true)).accountStatus(null).managed());
    }

    private static SaikuUser user(String name, boolean enabled, String... roles) {
        SaikuUser u = new SaikuUser();
        u.setUsername(name);
        u.setEnabled(enabled);
        u.setRoles(roles.length == 0 ? null : roles);
        return u;
    }

    private static UserService usersOf(SaikuUser... users) {
        final List<SaikuUser> list = new ArrayList<>(Arrays.asList(users));
        return new UserService() {
            @Override
            protected List<SaikuUser> usersNamed(String username) {
                return list;
            }
        };
    }
}
