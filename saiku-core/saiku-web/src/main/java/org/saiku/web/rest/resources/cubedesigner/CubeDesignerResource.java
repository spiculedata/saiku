/*
 * Copyright 2026 Paul Stoellberger / Spicule
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.cubedesigner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.saiku.service.datasource.MondrianCatalogResolver;
import org.saiku.service.datasource.SaikuVirtualFileHandler;
import org.saiku.service.datasource.SchemaFileAccessGuard;
import org.saiku.service.olap.ai.cubedesigner.CubeDesignerAiService;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;
import org.saiku.service.user.UserService;
import org.saiku.web.rest.resources.schemagen.DatasourceJdbcConnectionProvider;
import org.saiku.web.rest.util.MondrianLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-mostly backend for the cube/schema designer UI ({@code saiku-ui/src/lib/cube-designer}).
 * Three endpoints matching the designer's {@code CubeDesignerBackend} adapter:
 *
 * <ul>
 *   <li>{@code GET  /introspect/{dataSourceId}} — the datasource's tables + columns (source sidebar)</li>
 *   <li>{@code GET  /sample/{dataSourceId}?table=&amp;schema=&amp;limit=} — preview rows for a table</li>
 *   <li>{@code GET  /schema/{dataSourceId}} — the datasource's existing Mondrian schema XML (edit mode)</li>
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
    private static final int DEFAULT_SAMPLE_LIMIT = 25;
    private static final int MAX_SAMPLE_LIMIT = 500;
    private static final int DEFAULT_PREVIEW_ROWS = 50;
    private static final int MAX_PREVIEW_ROWS = 500;
    private static final int PREVIEW_TIMEOUT_SECONDS = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DatasourceJdbcConnectionProvider connectionProvider;
    private final DatasourceService datasourceService;
    private UserService userService; // optional; see class doc
    private CubeDesignerAiService aiService; // optional; absent ⇒ /turn 503s

    /**
     * Confines {@code file:} schema reads to the Saiku data directories (saiku#1845). Built from the
     * environment rather than injected so the containment holds even for a deployment that predates
     * the bean wiring — an unconfigured guard degrades to the old permissive behaviour and warns.
     */
    private final SchemaFileAccessGuard fileGuard = SchemaFileAccessGuard.fromEnvironment(null);

    public CubeDesignerResource(
            DatasourceJdbcConnectionProvider connectionProvider, DatasourceService datasourceService) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.datasourceService = Objects.requireNonNull(datasourceService, "datasourceService");
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    public void setAiService(CubeDesignerAiService aiService) {
        this.aiService = aiService;
    }

    // ── DTOs ────────────────────────────────────────────────────────────────
    public record ColumnView(String name, String type, boolean nullable, boolean primaryKey) {}

    public record TableView(String schema, String name, List<ColumnView> columns) {}

    public record IntrospectResult(List<TableView> tables) {}

    public record SampleResult(List<String> columns, List<List<Object>> rows) {}

    public record ConvertRequest(String mondrianXml, String dataSourceId) {}

    public record ConvertResult(String mondrianXml) {}

    public record SchemaResult(String mondrianXml, String label) {}

    public record TurnRequest(JsonNode messages, String canvasSummary) {}

    /** Body for {@code POST /try-query} (saiku#1872). {@code maxRows} is optional and clamped. */
    public record TryQueryRequest(String mondrianXml, String dataSourceId, String mdx, Integer maxRows) {}

    /**
     * Preview result. {@code error} carries a readable reason instead of an HTTP failure, because a
     * schema that does not work yet is the expected state while someone is editing one.
     */
    public record TryQueryResult(
            List<String> columns, List<List<Object>> rows, boolean truncated, long durationMs, String error) {}

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

    // ── (3) schema (existing Mondrian XML for edit mode) ──────────────────────
    /**
     * saiku#1634: return the datasource's already-attached Mondrian schema XML so the designer can
     * hydrate the canvas in edit mode (rather than opening blank). The catalog reference is parsed
     * out of the Mondrian {@code location} ({@code Catalog=...}) and resolved three ways:
     *
     * <ul>
     *   <li>{@code file:/path/Foo.xml} — read from the filesystem (the launcher's foodmart/bank use
     *       this: {@code Catalog=file:.../FoodMart4.xml});</li>
     *   <li>{@code res:Foo.xml} — read from the classpath (bundled schemas);</li>
     *   <li>{@code mondrian://Name} or a bare repository path — read from the Saiku repository.</li>
     * </ul>
     *
     * <p>404 when the datasource carries no catalog (plain JDBC / a fresh design target) or the
     * referenced schema can't be found — the host treats that as "new cube, start blank". Admin-only;
     * the {@code file:} path is already admin-authored via the datasource config, so this exposes no
     * file an admin couldn't already reach.
     */
    @GET
    @Path("/schema/{dataSourceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response schema(@PathParam("dataSourceId") String dataSourceId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        SaikuDatasource ds = datasourceService.getDatasource(dataSourceId);
        if (ds == null || ds.getProperties() == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("unknown datasource")
                    .build();
        }
        String location = ds.getProperties().getProperty(ISaikuConnection.URL_KEY);
        String catalog = MondrianLocation.parse(location).catalog();
        if (catalog == null || catalog.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("no schema attached to this datasource")
                    .build();
        }
        String xml;
        try {
            xml = resolveSchemaXml(catalog);
        } catch (IOException e) {
            LOG.warn("cube-designer schema read failed for '{}' catalog '{}'", dataSourceId, catalog, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("could not read the schema")
                    .build();
        }
        if (xml == null || xml.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("schema not found")
                    .build();
        }
        return Response.ok(new SchemaResult(xml, ds.getName())).build();
    }

    // ── (4) convert (Mondrian 3 → 4) ──────────────────────────────────────────
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

    // ── (5) try-query (run an UNSAVED schema) ──────────────────────────────────
    /**
     * Run MDX against a schema the user has not saved yet.
     *
     * <p>saiku#1872: OSS had no way to do this, so the designer's "Try a query" tab returned a 501
     * and the only way to see whether a cube worked was to publish it and hope. Cloud does this
     * gateway-side; this is the OSS equivalent.
     *
     * <p>How it avoids touching disk or the datasource: the proposed XML is registered in memory
     * under a reserved catalog path, served by the {@code VirtualFileHandler} Mondrian already
     * consults (the same seam added for repository-hosted schemas in saiku#1844), and the
     * connection is opened by taking the datasource's OWN stored location and swapping only the
     * {@code Catalog=} component. The warehouse, credentials and driver are therefore exactly the
     * real ones, and the request cannot supply a JDBC URL — a preview that accepted one would be a
     * way to make the server connect anywhere.
     *
     * <p>Admin-only, and no new privilege: an admin can already save this schema and query it. The
     * schema is discarded and its Mondrian cache entry flushed before the response returns, so a
     * long editing session cannot accumulate RolapSchema instances.
     */
    @POST
    @Path("/try-query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response tryQuery(TryQueryRequest req) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (req == null || req.mondrianXml() == null || req.mondrianXml().isBlank()) {
            return badRequest("missing 'mondrianXml'");
        }
        if (req.mdx() == null || req.mdx().isBlank()) {
            return badRequest("missing 'mdx'");
        }

        SaikuDatasource ds = datasourceService.getDatasourceByIdOrName(req.dataSourceId());
        if (ds == null || ds.getProperties() == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new TryQueryResult(null, null, false, 0, "unknown datasource '" + req.dataSourceId() + "'"))
                    .build();
        }
        String location = ds.getProperties().getProperty(ISaikuConnection.URL_KEY);
        String previewLocation;
        try {
            previewLocation = MondrianLocation.parse(location).withCatalog(null);
        } catch (IllegalStateException notMondrian) {
            // A plain-JDBC (or Ossie) datasource has no Mondrian coordinates to borrow.
            return Response.status(422)
                    .entity(new TryQueryResult(
                            null, null, false, 0, "datasource '" + req.dataSourceId() + "' is not a Mondrian source"))
                    .build();
        }

        int cap = req.maxRows() == null ? DEFAULT_PREVIEW_ROWS : Math.min(Math.max(req.maxRows(), 1), MAX_PREVIEW_ROWS);
        String previewRef = SaikuVirtualFileHandler.registerPreview(req.mondrianXml());
        long started = System.currentTimeMillis();
        try {
            String url = MondrianLocation.parse(location).withCatalog(MondrianCatalogResolver.SCHEME + previewRef);
            return runPreview(url, ds, req.mdx(), cap, started);
        } catch (Exception e) {
            // A broken schema or bad MDX is the NORMAL case here — the user is mid-edit. Report it
            // as a readable message rather than a stack trace, and keep the 200 shape the tab
            // renders, so "this cube doesn't work yet" is information, not an error page.
            LOG.debug("cube-designer preview failed", e);
            return Response.ok(
                            new TryQueryResult(null, null, false, System.currentTimeMillis() - started, rootMessage(e)))
                    .build();
        } finally {
            SaikuVirtualFileHandler.unregisterPreview(previewRef);
        }
    }

    /**
     * Open a one-shot Mondrian connection, run the MDX, and flatten the result.
     *
     * <p>Credentials are applied the same way {@code SaikuOlapConnection.connect} applies them,
     * and that detail is load-bearing: for a {@code jdbc:mondrian:} URL the INNER warehouse
     * credentials travel as {@code JdbcUser=} / {@code JdbcPassword=} inside the URL. Passing them
     * only as {@code DriverManager} arguments — the obvious thing — gets you
     * "Wrong user name or password" from the warehouse.
     */
    private Response runPreview(String url, SaikuDatasource ds, String mdx, int cap, long started) throws Exception {
        Properties props = ds.getProperties();
        String user = props.getProperty(ISaikuConnection.USERNAME_KEY);
        String password = props.getProperty(ISaikuConnection.PASSWORD_KEY);
        if ("true".equals(props.getProperty(ISaikuConnection.PASSWORD_ENCRYPT_KEY)) && password != null) {
            password = org.saiku.datasources.connection.encrypt.CryptoUtil.decrypt(password);
        }
        String driver = props.getProperty(ISaikuConnection.DRIVER_KEY);

        if (!url.endsWith(";")) {
            url += ";";
        }
        if (user != null && !user.isEmpty()) {
            url += "JdbcUser=" + user + ";";
        }
        if (password != null && !password.isEmpty()) {
            url += "JdbcPassword=" + password + ";";
        }
        // saiku#1902: same policy as the SaikuOlapConnection chokepoint — validate the FINAL url
        // (credentials appended) and only initialise a real java.sql.Driver class.
        org.saiku.service.datasource.JdbcUrlPolicy.validate(url);
        if (driver != null && !driver.isEmpty()) {
            org.saiku.service.datasource.JdbcUrlPolicy.loadDriverClass(driver);
        }

        try (java.sql.Connection raw = org.saiku.service.datasource.JdbcUrlPolicy.openConnection(url, user, password)) {
            org.olap4j.OlapConnection olap = raw.unwrap(org.olap4j.OlapConnection.class);
            try (org.olap4j.OlapStatement st = olap.createStatement()) {
                st.setQueryTimeout(PREVIEW_TIMEOUT_SECONDS);
                org.olap4j.CellSet cs = st.executeOlapQuery(mdx);
                TryQueryResult body = flatten(cs, cap, System.currentTimeMillis() - started);
                flushPreviewSchema(olap);
                return Response.ok(body).build();
            }
        }
    }

    /**
     * Drop the preview's schema from Mondrian's pool.
     *
     * <p>Every preview uses a fresh catalog path, so without this an editing session would leave a
     * RolapSchema behind for each run. Best-effort: failing to flush must not fail the preview the
     * user actually asked for.
     */
    private void flushPreviewSchema(org.olap4j.OlapConnection olap) {
        if (!mondrian.olap4j.SaikuMondrianHelper.flushSchema(olap)) {
            LOG.debug("could not flush the preview schema from Mondrian's cache");
        }
    }

    /** Cellset → columns + rows, capped. Row headers come first, then each measure cell. */
    private static TryQueryResult flatten(org.olap4j.CellSet cs, int cap, long durationMs) {
        List<org.olap4j.CellSetAxis> axes = cs.getAxes();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

        List<org.olap4j.Position> rowPositions = axes.size() > 1 ? axes.get(1).getPositions() : List.of();
        List<org.olap4j.Position> colPositions =
                axes.isEmpty() ? List.of() : axes.get(0).getPositions();

        // One column per row-axis hierarchy, then one per column-axis position.
        if (!rowPositions.isEmpty()) {
            for (org.olap4j.metadata.Member m : rowPositions.get(0).getMembers()) {
                columns.add(m.getHierarchy().getName());
            }
        }
        for (org.olap4j.Position p : colPositions) {
            columns.add(caption(p));
        }

        boolean truncated = false;
        if (rowPositions.isEmpty()) {
            // Measures-only query: a single row of cells.
            List<Object> only = new ArrayList<>();
            for (int c = 0; c < colPositions.size(); c++) {
                only.add(cs.getCell(colPositions.get(c)).getFormattedValue());
            }
            if (!only.isEmpty()) {
                rows.add(only);
            }
        } else {
            for (int r = 0; r < rowPositions.size(); r++) {
                if (rows.size() >= cap) {
                    truncated = true;
                    break;
                }
                List<Object> row = new ArrayList<>();
                for (org.olap4j.metadata.Member m : rowPositions.get(r).getMembers()) {
                    row.add(m.getCaption());
                }
                for (org.olap4j.Position cp : colPositions) {
                    row.add(cs.getCell(cp, rowPositions.get(r)).getFormattedValue());
                }
                rows.add(row);
            }
        }
        return new TryQueryResult(columns, rows, truncated, durationMs, null);
    }

    private static String caption(org.olap4j.Position p) {
        StringBuilder sb = new StringBuilder();
        for (org.olap4j.metadata.Member m : p.getMembers()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(m.getCaption());
        }
        return sb.toString();
    }

    /** Deepest useful message — Mondrian nests the real reason several causes down. */
    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return (m == null || m.isBlank()) ? c.getClass().getSimpleName() : m;
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new TryQueryResult(null, null, false, 0, message))
                .build();
    }

    // ── (5) DimSum AI turn ─────────────────────────────────────────────────────
    @POST
    @Path("/turn")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response turn(TurnRequest req) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (aiService == null || !aiService.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("The schema-authoring AI is not configured on this server.")
                    .build();
        }
        try {
            JsonNode content =
                    aiService.turn(req == null ? null : req.messages(), req == null ? null : req.canvasSummary());
            ObjectNode out = MAPPER.createObjectNode();
            out.set("content", content);
            return Response.ok(out).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("The schema-authoring AI is not configured on this server.")
                    .build();
        } catch (IOException e) {
            LOG.warn("cube-designer AI turn failed", e);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("The AI provider could not be reached.")
                    .build();
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
        // saiku#1661: accept either the UUID id (what the admin API + cube-designer route
        // hand out) or the connection name.
        SaikuDatasource ds = datasourceService.getDatasourceByIdOrName(dataSourceId);
        if (ds == null || ds.getProperties() == null) {
            throw new SQLException("no Saiku datasource named '" + dataSourceId + "'");
        }
        Properties props = ds.getProperties();
        String location = props.getProperty(ISaikuConnection.URL_KEY);
        if (location == null || location.isEmpty()) {
            throw new SQLException("datasource '" + dataSourceId + "' has no location");
        }
        // Mondrian locations wrap the real JDBC URL as `Jdbc=...`; plain locations are the URL itself.
        String parsedJdbc = MondrianLocation.parse(location).jdbc();
        String jdbcUrl = parsedJdbc != null ? parsedJdbc.trim() : location;
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new SQLException("datasource '" + dataSourceId + "' has no resolvable JDBC URL");
        }
        return new JdbcCoords(
                jdbcUrl,
                props.getProperty(ISaikuConnection.USERNAME_KEY),
                props.getProperty(ISaikuConnection.PASSWORD_KEY));
    }

    /**
     * Resolve a Mondrian {@code Catalog=} reference to its schema XML. Handles {@code file:} paths
     * (filesystem), {@code res:} (classpath), and {@code mondrian://Name} / bare repository paths
     * (Saiku repository). Returns null when the referenced schema can't be found.
     */
    private String resolveSchemaXml(String catalog) throws IOException {
        String c = catalog.trim();
        if (c.startsWith("file:")) {
            // saiku#1845: this endpoint returns whatever it reads straight to the caller, so an
            // unconstrained path here is an arbitrary file read — `Catalog=file:/etc/shadow` was
            // answered with the file's contents. Confine it to the Saiku data directories.
            java.nio.file.Path target = java.nio.file.Path.of(stripFileScheme(c));
            SchemaFileAccessGuard guard = this.fileGuard;
            if (guard != null) {
                guard.assertReadable(target);
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        }
        if (c.startsWith("res:")) {
            String resource = c.substring("res:".length());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        // Repository-stored schema. saiku#1844: the lookup rule now lives in
        // MondrianCatalogResolver, shared with the handler that resolves the same references on
        // the CONNECT path. It used to be duplicated here, which is how the designer could read a
        // `mondrian://` schema that the connection manager could not load.
        return MondrianCatalogResolver.resolve(c, datasourceService::getInternalFileData);
    }

    /**
     * Strip the {@code file:} scheme (and any {@code //authority}) off a file URL, leaving the path.
     * Handles {@code file:/p}, {@code file:///p}, and {@code file://host/p}.
     *
     * <p>saiku#1661: on Windows a file URL for a local path is {@code file:/C:/...} (or
     * {@code file:///C:/...}), which leaves a leading slash before the drive letter that
     * {@link java.nio.file.Path#of} rejects with {@code InvalidPathException: Illegal char <:>
     * at index 2}. Drop that leading slash so {@code /C:/x} → {@code C:/x}; genuine POSIX
     * absolute paths ({@code /home/x}) are untouched.
     */
    static String stripFileScheme(String fileUrl) {
        String path = fileUrl.substring("file:".length());
        if (path.startsWith("//")) {
            int slash = path.indexOf('/', 2);
            path = slash >= 0 ? path.substring(slash) : path.substring(2);
        }
        if (path.length() >= 3 && path.charAt(0) == '/' && path.charAt(2) == ':' && isDriveLetter(path.charAt(1))) {
            path = path.substring(1);
        }
        return path;
    }

    private static boolean isDriveLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
