/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.ServletContext;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.AccountStatus;
import org.saiku.service.user.UserService;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * saiku#2345: editing a user (roles, email) must not silently re-enable an account that SCIM or an
 * admin deactivated. The legacy UPDATE statements take {@code enabled} as a parameter and the DAO used
 * to pass TRUE unconditionally. Runs the real DAO against an in-process H2 with the shipped queries.
 */
public class JdbcUserDAOEnabledTest {

    private DriverManagerDataSource ds;
    private JdbcUserDAO dao;
    private Connection keepAlive;

    @Before
    public void setUp() throws Exception {
        String url = "jdbc:h2:mem:daoenabled_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        ds = new DriverManagerDataSource(url);
        keepAlive = ds.getConnection();
        try (Statement s = keepAlive.createStatement()) {
            s.execute("CREATE TABLE USERS(user_id INT(11) NOT NULL AUTO_INCREMENT, "
                    + "username VARCHAR(45) NOT NULL UNIQUE, password VARCHAR(100) NOT NULL, email VARCHAR(100), "
                    + "enabled TINYINT NOT NULL DEFAULT 1, GIVEN_NAME VARCHAR(100), FAMILY_NAME VARCHAR(100), "
                    + "DISPLAY_NAME VARCHAR(100), PRIMARY KEY(user_id));");
            s.execute("CREATE TABLE USER_ROLES (user_role_id INT(11) NOT NULL AUTO_INCREMENT,"
                    + "username VARCHAR(45), user_id INT(11) NOT NULL REFERENCES USERS(user_id),"
                    + "ROLE VARCHAR(45) NOT NULL, PRIMARY KEY (user_role_id));");
        }
        dao = new JdbcUserDAO();
        dao.setDataSource(ds);
        loadQueries(dao);
        setServletContext(dao);
    }

    @After
    public void tearDown() throws Exception {
        keepAlive.close();
    }

    /** saiku#2432: a failing role insert must roll back the delete, never leave the user role-less. */
    @Test
    public void roleRewrite_isAtomic_aFailedInsertKeepsTheOldRoles() {
        SaikuUser u = new SaikuUser();
        u.setUsername("atomic2432");
        u.setPassword("pw");
        u.setEmail("a@example.com");
        u.setRoles(new String[] {"ROLE_USER", "ROLE_ADMIN"});
        SaikuUser created = dao.insert(u);

        created.setRoles(new String[] {"ROLE_USER", "X".repeat(200)}); // ROLE is VARCHAR(45)
        try {
            dao.insertRole(created);
            org.junit.Assert.fail("the oversized role must be rejected");
        } catch (org.springframework.dao.DataAccessException expected) {
            // expected
        }

        assertTrue(
                "the admin role must survive the failed rewrite",
                java.util.Arrays.asList(reload(created).getRoles()).contains("ROLE_ADMIN"));
    }

    /** saiku#2432: the keyed lookup matches case-insensitively and returns only that user. */
    @Test
    public void findByUsername_isKeyedAndCaseInsensitive() {
        for (String name : new String[] {"Keyed2432", "other2432"}) {
            SaikuUser u = new SaikuUser();
            u.setUsername(name);
            u.setPassword("pw");
            u.setEmail(name + "@example.com");
            u.setRoles(new String[] {"ROLE_USER"});
            dao.insert(u);
        }

        java.util.Collection rows = dao.findByUsername("keyed2432");

        assertEquals(1, rows.size());
        assertEquals("Keyed2432", ((SaikuUser) rows.iterator().next()).getUsername());
    }

    @Test
    public void updateUser_doesNotReEnableADeactivatedAccount() {
        SaikuUser u = new SaikuUser();
        u.setUsername("leaver");
        u.setPassword("pw");
        u.setEmail("leaver@example.com");
        u.setRoles(new String[] {"ROLE_USER"});
        SaikuUser created = dao.insert(u);
        dao.updateUserEnabled(created.getId(), false);

        SaikuUser edit = dao.findByUserId(created.getId());
        edit.setEmail("changed@example.com");
        edit.setRoles(new String[] {"ROLE_USER"});
        dao.updateUser(edit, false);

        assertFalse(
                "a role/email edit must not reactivate a deactivated account",
                reload(created).isEnabled());
    }

