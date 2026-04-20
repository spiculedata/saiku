/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SchemaGenOrchestrator.ConnectionProvider} implementation backed by Saiku's
 * {@link DatasourceService}. Opens a fresh JDBC {@link Connection} to the underlying relational
 * database referenced by the Saiku data source's Mondrian {@code location} URL.
 *
 * <p>Saiku Mondrian data sources store their location as:
 *
 * <pre>jdbc:mondrian:Jdbc=&lt;real-jdbc-url&gt;;Catalog=...;JdbcDrivers=&lt;driver-class&gt;[;...]</pre>
 *
 * <p>This class parses the {@code Jdbc=} and {@code JdbcDrivers=} components out of that URL,
 * loads the driver class, and opens a standalone {@link DriverManager} connection using the
 * data source's {@code username} / {@code password} properties. The returned connection is
 * independent of Saiku's pooled olap4j / Mondrian connection — the orchestrator closes it via
 * try-with-resources without affecting live cube sessions.
 *
 * <p>If the location is already a plain {@code jdbc:} URL (non-Mondrian direct data source), it
 * is used verbatim.
 */
public class DatasourceJdbcConnectionProvider implements SchemaGenOrchestrator.ConnectionProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DatasourceJdbcConnectionProvider.class);

    private static final String MONDRIAN_PREFIX = "jdbc:mondrian:";

    private final DatasourceService datasourceService;

    public DatasourceJdbcConnectionProvider(DatasourceService datasourceService) {
        this.datasourceService = Objects.requireNonNull(datasourceService, "datasourceService");
    }

    @Override
    public Connection get(String dataSourceId) throws SQLException {
        SaikuDatasource ds = datasourceService.getDatasource(dataSourceId);
        if (ds == null) {
            throw new SQLException("no Saiku datasource named '" + dataSourceId + "'");
        }
        Properties props = ds.getProperties();
        if (props == null) {
            throw new SQLException("Saiku datasource '" + dataSourceId + "' has no properties");
        }
        String location = props.getProperty(ISaikuConnection.URL_KEY);
        if (location == null || location.isEmpty()) {
            throw new SQLException(
                    "Saiku datasource '" + dataSourceId + "' has no '" + ISaikuConnection.URL_KEY + "' property");
        }

        String jdbcUrl;
        String driver;
        if (location.startsWith(MONDRIAN_PREFIX)) {
            jdbcUrl = extractKey(location, "Jdbc");
            driver = extractKey(location, "JdbcDrivers");
            if (jdbcUrl == null) {
                throw new SQLException("Mondrian location for '" + dataSourceId + "' is missing 'Jdbc=' component");
            }
        } else if (location.startsWith("jdbc:")) {
            jdbcUrl = location;
            driver = props.getProperty(ISaikuConnection.DRIVER_KEY);
        } else {
            throw new SQLException("Saiku datasource '" + dataSourceId + "' location is not a JDBC URL: " + location);
        }

        if (driver != null && !driver.isEmpty()) {
            // JdbcDrivers can be a comma-separated list — try each until one loads.
            for (String candidate : driver.split(",")) {
                String c = candidate.trim();
                if (c.isEmpty()) {
                    continue;
                }
                try {
                    Class.forName(c);
                } catch (ClassNotFoundException ex) {
                    LOG.debug("JDBC driver '{}' not found on classpath for '{}': {}", c, dataSourceId, ex.getMessage());
                }
            }
        }

        String username = props.getProperty(ISaikuConnection.USERNAME_KEY);
        String password = props.getProperty(ISaikuConnection.PASSWORD_KEY);

        Properties jdbcProps = new Properties();
        if (username != null) {
            jdbcProps.setProperty("user", username);
        }
        if (password != null) {
            jdbcProps.setProperty("password", password);
        }
        return DriverManager.getConnection(jdbcUrl, jdbcProps);
    }

    /**
     * Extract the value associated with {@code key=} from a Mondrian-style semicolon-delimited
     * location URL. Handles the specific Mondrian quirk that the inner {@code Jdbc=} value is
     * itself a {@code jdbc:...} URL which may contain its own {@code =} characters but, by
     * convention, no embedded {@code ;}.
     *
     * @return the raw value, or {@code null} if the key is not present
     */
    static String extractKey(String location, String key) {
        // Strip the leading "jdbc:mondrian:" if present so we don't match the wrong token.
        String body = location.startsWith(MONDRIAN_PREFIX) ? location.substring(MONDRIAN_PREFIX.length()) : location;
        String needle = key + "=";
        int idx = body.indexOf(needle);
        while (idx > 0 && body.charAt(idx - 1) != ';') {
            // Keep scanning — we matched a substring that isn't at a segment boundary.
            int next = body.indexOf(needle, idx + 1);
            if (next < 0) {
                return null;
            }
            idx = next;
        }
        if (idx < 0) {
            return null;
        }
        int start = idx + needle.length();
        int end = body.indexOf(';', start);
        String value = end < 0 ? body.substring(start) : body.substring(start, end);
        return value.isEmpty() ? null : value;
    }
}
