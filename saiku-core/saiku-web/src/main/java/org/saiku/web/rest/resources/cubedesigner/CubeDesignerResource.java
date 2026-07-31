/*
 * Copyright 2026 Paul Stoellberger / Spicule
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.cubedesigner;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import mondrian.rolap.M3ToM4Converter;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;
import org.saiku.service.user.UserService;
import org.saiku.web.rest.resources.schemagen.DatasourceJdbcConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-mostly backend for the cube/schema designer UI ({@code saiku-ui/src/lib/cube-designer}).
 * Three endpoints matching the designer's {@code CubeDesignerBackend} adapter:
 *
 * <ul>
 *   <li>{@code GET  /introspect/{dataSourceId}} — the datasource's tables + columns (source sidebar)</li>
 *   <li>{@code GET  /sample/{dataSourceId}?table=&amp;schema=&amp;limit=} — preview rows for a table</li>
 *   <li>{@code POST /convert} — upgrade a Mondrian-3 schema XML to Mondrian-4</li>
 * </ul>
 *
 * <p>Auth mirrors the schema-generator: an inline {@code userService.isAdmin()} guard (403 for
 * non-admins). {@code userService} is optional so the constructor stays test-friendly — when null,
 * the guard no-ops (headless/test mode). Registered as a Spring bean in {@code saiku-beans.xml};
 * the Jersey application auto-scans {@code @Path} beans.
 */
@Path("/saiku/admin/cube-designer")
public class CubeDesignerResource {

    private static final Logger LOG = LoggerFactory.getLogger(CubeDesignerResource.class);
    private static final String MONDRIAN_PREFIX = "jdbc:mondrian:";
    private static final int DEFAULT_SAMPLE_LIMIT = 25;
    private static final int MAX_SAMPLE_LIMIT = 500;

    private final DatasourceJdbcConnectionProvider connectionProvider;
    private final DatasourceService datasourceService;
    private UserService userService; // optional; see class doc

    public CubeDesignerResource(
            DatasourceJdbcConnectionProvider connectionProvider, DatasourceService datasourceService) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.datasourceService = Objects.requireNonNull(datasourceService, "datasourceService");
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    // ── DTOs ────────────────────────────────────────────────────────────────
    public record ColumnView(String name, String type, boolean nullable, boolean primaryKey) {}

    public record TableView(String schema, String name, List<ColumnView> columns) {}

    public record IntrospectResult(List<TableView> tables) {}

    public record SampleResult(List<String> columns, List<List<Object>> rows) {}

    public record ConvertRequest(String mondrianXml, String dataSourceId) {}

    public record ConvertResult(String mondrianXml) {}