    /**
     * saiku#2431: the exact repro from the issue. {@code JdbcUserDAO.insert} prepends
     * {@code ROLE_USER}, and both the admin console's new-user form and SCIM create send it too,
     * so the stored row reads {@code ROLE_USER,ROLE_USER}. That row must still be read as found
     * and managed — with its {@code enabled} flag intact — so the web layer's policy denies a
     * deactivated account instead of silently allowing it.
     */
    @Test
    public void disabledDuplicateRoleRow_isManagedAndDisabled_saiku2431() {
        SaikuUser u = new SaikuUser();
        u.setUsername("leaver2431");
        u.setPassword("pw");
        u.setEmail("leaver@example.com");
        u.setRoles(new String[] {"ROLE_USER"});
        SaikuUser created = dao.insert(u);
        dao.updateUserEnabled(created.getId(), false);

        // sanity: the stored row really is the duplicate shape the issue describes
        SaikuUser stored = reload(created);
        assertEquals(
                "insert must produce the duplicate ROLE_USER shape",
                new java.util.LinkedHashSet<>(java.util.Arrays.asList(stored.getRoles())),
                new java.util.LinkedHashSet<>(java.util.Arrays.asList("ROLE_USER")));
        assertEquals(2, stored.getRoles().length);

        UserService us = new UserService();
        us.setJdbcUserDAO(dao);
        AccountStatus status = us.accountStatus("leaver2431");

        assertTrue("a found row must be managed, whatever its roles contain", status.managed());
        assertFalse("the deactivated row must read as disabled, not unmanaged", status.enabled());
        assertEquals(Set.of("ROLE_USER"), status.roles());
    }

    /** Same repro for a console-created admin ({@code ROLE_ADMIN} later removed, row deactivated). */
    @Test
    public void disabledDuplicateRoleAdminRow_isManagedAndDisabled_saiku2431() {
        SaikuUser u = new SaikuUser();
        u.setUsername("admin2431");
        u.setPassword("pw");
        u.setEmail("admin@example.com");
        u.setRoles(new String[] {"ROLE_USER", "ROLE_ADMIN"});
        SaikuUser created = dao.insert(u);
        dao.updateUserEnabled(created.getId(), false);

        UserService us = new UserService();
        us.setJdbcUserDAO(dao);
        AccountStatus status = us.accountStatus("admin2431");

        assertTrue(status.managed());
        assertFalse(status.enabled());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), status.roles());
    }

    /**
     * The row the demotion writes back (SCIM {@code revokeRole} / the console's role edit) replaces
     * {@code USER_ROLES} wholesale, so a demoted console-created admin is left with a clean
     * {@code ROLE_USER} row — which must read as managed, enabled, and admin-free, so the policy
     * strips the authority the authenticator still vouches for.
     */
    @Test
    public void demotedAdminRow_reportsRemainingRoles() {
        SaikuUser u = new SaikuUser();
        u.setUsername("boss2431");
        u.setPassword("pw");
        u.setEmail("boss@example.com");
        u.setRoles(new String[] {"ROLE_USER", "ROLE_ADMIN"});
        SaikuUser created = dao.insert(u);

        SaikuUser demoted = dao.findByUserId(created.getId());
        demoted.setRoles(new String[] {"ROLE_USER"});
        dao.updateRoles(demoted);

        UserService us = new UserService();
        us.setJdbcUserDAO(dao);
        AccountStatus status = us.accountStatus("boss2431");

        assertTrue(status.managed());
        assertTrue(status.enabled());
        assertEquals(Set.of("ROLE_USER"), status.roles());
    }

    @Test
    public void updateUser_keepsAnEnabledAccountEnabled() {
        SaikuUser u = new SaikuUser();
        u.setUsername("stayer");
        u.setPassword("pw");
        u.setRoles(new String[] {"ROLE_USER"});
        SaikuUser created = dao.insert(u);

        SaikuUser edit = dao.findByUserId(created.getId());
        dao.updateUser(edit, false);

        assertTrue(reload(created).isEnabled());
    }

    private SaikuUser reload(SaikuUser created) {
        for (Object o : dao.findAllUsers()) {
            SaikuUser candidate = (SaikuUser) o;
            if (candidate.getUsername().equals(created.getUsername())) {
                return candidate;
            }
        }
        throw new AssertionError("user vanished: " + created.getUsername());
    }

    private static void setServletContext(JdbcUserDAO dao) throws Exception {
        ServletContext ctx = (ServletContext) Proxy.newProxyInstance(
                JdbcUserDAOEnabledTest.class.getClassLoader(),
                new Class<?>[] {ServletContext.class},
                (p, m, a) -> "getInitParameter".equals(m.getName()) ? "false" : null);
        Field f = JdbcUserDAO.class.getDeclaredField("servletContext");
        f.setAccessible(true);
        f.set(dao, ctx);
    }

    private static void loadQueries(JdbcUserDAO dao) throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        Path file = null;
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("saiku-webapp/src/main/resources/database-queries.properties");
            if (Files.exists(candidate)) {
                file = candidate;
                break;
            }
            dir = dir.getParent();
        }
        assertNotNull("database-queries.properties not reachable from the module", file);
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        Field f = JdbcUserDAO.class.getDeclaredField("prop");
        f.setAccessible(true);
        ((Properties) f.get(dao)).putAll(p);
    }
}
