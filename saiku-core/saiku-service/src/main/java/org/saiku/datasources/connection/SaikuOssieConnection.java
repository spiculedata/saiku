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
import org.saiku.service.datasource.JdbcUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saiku connection wrapper for an Apache Ossie semantic-model datasource. Wraps a plain JDBC
 * connection acquired from Calcite's {@code jdbc:calcite:} driver, wired to an
 * {@link bi.saiku.ossie.sql.internal.OssieSchemaFactory} that reads the Ossie YAML declared in the
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
        // Pick the Ossie semantic_model to expose. Precedence:
        //   1. Explicit OSSIE_MODEL_KEY property (future multi-model YAML use).
        //   2. The datasource's `schema` property — this is what the .sds
        //      writes and what the admin form binds to. It's the operator's
        //      declared intent about which semantic_model in the YAML this
        //      datasource surfaces, and it must match the SQL translator's
        //      schema-qualified refs (which use the semantic_model name).
        //   3. Fall back to the connection name for the one-model-per-datasource
        //      MVP shape where nothing is set.
        String modelName = props.getProperty(OSSIE_MODEL_KEY);
        if (modelName == null || modelName.isBlank()) {
            modelName = props.getProperty("schema");
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = name;
        }

        // Eagerly load the warehouse JDBC driver so it registers with DriverManager. In the
        // launcher fat-jar the driver lives in the war's classloader, which DriverManager's
        // ServiceLoader auto-registration doesn't reliably scan — so the DBCP2 pool inside
        // OssieSchemaFactory can otherwise fail with "Cannot create JDBC driver of class ''".
        // An explicit `driver` property wins; otherwise infer the well-known jdbc:quack driver.
        // saiku#1902: the warehouse URL is the descriptor-controlled input here. It is validated
        // BEFORE the driver class is touched and before it is baked into the Calcite model, so an
        // Ossie datasource is held to the same policy as an OLAP one.
        JdbcUrlPolicy.validate(warehouseUrl);

        String warehouseDriver = props.getProperty(DRIVER_KEY);
        if ((warehouseDriver == null || warehouseDriver.isBlank())
                && warehouseUrl != null
                && warehouseUrl.startsWith("jdbc:quack:")) {
            warehouseDriver = "com.gizmodata.quack.jdbc.sql.QuackDriver";
        }
        if (warehouseDriver != null && !warehouseDriver.isBlank()) {
            try {
                // Type-checked before initialisation: a descriptor can't name an arbitrary class
                // just to run its static initialiser.
                JdbcUrlPolicy.loadDriverClass(warehouseDriver);
            } catch (ClassNotFoundException e) {
                log.warn("Warehouse JDBC driver '{}' not found on the classpath", warehouseDriver);
            }
        }

        String calciteUrl = buildCalciteConnectString(Path.of(ossieYaml), modelName, warehouseUrl, user, password);
        // The Calcite driver reads the model + operand from the URL — no user/pass params
        // needed here since we baked the warehouse creds into the operand. This URL is assembled
        // by Saiku around a temp model file it just wrote (jdbc:calcite: is deliberately NOT a
        // user-facing scheme in JdbcUrlPolicy); the only descriptor-controlled part — the
        // warehouse URL — was validated above and is JSON-escaped into the operand.
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
        // saiku#1902: every descriptor-controlled value is JSON-escaped. Unescaped, a '"' in the
        // warehouse URL / user / password / schema could close the operand and append a second
        // Calcite schema (e.g. a "jdbc" type with an arbitrary jdbcUrl) to the model document.
        operand.append("\"ossieYaml\": \"")
                .append(jsonEscape(
                        Objects.requireNonNull(ossieYaml, "ossieYaml").toString()))
                .append("\"");
        if (warehouseJdbcUrl != null && !warehouseJdbcUrl.isBlank()) {
            operand.append(",\"jdbcUrl\": \"")
                    .append(jsonEscape(warehouseJdbcUrl))
                    .append("\"");
        }
        if (warehouseUser != null) {
            operand.append(",\"jdbcUser\": \"")
                    .append(jsonEscape(warehouseUser))
                    .append("\"");
        }
        if (warehousePassword != null) {
            operand.append(",\"jdbcPassword\": \"")
                    .append(jsonEscape(warehousePassword))
                    .append("\"");
        }
        String schema = jsonEscape(schemaName);
        String modelJson = "{\n"
                + "  \"version\": \"1.0\",\n"
                + "  \"defaultSchema\": \"" + schema + "\",\n"
                + "  \"schemas\": [{\n"
                + "    \"name\": \"" + schema + "\",\n"
                + "    \"type\": \"custom\",\n"
                + "    \"factory\": \"bi.saiku.ossie.sql.internal.OssieSchemaFactory\",\n"
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

    /** Minimal JSON string escaping for the hand-built model document. */
    static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
