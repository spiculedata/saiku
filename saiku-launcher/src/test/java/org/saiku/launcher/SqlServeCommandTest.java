/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.sql.server.SqlServerCredentials;
import picocli.CommandLine;

/** Bind/auth policy for {@code saiku sql-serve} (saiku#1910) — checked before any socket opens. */
public class SqlServeCommandTest {

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();

    private SqlServeCommand parse(String... args) {
        SqlServeCommand cmd = new SqlServeCommand();
        new CommandLine(cmd).parseArgs(args);
        cmd.env = env;
        cmd.err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        return cmd;
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void bindsToLoopbackByDefault() {
        assertEquals("127.0.0.1", parse("--ossie", "m.yaml").bindHost);
    }

    @Test
    public void refusesNonLoopbackBindWithoutAuth() throws Exception {
        SqlServeCommand cmd = parse("--ossie", "m.yaml", "--bind", "0.0.0.0");
        assertEquals(Integer.valueOf(SqlServeCommand.EXIT_CONFIG), cmd.call());
        assertTrue(err(), err().contains("refusing to listen on 0.0.0.0"));
    }

    @Test
    public void refusesAuthUserWithoutPassword() throws Exception {
        SqlServeCommand cmd = parse("--ossie", "m.yaml", "--bind", "0.0.0.0", "--auth-user", "bi");
        assertEquals(Integer.valueOf(SqlServeCommand.EXIT_CONFIG), cmd.call());
        assertTrue(err(), err().contains("--auth-user requires a password"));
    }

    @Test
    public void authPasswordFromFileStripsTrailingNewline() throws Exception {
        Path secret = Files.createTempFile("sql-serve-auth-", ".txt");
        try {
            Files.writeString(secret, "s3cret,with comma\n");
            SqlServerCredentials creds = parse(
                            "--ossie", "m.yaml", "--auth-user", "bi", "--auth-password-file", secret.toString())
                    .resolveCredentials();
            assertEquals("bi", creds.getUsername());
            assertEquals("s3cret,with comma", creds.getPassword());
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    public void authPasswordFromEnvironment() throws Exception {
        env.put(SqlServeCommand.AUTH_PASSWORD_ENV, "pw");
        assertEquals(
                "pw",
                parse("--ossie", "m.yaml", "--auth-user", "bi")
                        .resolveCredentials()
                        .getPassword());
    }

    @Test
    public void noAuthUserMeansTrustMode() throws Exception {
        assertNull(parse("--ossie", "m.yaml").resolveCredentials());
    }

    @Test
    public void jdbcPasswordPrefersFileThenEnvAndWarnsOnCliArg() throws Exception {
        Path secret = Files.createTempFile("sql-serve-jdbc-", ".txt");
        try {
            Files.writeString(secret, "from-file\n");
            env.put(SqlServeCommand.JDBC_PASSWORD_ENV, "from-env");
            assertEquals(
                    "from-file",
                    parse("--ossie", "m.yaml", "--jdbc-password-file", secret.toString(), "--jdbc-password", "x")
                            .resolveJdbcPassword());
            assertEquals(
                    "from-env",
                    parse("--ossie", "m.yaml", "--jdbc-password", "x").resolveJdbcPassword());
            assertTrue(err(), err().isEmpty());

            env.clear();
            assertEquals("x", parse("--ossie", "m.yaml", "--jdbc-password", "x").resolveJdbcPassword());
            assertTrue(err(), err().contains("WARNING --jdbc-password"));
        } finally {
            Files.deleteIfExists(secret);
        }
    }
}
