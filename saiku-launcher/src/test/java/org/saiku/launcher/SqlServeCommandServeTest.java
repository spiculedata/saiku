/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import picocli.CommandLine;

/**
 * {@link SqlServeCommand#call()} dispatch paths that don't open a socket:
 * both-endpoints-disabled, the unauthenticated-remote banner and the
 * password-file resolution failure.
 */
public class SqlServeCommandServeTest {

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
    public void withBothEndpointsDisabledTheCommandSaysSoAndExitsOne() throws Exception {
        // port 0 + pg-port 0: nothing to serve — the command must report it
        // rather than silently exiting. The note is written to the real
        // stderr (not the redirectable err field), so capture System.err.
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            int code = parse("--ossie", "m.yaml", "--port", "0").call();
            assertEquals(1, code);
            assertTrue(captured.toString(StandardCharsets.UTF_8).contains("both endpoints disabled"));
        } finally {
            System.setErr(original);
        }
    }

    @Test
    public void anExplicitlyDisabledPgWireAloneAlsoDisablesEverything() throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            int code =
                    parse("--ossie", "m.yaml", "--port", "0", "--pg-port", "0").call();
            assertEquals(1, code);
            assertTrue(captured.toString(StandardCharsets.UTF_8).contains("both endpoints disabled"));
        } finally {
            System.setErr(original);
        }
    }

    @Test
    public void anUnauthenticatedRemoteBindPrintsTheBannerButServesNothing() throws Exception {
        // allow-unauthenticated-remote suppresses the refusal; with both
        // ports disabled the command still reports there is nothing to do.
        int code = parse("--ossie", "m.yaml", "--bind", "0.0.0.0", "--allow-unauthenticated-remote", "--port", "0")
                .call();
        assertEquals(1, code);
        assertTrue(err(), err().contains("UNAUTHENTICATED and listening on 0.0.0.0"));
    }

    @Test
    public void anUnreadableAuthPasswordFileIsAConfigError() throws Exception {
        int code = parse(
                        "--ossie",
                        "m.yaml",
                        "--auth-user",
                        "bi",
                        "--auth-password-file",
                        "/nonexistent/path/secret.txt")
                .call();
        assertEquals(SqlServeCommand.EXIT_CONFIG, code);
    }

    @Test
    public void anUnreadableJdbcPasswordFileIsAConfigError() throws Exception {
        int code = parse("--ossie", "m.yaml", "--port", "0", "--jdbc-password-file", "/nonexistent/path/pw.txt")
                .call();
        assertEquals(SqlServeCommand.EXIT_CONFIG, code);
    }
}
