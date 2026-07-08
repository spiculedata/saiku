/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.saiku.sql.server.OssieSqlServer;
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
            description = "TCP port to bind. Use 0 for an OS-assigned port.",
            defaultValue = "8765")
    int port;

    @Override
    public Integer call() throws Exception {
        try (OssieSqlServer server = new OssieSqlServer(port, ossieYaml, schemaName, jdbcUrl, jdbcUser, jdbcPassword)) {
            System.out.println("sql-serve: listening on " + server.getUrl());
            System.out.println("sql-serve: connect via jdbc:avatica:remote:url=" + server.getUrl()
                    + " with serialization=protobuf");
            System.out.println("sql-serve: Ctrl+C to stop");
            // Block forever. Avatica's HttpServer runs in its own thread pool; the main thread
            // just needs to stay alive until interrupted so the try-with-resources close()
            // fires on shutdown.
            Thread.currentThread().join();
            return 0;
        }
    }
}
