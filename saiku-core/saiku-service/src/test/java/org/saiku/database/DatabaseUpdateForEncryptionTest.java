/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * saiku#1155 regression — SQL injection in the password-encryption migration.
 *
 * <p>{@code Database.updateForEncyption} string-concatenated the DB-sourced
 * username into the UPDATE, so a username with a single quote broke the
 * migration and a crafted value such as {@code x' OR '1'='1} rewrote EVERY
 * row's password with one hash (CWE-89). The fix binds the username as a
 * PreparedStatement parameter. These tests drive the REAL production helper
 * ({@link Database#encryptUserPasswords}) against in-memory H2; both fail on
 * the pre-fix code (apostrophe → SQLException; payload → cross-row clobber) and
 * pass after.
 */
public class DatabaseUpdateForEncryptionTest {

    private Connection c;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Before
    public void setUp() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:enc_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE users (username VARCHAR(45), password VARCHAR(100))");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (c != null) c.close();
    }

    private void insert(String username, String password) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(username, password) VALUES(?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, password);
            ps.executeUpdate();
        }
    }

    private String passwordOf(String username) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT password FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue("row for [" + username + "] must exist", rs.next());
                return rs.getString(1);
            }
        }
    }

    @Test
    public void apostropheUsernameMigratesEachRowToItsOwnBcryptHash() throws Exception {
        insert("o'brien", "pw_obrien"); // a single quote broke the old concatenated SQL
        insert("victim", "pw_victim");

        // Real production code path — must NOT throw on the apostrophe (pre-fix: SQLException).
        Database.encryptUserPasswords(c, encoder);

        String obrien = passwordOf("o'brien");
        String victim = passwordOf("victim");
        assertTrue("o'brien hashed to its own password", encoder.matches("pw_obrien", obrien));
        assertTrue("victim hashed to its own password", encoder.matches("pw_victim", victim));
        assertFalse("rows must not share a hash", obrien.equals(victim));
    }

    @Test
    public void injectionUsernameCannotClobberOtherRows() throws Exception {
        // victim inserted FIRST so a cross-row clobber (pre-fix) overwrites its
        // already-correct hash when the payload row is processed afterwards.
        insert("victim", "pw_victim");
        insert("x' OR '1'='1", "pw_attacker");

        Database.encryptUserPasswords(c, encoder);

        // Post-fix: victim keeps ITS OWN password; pre-fix the OR-payload UPDATE
        // matched every row and rewrote victim with the attacker's hash.
        assertTrue(
                "victim password intact (not clobbered by the OR payload)",
                encoder.matches("pw_victim", passwordOf("victim")));
        assertTrue("the payload row got its own hash", encoder.matches("pw_attacker", passwordOf("x' OR '1'='1")));
    }
}
