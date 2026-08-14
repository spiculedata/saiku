/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;

/**
 * saiku#1809 PR4 — backward-compat coverage for {@link JdbcUserDAO.UserMapper#mapRow} reading the
 * {@code USERS.ENABLED} column. The mapper maps a disabled row to {@code isEnabled()==false} and
 * fails SAFE to enabled (legacy behaviour) when the value is SQL NULL or the column is absent from the
 * SELECT — so pre-PR4 queries / rows are never wrongly reported disabled.
 *
 * <p>Drives the REAL production mapper against controlled in-memory H2 ResultSets (no mocking
 * framework — Mockito is intentionally not on this module's classpath; mirrors {@link
 * DatabaseUpdateForEncryptionTest}'s real-H2 approach).
 */
public class JdbcUserDAOUserMapperTest {

    private Connection c;

    @Before
    public void setUp() throws Exception {
        c = DriverManager.getConnection(
                "jdbc:h2:mem:usermapper_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    @After
    public void tearDown() throws Exception {
        if (c != null) {
            c.close();
        }
    }

    /** Map the single row produced by {@code sql} through the real production {@link JdbcUserDAO.UserMapper}. */
    private SaikuUser mapSingleRow(String sql) throws Exception {
        JdbcUserDAO.UserMapper mapper = new JdbcUserDAO.UserMapper();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            assertTrue("test fixture must produce a row", rs.next());
            return (SaikuUser) mapper.mapRow(rs, 0);
        }
    }

    @Test
    public void enabledZero_mapsToDisabled() throws Exception {
        // A row whose ENABLED = 0 must surface as disabled — this is the whole point of PR4's gate.
        SaikuUser u = mapSingleRow("SELECT 7 AS user_id, 'alice' AS username, 'a@x.com' AS email, "
                + "'pw' AS password, 0 AS enabled, 'ROLE_USER' AS ROLES");
        assertEquals("alice", u.getUsername());
        assertFalse("ENABLED = 0 must map to isEnabled()==false", u.isEnabled());
    }

    @Test
    public void enabledOne_mapsToEnabled() throws Exception {
        SaikuUser u = mapSingleRow("SELECT 1 AS user_id, 'bob' AS username, 'b@x.com' AS email, "
                + "'pw' AS password, 1 AS enabled, 'ROLE_USER' AS ROLES");
        assertTrue("ENABLED = 1 must map to isEnabled()==true", u.isEnabled());
    }

    @Test
    public void enabledSqlNull_failsSafeToEnabled() throws Exception {
        // Column present but SQL NULL (wasNull()) — legacy fallback: treat as enabled, never disabled.
        SaikuUser u = mapSingleRow("SELECT 2 AS user_id, 'carol' AS username, 'c@x.com' AS email, "
                + "'pw' AS password, CAST(NULL AS INT) AS enabled, 'ROLE_USER' AS ROLES");
        assertTrue("a SQL-NULL ENABLED must fail safe to enabled (legacy behaviour)", u.isEnabled());
    }

    @Test
    public void enabledColumnMissing_failsSafeToEnabled() throws Exception {
        // The SELECT omits ENABLED entirely (a pre-PR4 / older query shape). rs.getInt("enabled")
        // throws SQLException; the mapper must swallow it and default to enabled — never disabled.
        SaikuUser u = mapSingleRow("SELECT 3 AS user_id, 'dave' AS username, 'd@x.com' AS email, "
                + "'pw' AS password, 'ROLE_USER' AS ROLES");
        assertEquals("dave", u.getUsername());
        assertTrue("a missing ENABLED column must fail safe to enabled (legacy fallback)", u.isEnabled());
    }
}