    // ── (1) introspect ───────────────────────────────────────────────────────
    @GET
    @Path("/introspect/{dataSourceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response introspect(@PathParam("dataSourceId") String dataSourceId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        // Skip row-count estimates (threshold 0) — an interactive designer call
        // must not fire COUNT(*) across every table.
        JdbcIntrospector introspector = new JdbcIntrospector(new JdbcIntrospector.Options()
                .withTableSizeThreshold(0)
                .withIncludeTableTypes(List.of("TABLE", "VIEW")));
        try (Connection conn = connectionProvider.get(dataSourceId)) {
            DbModel model = introspector.introspect(conn);
            List<TableView> tables = new ArrayList<>(model.tables().size());
            for (DbTable t : model.tables()) {
                List<ColumnView> cols = new ArrayList<>(t.columns().size());
                for (DbColumn c : t.columns()) {
                    cols.add(new ColumnView(
                            c.name(), c.type() == null ? null : c.type().getName(), c.nullable(), c.primaryKey()));
                }
                tables.add(new TableView(t.schema(), t.name(), cols));
            }
            return Response.ok(new IntrospectResult(tables)).build();
        } catch (SQLException e) {
            LOG.warn("cube-designer introspect failed for '{}'", dataSourceId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("could not introspect the datasource")
                    .build();
        }
    }

    // ── (2) sample ────────────────────────────────────────────────────────────
    @GET
    @Path("/sample/{dataSourceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sample(
            @PathParam("dataSourceId") String dataSourceId,
            @QueryParam("table") String table,
            @QueryParam("schema") String schema,
            @QueryParam("limit") Integer limit) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (table == null || table.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("missing 'table'")
                    .build();
        }
        int cap = clampLimit(limit);
        // `limit` may arrive as a schema-qualified "schema.table" (the designer
        // sends the qualified name) — split it if `schema` wasn't passed.
        String tbl = table;
        String sch = schema;
        if ((sch == null || sch.isBlank()) && table.contains(".")) {
            int dot = table.indexOf('.');
            sch = table.substring(0, dot);
            tbl = table.substring(dot + 1);
        }
        String sql = "SELECT * FROM " + quoteQualified(sch, tbl);
        try (Connection conn = connectionProvider.get(dataSourceId);
                Statement st = conn.createStatement()) {
            // setMaxRows caps the result dialect-independently (no LIMIT/TOP/ROWNUM rewrite).
            st.setMaxRows(cap);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> cols = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    cols.add(md.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return Response.ok(new SampleResult(cols, rows)).build();
            }
        } catch (SQLException e) {
            LOG.warn("cube-designer sample failed for '{}' table '{}'", dataSourceId, table, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("could not sample the table")
                    .build();
        }
    }

    // ── (3) convert (Mondrian 3 → 4) ──────────────────────────────────────────
    @POST
    @Path("/convert")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response convert(ConvertRequest req) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (req == null || req.mondrianXml() == null || req.mondrianXml().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("missing 'mondrianXml'")
                    .build();
        }
        JdbcCoords coords;
        try {
            coords = resolveCoords(req.dataSourceId());
        } catch (SQLException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("unknown datasource")
                    .build();
        }
        try {
            String m4 = M3ToM4Converter.convert(req.mondrianXml(), coords.jdbcUrl(), coords.user(), coords.password());
            return Response.ok(new ConvertResult(m4)).build();
        } catch (M3ToM4Converter.ConversionException e) {
            // 422 Unprocessable — the schema/connection is the problem, not the request shape.
            // The typed token lets the UI show an actionable reason.
            return Response.status(422).entity(e.token()).build();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private Response adminGuard() {
        if (userService == null) {
            return null; // test / headless mode
        }
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return null;
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_SAMPLE_LIMIT;
        }
        return Math.min(limit, MAX_SAMPLE_LIMIT);
    }

    /** SQL-standard identifier quoting (double-quote, doubling embedded quotes) to avoid injection. */
    private static String quoteQualified(String schema, String table) {
        String t = quoteIdent(table);
        return (schema == null || schema.isBlank()) ? t : quoteIdent(schema) + "." + t;
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** Raw JDBC coordinates for the converter (which opens its own DataSource). */
    private record JdbcCoords(String jdbcUrl, String user, String password) {}

    private JdbcCoords resolveCoords(String dataSourceId) throws SQLException {
        SaikuDatasource ds = datasourceService.getDatasource(dataSourceId);
        if (ds == null || ds.getProperties() == null) {
            throw new SQLException("no Saiku datasource named '" + dataSourceId + "'");
        }
        Properties props = ds.getProperties();
        String location = props.getProperty(ISaikuConnection.URL_KEY);
        if (location == null || location.isEmpty()) {
            throw new SQLException("datasource '" + dataSourceId + "' has no location");
        }
        String jdbcUrl = location.startsWith(MONDRIAN_PREFIX) ? extractJdbc(location) : location;
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new SQLException("datasource '" + dataSourceId + "' has no resolvable JDBC URL");
        }
        return new JdbcCoords(
                jdbcUrl,
                props.getProperty(ISaikuConnection.USERNAME_KEY),
                props.getProperty(ISaikuConnection.PASSWORD_KEY));
    }

    /**
     * Pull the inner {@code Jdbc=} URL out of a Mondrian location string
     * ({@code jdbc:mondrian:Jdbc=jdbc:...;JdbcDrivers=...}). The inner value is a
     * {@code jdbc:} URL that may contain {@code =} but, by Mondrian convention, no {@code ;}.
     */
    private static String extractJdbc(String location) {
        String body = location.substring(MONDRIAN_PREFIX.length());
        String needle = "Jdbc=";
        int idx = body.indexOf(needle);
        while (idx > 0 && body.charAt(idx - 1) != ';') {
            idx = body.indexOf(needle, idx + 1);
        }
        if (idx < 0) {
            return null;
        }
        int start = idx + needle.length();
        int end = body.indexOf(';', start);
        return (end < 0 ? body.substring(start) : body.substring(start, end)).trim();
    }
}
