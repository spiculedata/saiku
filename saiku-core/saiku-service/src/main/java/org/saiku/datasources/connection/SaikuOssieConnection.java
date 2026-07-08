/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import static org.saiku.datasources.connection.encrypt.CryptoUtil.decrypt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saiku connection wrapper for an Apache Ossie semantic-model datasource. Wraps a plain JDBC
 * connection acquired from Calcite's {@code jdbc:calcite:} driver, wired to an
 * {@link org.saiku.sql.adapter.OssieSchemaFactory} that reads the Ossie YAML declared in the
 * datasource properties. Every SQL query dispatched by {@code OssieQueryService} runs through
 * this connection.
 *
 * <p>Properties consumed:
 *
 * <ul>
 *   <li>{@link ISaikuConnection#OSSIE_YAML_KEY} (required) — filesystem path to the Ossie YAML
 *   <li>{@link ISaikuConnection#URL_KEY} (required) — the warehouse JDBC URL (Postgres/H2/etc)
 *   <li>{@link ISaikuConnection#USERNAME_KEY} / {@link ISaikuConnection#PASSWORD_KEY} — warehouse
 *       credentials
 *   <li>{@link ISaikuConnection#OSSIE_MODEL_KEY} (optional) — pick a specific
 *       {@code semantic_model[]} entry when the YAML carries multiple; defaults to the first
 * </ul>
 *
 * <p>The Calcite connect URL is assembled locally rather than delegated to
 * {@code OssieSqlServer.buildCalciteConnectString} because {@code saiku-sql} depends on
 * {@code saiku-service} and pulling the other direction would create a Maven cycle. The
 * string-assembly logic is small and the two callers must be kept structurally equivalent —
 * changes to either should be mirrored (the shape is exercised by
 * {@code PharmaEndToEndIT.openCalcite}). A future refactor can move both to a shared helper.
 */
public class SaikuOssieConnection implements ISaikuConnection {

    private static final Logger log = LoggerFactory.getLogger(SaikuOssieConnection.class);

    private String name;
    private Properties properties;
    private Connection calciteConnection;
    private boolean initialized = false;

    public SaikuOssieConnection(String name, Properties props) {
        this.name = name;
        this.properties = props;
    }

    public SaikuOssieConnection(Properties props) {
        this.properties = props;
        this.name = props.getProperty(NAME_KEY);
    }

    @Override
    public boolean connect() throws Exception {
        return connect(properties);
    }

    @Override
    public boolean connect(Properties props) throws Exception {
        // Skip in safemode to match SaikuOlapConnection's boot semantics — an operator running
        // the launcher in safemode expects zero live connections.
        try {
            String safemode = System.getProperty("saiku.safemode");
            if (safemode != null && safemode.equals("true")) {
                log.debug("Not starting Ossie connection {}, Saiku in safe mode", name);
                return false;
            }
        } catch (Exception ignored) {
            // safemode property lookup failure is not a connection failure.
        }
        if (props.containsKey("enabled") && "false".equals(props.getProperty("enabled"))) {
            log.info("Ossie datasource '{}' marked as disabled.", name);
            return false;
        }

        this.properties = props;
        String ossieYaml = Objects.requireNonNull(
                props.getProperty(OSSIE_YAML_KEY),
                "SaikuOssieConnection: '" + OSSIE_YAML_KEY + "' property is required");
        String warehouseUrl = Objects.requireNonNull(
                props.getProperty(URL_KEY), "SaikuOssieConnection: '" + URL_KEY + "' (warehouse JDBC URL) is required");
        String user = props.getProperty(USERNAME_KEY);
        String password = props.getProperty(PASSWORD_KEY);
        String passwordEnc = props.getProperty(PASSWORD_ENCRYPT_KEY);
        if (passwordEnc != null && passwordEnc.equals("true") && password != null) {
            password = decrypt(password);
        }
        // Default the Ossie model name to the datasource name when the operator hasn't picked
        // one — one-model-per-datasource is the MVP shape; explicit picking is for future
        // multi-model YAML files.
        String modelName = props.getProperty(OSSIE_MODEL_KEY, name);

        String calciteUrl = buildCalciteConnectString(Path.of(ossieYaml), modelName, warehouseUrl, user, password);
        // The Calcite driver reads the model + operand from the URL — no user/pass params
        // needed here since we baked the warehouse creds into the operand.
        this.calciteConnection = DriverManager.getConnection(calciteUrl);
        this.initialized = true;
        log.info("Ossie connection '{}' opened against Ossie YAML {}", name, ossieYaml);
        return true;
    }

    @Override
    public boolean clearCache() {
        // No cache to clear — Calcite plans queries per-execution; the underlying JDBC driver
        // owns any prepared-statement cache.
        return true;
    }

    @Override
    public String getDatasourceType() {
        return OSSIE_DATASOURCE;
    }

    @Override
    public boolean initialized() {
        return initialized;
    }

    @Override
    public Connection getConnection() {
        try {
            if (calciteConnection == null || calciteConnection.isClosed()) {
                connect();
            }
        } catch (Exception e) {
            log.warn("Failed to re-open Ossie connection '{}': {}", name, e.getMessage());
        }
        return calciteConnection;
    }

    @Override
    public void setProperties(Properties props) {
        this.properties = props;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Properties getProperties() {
        return properties;
    }

    /**
     * Build a Calcite JDBC connect string pointing at a temp-file model.json that instantiates
     * {@code OssieSchemaFactory} with the supplied warehouse-connection operands. Structurally
     * equivalent to {@code OssieSqlServer.buildCalciteConnectString} in the saiku-sql module;
     * see the class javadoc for why the two aren't sharing code today.
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
            Path modelPath = Files.createTempFile("saiku-ossie-model-", ".json");
            modelPath.toFile().deleteOnExit();
            Files.writeString(modelPath, modelJson);
            return "jdbc:calcite:model=" + modelPath + ";caseSensitive=false";
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage Calcite model.json", e);
        }
    }
}
