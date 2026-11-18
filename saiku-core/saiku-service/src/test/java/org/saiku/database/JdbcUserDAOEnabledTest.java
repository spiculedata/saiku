/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import jakarta.servlet.ServletContext;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
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
        assumeTrue("database-queries.properties not reachable from the module", file != null);
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        Field f = JdbcUserDAO.class.getDeclaredField("prop");
        f.setAccessible(true);
        ((Properties) f.get(dao)).putAll(p);
    }
}
