/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.saiku.sql.server.OssieSqlServer;
import org.saiku.sql.server.SqlServerCredentials;
import org.saiku.sql.server.pgwire.PgWireServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code saiku sql-serve} — start a network SQL endpoint over the Ossie semantic model.
 *
 * <p>Runs an Apache Avatica HTTP server that exposes the same SQL surface used by {@code
 * saiku ossie-export}'s output. Any Avatica-compatible client (JDBC via {@code
 * avatica-server}'s JDBC driver, Python {@code phoenixdb}, Go {@code avatica}) can then connect
 * over the wire and execute SQL against the semantic model — SELECT, WHERE, GROUP BY, explicit
 * JOIN, metric SELECT, relationship views, and auto-injected joins all work.
 *
 * <p>Example — start the server against a locally-exported Pharma schema:
 *
 * <pre>{@code
 * saiku sql-serve --ossie pharma.ossie.yaml \
 *                 --schema PHARMA \
 *                 --jdbc-url jdbc:postgresql://warehouse:5432/prod \
 *                 --jdbc-user app --jdbc-password-file /run/secrets/warehouse \
 *                 --port 8765
 * }</pre>
 *
 * <p>Clients then connect via {@code jdbc:avatica:remote:url=http://localhost:8765} with
 * {@code serialization=protobuf}.
 *
 * <p>Security (saiku#1910): both endpoints hand out SQL against the warehouse as the configured
 * service user, so they bind to {@code 127.0.0.1} by default. {@code --bind} to anything that
 * isn't loopback is refused unless {@code --auth-user} + a password (file or {@value
 * #AUTH_PASSWORD_ENV}) are set — Avatica then requires HTTP basic auth and PG-wire requires
 * SCRAM-SHA-256. Neither endpoint speaks TLS, so a network-facing deployment should also sit
 * behind a TLS-terminating proxy. {@code --allow-unauthenticated-remote} overrides the refusal
 * for isolated networks and prints a warning banner.
 */
@Command(
        name = "sql-serve",
        description = "Start a network Avatica SQL endpoint over an Ossie semantic model.",
        mixinStandardHelpOptions = true)
public class SqlServeCommand implements Callable<Integer> {

    @Option(
            names = {"-o", "--ossie"},
            description = "Path to the Ossie YAML file describing the semantic model.",
            required = true)
    Path ossieYaml;

    @Option(
            names = {"-s", "--schema"},
            description = "Schema name (must match a semantic_model entry in the Ossie YAML). Defaults to the first.",
            defaultValue = "PHARMA")
    String schemaName;

    @Option(
            names = "--jdbc-url",
            description = "Warehouse JDBC URL. Without it, tables register but queries return zero rows.")
    String jdbcUrl;

    @Option(names = "--jdbc-user", description = "Warehouse JDBC user.")
    String jdbcUser;

    @Option(
            names = "--jdbc-password",
            description = "Warehouse JDBC password. Deprecated: visible in the process list. Prefer "
                    + "--jdbc-password-file or the " + JDBC_PASSWORD_ENV + " environment variable.")
    String jdbcPassword;

    @Option(
            names = "--jdbc-password-file",
            description = "File holding the warehouse JDBC password (trailing newline ignored).")
    Path jdbcPasswordFile;

    @Option(
            names = "--bind",
            description = "Address both endpoints listen on. Default: ${DEFAULT-VALUE} (this machine only). "
                    + "A non-loopback address requires --auth-user.",
            defaultValue = SqlServerCredentials.DEFAULT_BIND_HOST)
    String bindHost;

    @Option(
            names = "--auth-user",
            description = "Username SQL clients must authenticate as (HTTP basic on Avatica, SCRAM-SHA-256 "
                    + "on PG-wire). Requires --auth-password-file or " + AUTH_PASSWORD_ENV + ".")
    String authUser;

    @Option(
            names = "--auth-password-file",
            description = "File holding the password for --auth-user (trailing newline ignored).")
    Path authPasswordFile;

    @Option(
            names = "--allow-unauthenticated-remote",
            description = "Permit a non-loopback --bind without --auth-user. Anyone who can reach the port "
                    + "can then run SQL against the warehouse. Only for isolated networks.")
    boolean allowUnauthenticatedRemote;

    @Option(
            names = {"-p", "--port"},
            description =
                    "Avatica HTTP port. Set to 0 to disable the Avatica endpoint; use --pg-port for pg-wire only.",
            defaultValue = "8765")
    int port;

    @Option(
            names = "--pg-port",
            description = "Postgres wire port. When set, opens a native PG-wire endpoint alongside Avatica so "
                    + "psql / pgAdmin / Tableau / DBeaver / dbt-postgres can connect. Set to 0 to disable.",
            defaultValue = "0")
    int pgPort;

    static final String JDBC_PASSWORD_ENV = "SAIKU_SQL_JDBC_PASSWORD";
    static final String AUTH_PASSWORD_ENV = "SAIKU_SQL_AUTH_PASSWORD";

    /** Environment lookup; a field so tests can substitute a fixed map. */
    Map<String, String> env = System.getenv();

    PrintStream err = System.err;

    /** Exit code for a configuration the command refuses to start with. */
    static final int EXIT_CONFIG = 2;

    @Override
    public Integer call() throws Exception {
        SqlServerCredentials credentials;
        String warehousePassword;
        boolean exposed;
        try {
            warehousePassword = resolveJdbcPassword();
            credentials = resolveCredentials();
            exposed = !SqlServerCredentials.isLoopback(bindHost);
        } catch (IllegalArgumentException | IOException e) {
            err.println("sql-serve: " + e.getMessage());
            return EXIT_CONFIG;
        }
        if (exposed && credentials == null) {
            if (!allowUnauthenticatedRemote) {
                err.println("sql-serve: refusing to listen on " + bindHost + " without authentication. Set "
                        + "--auth-user with --auth-password-file (or " + AUTH_PASSWORD_ENV + "), bind to "
                        + "127.0.0.1, or pass --allow-unauthenticated-remote on an isolated network.");
                return EXIT_CONFIG;
            }
            printExposedBanner();
        }

        // The Avatica server owns the Calcite connect string wiring — reuse it for both
        // endpoints so the two servers dispatch queries to the same JdbcMeta backend.
        String calciteConnectString =
                OssieSqlServer.buildCalciteConnectString(ossieYaml, schemaName, jdbcUrl, jdbcUser, warehousePassword);

        OssieSqlServer avatica = null;
        PgWireServer pgWire = null;
        try {
            if (port > 0) {
                avatica = new OssieSqlServer(
                        bindHost, port, credentials, ossieYaml, schemaName, jdbcUrl, jdbcUser, warehousePassword);
                System.out.println("sql-serve: Avatica endpoint listening on " + avatica.getUrl());
                System.out.println("sql-serve:   client → jdbc:avatica:remote:url=" + avatica.getUrl()
                        + ";serialization=protobuf"
                        + (credentials == null
                                ? ""
                                : ";authentication=BASIC;avatica_user=" + credentials.getUsername()
                                        + ";avatica_password=<password>"));
            }
            if (pgPort > 0) {
                pgWire = new PgWireServer(bindHost, pgPort, calciteConnectString, credentials);
                int actual = pgWire.getPort();
                System.out.println("sql-serve: Postgres wire endpoint listening on " + bindHost + ":" + actual
                        + (credentials == null ? " (no authentication)" : " (SCRAM-SHA-256)"));
                System.out.println("sql-serve:   client → jdbc:postgresql://localhost:" + actual
                        + "/saiku?sslmode=disable&preferQueryMode=simple");
                System.out.println("sql-serve:   psql  → PGSSLMODE=disable psql -h localhost -p " + actual + " saiku");
            }
            if (avatica == null && pgWire == null) {
                System.err.println("sql-serve: both endpoints disabled (--port 0 --pg-port 0). Nothing to do.");
                return 1;
            }
            System.out.println("sql-serve: Ctrl+C to stop");
            // Block forever. Both servers run in their own threads; the main thread just needs
            // to stay alive until interrupted so the finally clause tears them down cleanly.
            Thread.currentThread().join();
            return 0;
        } finally {
            if (pgWire != null) pgWire.close();
            if (avatica != null) avatica.close();
        }
    }

    /**
     * Warehouse password, from (in order) {@code --jdbc-password-file}, {@value #JDBC_PASSWORD_ENV},
     * or the deprecated {@code --jdbc-password}. Null when none is given.
     */
    String resolveJdbcPassword() throws IOException {
        if (jdbcPasswordFile != null) return readSecret(jdbcPasswordFile);
        String fromEnv = env.get(JDBC_PASSWORD_ENV);
        if (fromEnv != null) return fromEnv;
        if (jdbcPassword != null) {
            err.println("sql-serve: WARNING --jdbc-password exposes the warehouse password in the process "
                    + "list; use --jdbc-password-file or " + JDBC_PASSWORD_ENV + " instead.");
        }
        return jdbcPassword;
    }

    /** Endpoint credentials, or null when {@code --auth-user} is not set. */
    SqlServerCredentials resolveCredentials() throws IOException {
        if (authUser == null) {
            if (authPasswordFile != null) {
                throw new IllegalArgumentException("--auth-password-file requires --auth-user");
            }
            return null;
        }
        String password = authPasswordFile != null ? readSecret(authPasswordFile) : env.get(AUTH_PASSWORD_ENV);
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException(
                    "--auth-user requires a password via --auth-password-file or " + AUTH_PASSWORD_ENV);
        }
        return new SqlServerCredentials(authUser, password);
    }

    private static String readSecret(Path file) throws IOException {
        String s = Files.readString(file, StandardCharsets.UTF_8);
        // Strip only the trailing line break `echo` / editors add; other whitespace is kept.
        if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
        if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
        return s;
    }

    private void printExposedBanner() {
        String line = "*".repeat(78);
        err.println(line);
        err.println("* WARNING: sql-serve is UNAUTHENTICATED and listening on " + bindHost);
        err.println("* Anyone who can reach these ports can run SQL against the warehouse as the");
        err.println("* configured JDBC user. Use --auth-user unless this network is isolated.");
        err.println(line);
    }
}
