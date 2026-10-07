/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.apache.calcite.avatica.jdbc.JdbcMeta;
import org.apache.calcite.avatica.remote.LocalService;
import org.apache.calcite.avatica.remote.Service;
import org.apache.calcite.avatica.server.AvaticaJsonHandler;
import org.apache.calcite.avatica.server.AvaticaProtobufHandler;
import org.apache.calcite.avatica.server.HttpServer;
import org.apache.calcite.avatica.server.ServerCustomizer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

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
 * <p>The listener binds to loopback unless told otherwise. When {@link SqlServerCredentials}
 * are supplied the endpoint requires HTTP basic auth for that one user (clients connect with
 * {@code authentication=BASIC;avatica_user=...;avatica_password=...}); basic auth over plain
 * HTTP only protects a trusted network, so anything wider needs a TLS-terminating proxy in front
 * (saiku#1910).
 *
 * <p>This is the first slice of #1386. Full Postgres wire protocol is a separate follow-up —
 * Avatica speaks its own wire, not Postgres's, so a native {@code psql}/{@code libpq}-compatible
 * frontend requires a separate PG-wire adapter layered on the same {@link JdbcMeta} backend.
 */
public class OssieSqlServer implements AutoCloseable {

    /** Jetty role every authenticated sql-serve user is granted. */
    private static final String ROLE = "saiku-sql";

    private final HttpServer server;
    private final String jdbcConnectString;
    private final String bindHost;
    private final Path loginProperties;

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
    /** Package-visible for the PgWire IT which reuses the same connect model. */
    public static String buildCalciteConnectString(
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
                + "    \"factory\": \"bi.saiku.ossie.sql.internal.OssieSchemaFactory\",\n"
                + "    \"operand\": {" + operand + "}\n"
                + "  }]\n"
                + "}";
        try {
            Path modelPath = Files.createTempFile("ossie-sql-server-model-", ".json");
            modelPath.toFile().deleteOnExit();
            Files.writeString(modelPath, modelJson);
            // caseSensitive=false matches how every BI tool casts identifiers — most warehouses
            // are case-insensitive on unquoted names, and Calcite defaults to case-sensitive
            // which surprises users. Default lex (ORACLE) keeps double-quoted identifiers
            // (needed for spaced-name Ossie schemas like "Pharma Rx") while treating unquoted
            // ones case-insensitively.
            return "jdbc:calcite:model=" + modelPath + ";caseSensitive=false";
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
     *
     * <p>Binds to loopback with no authentication; see the full constructor for anything else.
     */
    public OssieSqlServer(
            int port,
            Path ossieYaml,
            String schemaName,
            String warehouseJdbcUrl,
            String warehouseUser,
            String warehousePassword)
            throws SQLException {
        this(
                SqlServerCredentials.DEFAULT_BIND_HOST,
                port,
                null,
                ossieYaml,
                schemaName,
                warehouseJdbcUrl,
                warehouseUser,
                warehousePassword);
    }

    /**
     * @param bindHost address to listen on; {@code null} means {@link
     *     SqlServerCredentials#DEFAULT_BIND_HOST}
     * @param credentials the user clients must authenticate as via HTTP basic auth, or {@code
     *     null} for no authentication
     */
    public OssieSqlServer(
            String bindHost,
            int port,
            SqlServerCredentials credentials,
            Path ossieYaml,
            String schemaName,
            String warehouseJdbcUrl,
            String warehouseUser,
            String warehousePassword)
            throws SQLException {
        this.bindHost = bindHost == null ? SqlServerCredentials.DEFAULT_BIND_HOST : bindHost;
        this.jdbcConnectString =
                buildCalciteConnectString(ossieYaml, schemaName, warehouseJdbcUrl, warehouseUser, warehousePassword);
        // JdbcMeta owns the outbound Calcite connection pool; every incoming Avatica request
        // borrows a Statement from a Connection. Auto-connects lazily on first use.
        JdbcMeta meta = new JdbcMeta(jdbcConnectString);
        Service service = new LocalService(meta);
        // Avatica's builder has no host option; a customizer runs after it creates the
        // connector and before Jetty starts, so the listener never opens on the wildcard.
        ServerCustomizer<Server> bindToHost = jetty -> {
            for (Connector connector : jetty.getConnectors()) {
                if (connector instanceof ServerConnector) ((ServerConnector) connector).setHost(this.bindHost);
            }
        };
        HttpServer.Builder<Server> builder = new HttpServer.Builder<Server>()
                .withHandler(new AvaticaProtobufHandler(service))
                .withPort(port)
                .withServerCustomizers(List.of(bindToHost), Server.class);
        if (credentials != null) {
            this.loginProperties = writeLoginProperties(credentials);
            builder.withBasicAuthentication(loginProperties.toString(), new String[] {ROLE});
        } else {
            this.loginProperties = null;
        }
        this.server = builder.build();
        this.server.start();
    }

    /**
     * Stages the Jetty {@code HashLoginService} file for basic auth. The password is stored as
     * Jetty's {@code MD5:} credential rather than in the clear, which also keeps characters such
     * as {@code ,} out of the properties syntax; the file is owner-only and deleted on close.
     */
    private static Path writeLoginProperties(SqlServerCredentials credentials) {
        try {
            Path file = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? Files.createTempFile(
                            "ossie-sql-server-users-",
                            ".properties",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile("ossie-sql-server-users-", ".properties");
            file.toFile().deleteOnExit();
            String md5 = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("MD5")
                            .digest(credentials.getPassword().getBytes(StandardCharsets.UTF_8)));
            Files.writeString(file, credentials.getUsername() + ": MD5:" + md5 + "," + ROLE + "\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage Avatica login properties", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public int getPort() {
        return server.getPort();
    }

    /**
     * Returns the Avatica remote connect URL clients use — e.g. {@code http://localhost:8765}.
     * Loopback and wildcard binds report {@code localhost}; a specific address reports itself.
     */
    public String getUrl() {
        String host = bindHost;
        try {
            InetAddress address = InetAddress.getByName(bindHost);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) host = "localhost";
            else if (host.indexOf(':') >= 0) host = "[" + host + "]";
        } catch (UnknownHostException e) {
            // keep the configured name
        }
        return "http://" + host + ":" + getPort();
    }

    /** Diagnostic hook for tests; the exact connect string is otherwise internal. */
    String getUnderlyingCalciteConnectString() {
        return jdbcConnectString;
    }

    @Override
    public void close() {
        server.stop();
        if (loginProperties != null) {
            try {
                Files.deleteIfExists(loginProperties);
            } catch (IOException ignored) {
                // deleteOnExit is the fallback.
            }
        }
    }
}
