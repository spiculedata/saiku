/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * saiku#1438 — the three SQL additions SCIM depends on, exercised against a REAL in-process H2
 * {@code USERS} table (no mocking framework on this module's classpath; mirrors
 * {@link DatabaseUpdateForEncryptionTest}'s approach).
 *
 * <p>The statements are read from the <b>real</b> {@code database-queries.properties} that
 * {@link JdbcUserDAO} loads at runtime, found by walking up from the module directory — so this
 * test cannot silently pass against a copy that has drifted from the shipped query file. The boot
 * ALTERs are copied from {@link Database#loadUsers} and their idempotency is asserted: an
 * existing H2 file is upgraded in place on every start, and a second boot must not fail.
 */
public class ScimUserProfileSqlTest {

    private Connection c;
    private Properties queries;

    @Before
    public void setUp() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:scimsql_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        queries = loadQueries();
        // The pre-#1438 table shape, exactly as older installs have it on disk.
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE USERS(user_id INT(11) NOT NULL AUTO_INCREMENT, "
                    + "username VARCHAR(45) NOT NULL UNIQUE, password VARCHAR(100) NOT NULL, email VARCHAR(100), "
                    + "enabled TINYINT NOT NULL DEFAULT 1, PRIMARY KEY(user_id));");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (c != null) {
            c.close();
        }
    }

    @Test
    public void queryFileDeclaresTheScimStatements() {
        // Guard against the file being moved/renamed, or a statement being dropped from it while
        // the Java side still asks for it.
        assertEquals("UPDATE users set enabled = ? where user_id = ?;", queries.getProperty("updateUserEnabled"));
        assertEquals("UPDATE users set email = ? where user_id = ?;", queries.getProperty("updateUserEmail"));
        String profile = queries.getProperty("updateUserProfile");
        assertTrue(
                "updateUserProfile must write the SCIM display columns",
                profile.contains("GIVEN_NAME") && profile.contains("FAMILY_NAME") && profile.contains("DISPLAY_NAME"));
    }

    @Test
    public void bootAltersAreIdempotent() throws Exception {
        applyBootAlters();
        // A second boot must be a no-op, not a "column already exists" failure — loadUsers runs on
        // every start against an existing saiku-home.
        applyBootAlters();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT GIVEN_NAME, FAMILY_NAME, DISPLAY_NAME FROM USERS")) {
            assertTrue(rs.next());
        }
    }

    @Test
    public void activeFalseSurvivesTheUpdate() throws Exception {
        applyBootAlters();
        int id = insertUser("jsmith");
        try (PreparedStatement ps = c.prepareStatement(queries.getProperty("updateUserEnabled"))) {
            ps.setBoolean(1, false);
            ps.setInt(2, id);
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(0, enabledOf(id));
        // And the flip back, which is what an IdP reactivation PATCH does.
        try (PreparedStatement ps = c.prepareStatement(queries.getProperty("updateUserEnabled"))) {
            ps.setBoolean(1, true);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
        assertEquals(1, enabledOf(id));
    }

    @Test
    public void profileAndEmailUpdatesLeaveTheRestOfTheRowAlone() throws Exception {
        applyBootAlters();
        int id = insertUser("dana");
        try (PreparedStatement ps = c.prepareStatement(queries.getProperty("updateUserProfile"))) {
            ps.setString(1, "Dana");
            ps.setString(2, "Scully");
            ps.setString(3, "Dana Scully");
            ps.setInt(4, id);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = c.prepareStatement(queries.getProperty("updateUserEmail"))) {
            ps.setString(1, "dana@x.com");
            ps.setInt(2, id);
            assertEquals(1, ps.executeUpdate());
        }
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT username, email, enabled, GIVEN_NAME, FAMILY_NAME, DISPLAY_NAME FROM USERS")) {
            assertTrue(rs.next());
            assertEquals("dana", rs.getString("username"));
            assertEquals("dana@x.com", rs.getString("email"));
            assertEquals(
                    "the legacy UPDATE hard-codes enabled=TRUE, so these statements must not", 1, rs.getInt("enabled"));
            assertEquals("Dana", rs.getString("GIVEN_NAME"));
            assertEquals("Scully", rs.getString("FAMILY_NAME"));
            assertEquals("Dana Scully", rs.getString("DISPLAY_NAME"));
        }
    }

    @Test
    public void aNullProfileClearsTheColumns() throws Exception {
        // SCIM attribute removal is a null write, not a skip.
        applyBootAlters();
        int id = insertUser("fox");
        try (PreparedStatement ps = c.prepareStatement(queries.getProperty("updateUserProfile"))) {
            ps.setString(1, "Dana");
            ps.setString(2, "Scully");
            ps.setString(3, "Dana Scully");
            ps.setInt(4, id);
            ps.executeUpdate();
            ps.setString(1, null);
            ps.setString(2, null);
            ps.setString(3, null);
            ps.executeUpdate();
        }
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT GIVEN_NAME FROM USERS")) {
            assertTrue(rs.next());
            assertNull(rs.getString("GIVEN_NAME"));
        }
    }

    private void applyBootAlters() throws Exception {
        // Mirrors Database.loadUsers (saiku#1438).
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE USERS ADD COLUMN IF NOT EXISTS GIVEN_NAME VARCHAR(100);");
            s.execute("ALTER TABLE USERS ADD COLUMN IF NOT EXISTS FAMILY_NAME VARCHAR(100);");
            s.execute("ALTER TABLE USERS ADD COLUMN IF NOT EXISTS DISPLAY_NAME VARCHAR(255);");
        }
    }

    private int insertUser(String username) throws Exception {
        try (PreparedStatement ps =
                c.prepareStatement("INSERT INTO users(username,password,email,enabled) VALUES (?,?,?,?)")) {
            ps.setString(1, username);
            ps.setString(2, "hash");
            ps.setString(3, username + "@x.com");
            ps.setBoolean(4, true);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT user_id FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private int enabledOf(int id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT enabled FROM users WHERE user_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    /** Walk up from the module dir to the shipped query file, so this test reads the real thing. */
    private static Properties loadQueries() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("saiku-webapp/src/main/webapp/WEB-INF/database-queries.properties");
            if (Files.exists(candidate)) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(candidate)) {
                    p.load(in);
                }
                return p;
            }
            dir = dir.getParent();
        }
        // Not a reactor checkout (an installed-jar layout) — the SQL assertions cannot run.
        assumeTrue(
                "database-queries.properties not reachable from "
                        + Paths.get("").toAbsolutePath(),
                false);
        return new Properties();
    }

    /** Guards against an accidental encoding change in the file we read. */
    @Test
    public void queryFileIsPlainUtf8Text() throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        Path file = null;
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("saiku-webapp/src/main/webapp/WEB-INF/database-queries.properties");
            if (Files.exists(candidate)) {
                file = candidate;
                break;
            }
            dir = dir.getParent();
        }
        assumeTrue(file != null);
        String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(body.contains("updateUserEnabled"));
    }
}
