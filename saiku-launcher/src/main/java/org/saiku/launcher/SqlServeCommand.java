/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.saiku.sql.server.OssieSqlServer;
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
 *                 --jdbc-user app --jdbc-password '***' \
 *                 --port 8765
 * }</pre>
 *
 * <p>Clients then connect via {@code jdbc:avatica:remote:url=http://localhost:8765} with
 * {@code serialization=protobuf}.
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

    @Option(names = "--jdbc-password", description = "Warehouse JDBC password.")
    String jdbcPassword;

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

    @Override
    public Integer call() throws Exception {
        // The Avatica server owns the Calcite connect string wiring — reuse it for both
        // endpoints so the two servers dispatch queries to the same JdbcMeta backend.
        String calciteConnectString =
                OssieSqlServer.buildCalciteConnectString(ossieYaml, schemaName, jdbcUrl, jdbcUser, jdbcPassword);

        OssieSqlServer avatica = null;
        PgWireServer pgWire = null;
        try {
            if (port > 0) {
                avatica = new OssieSqlServer(port, ossieYaml, schemaName, jdbcUrl, jdbcUser, jdbcPassword);
                System.out.println("sql-serve: Avatica endpoint listening on " + avatica.getUrl());
                System.out.println("sql-serve:   client → jdbc:avatica:remote:url=" + avatica.getUrl()
                        + " (serialization=protobuf)");
            }
            if (pgPort > 0) {
                pgWire = new PgWireServer(pgPort, calciteConnectString);
                int actual = pgWire.getPort();
                System.out.println("sql-serve: Postgres wire endpoint listening on port " + actual);
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
}
