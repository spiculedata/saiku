/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import org.apache.calcite.avatica.jdbc.JdbcMeta;
import org.apache.calcite.avatica.remote.LocalService;
import org.apache.calcite.avatica.remote.Service;
import org.apache.calcite.avatica.server.AvaticaJsonHandler;
import org.apache.calcite.avatica.server.AvaticaProtobufHandler;
import org.apache.calcite.avatica.server.HttpServer;

/**
 * Network endpoint that exposes the Ossie/Calcite SQL surface over Apache Avatica's HTTP+
 * protobuf protocol. Any Avatica-compatible client — the {@code avatica-server} JDBC driver, the
 * Python {@code phoenixdb} package, the Go {@code avatica} driver — connects to the resulting
 * URL and executes SQL against a Calcite connection pre-wired to an Ossie YAML.
 *
 * <p>Under the hood: {@link HttpServer} → {@link AvaticaProtobufHandler} → {@link LocalService} →
 * {@link JdbcMeta} → {@code jdbc:calcite:...} pointed at our Ossie model. Every remote query
 * gets forwarded to a fresh Calcite prepare cycle; connection state (transactions, cursors) is
 * tracked per-remote-session by {@link JdbcMeta}.
 *
 * <p>This is the first slice of #1386. Full Postgres wire protocol is a separate follow-up —
 * Avatica speaks its own wire, not Postgres's, so a native {@code psql}/{@code libpq}-compatible
 * frontend requires a separate PG-wire adapter layered on the same {@link JdbcMeta} backend.
 */
public class OssieSqlServer implements AutoCloseable {

    private final HttpServer server;
    private final String jdbcConnectString;

    /**
     * Build a Calcite JDBC connect string pointing at an on-disk model.json that instantiates
     * {@code OssieSchemaFactory} against the supplied Ossie YAML.
     *
     * <p>We can't use {@code jdbc:calcite:model=inline:{...}} because Calcite's JDBC connect
     * string uses {@code ;} as its parameter separator — the warehouse JDBC URL nested inside
     * our operand (e.g. {@code jdbc:h2:mem:name;DB_CLOSE_DELAY=-1;MODE=PostgreSQL}) contains
     * literal semicolons that break the parser. Writing the model to a temp file and passing
     * {@code model=<path>} sidesteps the issue entirely.
     */
    static String buildCalciteConnectString(
            Path ossieYaml,
            String schemaName,
            String warehouseJdbcUrl,
            String warehouseUser,
            String warehousePassword) {
        StringBuilder operand = new StringBuilder();
        operand.append("\"ossieYaml\": \"")
                .append(Objects.requireNonNull(ossieYaml, "ossieYaml")
                        .toString()
                        .replace("\\", "\\\\"))
                .append("\"");
        if (warehouseJdbcUrl != null && !warehouseJdbcUrl.isBlank()) {
            operand.append(",\"jdbcUrl\": \"").append(warehouseJdbcUrl).append("\"");
        }
        if (warehouseUser != null) {
            operand.append(",\"jdbcUser\": \"").append(warehouseUser).append("\"");
        }
        if (warehousePassword != null) {
            operand.append(",\"jdbcPassword\": \"").append(warehousePassword).append("\"");
        }
        String modelJson = "{\n"
                + "  \"version\": \"1.0\",\n"
                + "  \"defaultSchema\": \"" + schemaName + "\",\n"
                + "  \"schemas\": [{\n"
                + "    \"name\": \"" + schemaName + "\",\n"
                + "    \"type\": \"custom\",\n"
                + "    \"factory\": \"org.saiku.sql.adapter.OssieSchemaFactory\",\n"
                + "    \"operand\": {" + operand + "}\n"
                + "  }]\n"
                + "}";
        try {
            Path modelPath = Files.createTempFile("ossie-sql-server-model-", ".json");
            modelPath.toFile().deleteOnExit();
            Files.writeString(modelPath, modelJson);
            return "jdbc:calcite:model=" + modelPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage Calcite model.json", e);
        }
    }

    /**
     * Start the server on the given port. Pass {@code 0} to have the OS assign an ephemeral
     * port; the actual port is available via {@link #getPort()} after the constructor returns.
     *
     * <p>Serialization is protobuf by default because it's the format the Avatica JDBC driver
     * uses. JSON is available on the same endpoint via a separate handler for humans debugging
     * with curl — see {@link AvaticaJsonHandler}. For this first slice we only wire protobuf.
     */
    public OssieSqlServer(
            int port,
            Path ossieYaml,
            String schemaName,
            String warehouseJdbcUrl,
            String warehouseUser,
            String warehousePassword)
            throws SQLException {
        this.jdbcConnectString =
                buildCalciteConnectString(ossieYaml, schemaName, warehouseJdbcUrl, warehouseUser, warehousePassword);
        // JdbcMeta owns the outbound Calcite connection pool; every incoming Avatica request
        // borrows a Statement from a Connection. Auto-connects lazily on first use.
        JdbcMeta meta = new JdbcMeta(jdbcConnectString);
        Service service = new LocalService(meta);
        this.server = new HttpServer.Builder<Object>()
                .withHandler(new AvaticaProtobufHandler(service))
                .withPort(port)
                .build();
        this.server.start();
    }

    public int getPort() {
        return server.getPort();
    }

    /** Returns the Avatica remote connect URL clients use — e.g. {@code http://localhost:8765}. */
    public String getUrl() {
        return "http://localhost:" + getPort();
    }

    /** Diagnostic hook for tests; the exact connect string is otherwise internal. */
    String getUnderlyingCalciteConnectString() {
        return jdbcConnectString;
    }

    @Override
    public void close() {
        server.stop();
    }
}
