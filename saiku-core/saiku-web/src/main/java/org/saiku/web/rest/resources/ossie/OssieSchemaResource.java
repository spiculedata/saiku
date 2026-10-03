/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.ossie;

import bi.saiku.ossie.OssieYamlReader;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.history.SchemaHistoryService;
import org.saiku.service.user.UserService;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read/write the raw Ossie (M4) semantic-model YAML for an OSSIE datasource. This is the write
 * path issue #1121's schema-history feature attaches to: there was previously no REST endpoint
 * that could save this YAML at all (the only prior writer was the {@code ossie-export} CLI
 * command, run offline, and {@code OssieDiscoverService} only reads it). Deliberately minimal —
 * whole-file overwrite, no branch preview / autocomplete / conflict detection; those belong to
 * the Data Model IDE (#1428), which can grow its own richer save endpoint later without this one
 * going away.
 *
 * <p>Admin-gated ({@code userService.isAdmin()}), same posture as {@code AdminResource}'s Mondrian
 * schema upload and {@code CubeDesignerResource} — Ossie datasources are admin-managed with no
 * per-object ACL, unlike dashboards.
 *
 * <p>Every successful {@code PUT} archives the replaced YAML (or {@code null} on first write) via
 * {@link SchemaHistoryService#archive}; a history-write failure is logged and swallowed so it can
 * never block or fail the schema save itself.
 */
@Path("/saiku/admin/ossie/schema")
public class OssieSchemaResource {

    private static final Logger log = LoggerFactory.getLogger(OssieSchemaResource.class);

    private DatasourceService datasourceService;
    private SchemaHistoryService historyService;
    private UserService userService; // optional; null in headless/test mode, matches CubeDesignerResource
    private SessionService sessionService;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setHistoryService(SchemaHistoryService s) {
        this.historyService = s;
    }

    public void setUserService(UserService s) {
        this.userService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    /** The datasource's current raw YAML, verbatim. */
    @GET
    @Path("/{datasourceId}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response get(@PathParam("datasourceId") String datasourceId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        Path yamlPath;
        try {
            yamlPath = resolveYamlPath(datasourceId);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (!Files.isReadable(yamlPath)) {
            return notFound();
        }
        try {
            return Response.ok(Files.readString(yamlPath, StandardCharsets.UTF_8))
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        } catch (IOException e) {
            log.warn("failed to read Ossie YAML for {}", datasourceId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("failed to read schema: " + e.getMessage())
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
    }

    /**
     * Overwrite the datasource's YAML with {@code body}. Validated with {@link OssieYamlReader}
     * before anything is written to disk — a malformed document is rejected as 400 and the file
     * on disk is left untouched. On success, the replaced content (or {@code null} for a
     * first-ever write) is archived to the schema history.
     */
    @PUT
    @Path("/{datasourceId}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("datasourceId") String datasourceId, String body) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (body == null || body.isBlank()) {
            return badRequest("YAML body must not be empty");
        }
        try {
            new OssieYamlReader().readString(body);
        } catch (IOException e) {
            return badRequest("invalid Ossie YAML: " + e.getMessage());
        }
        Path yamlPath;
        try {
            yamlPath = resolveYamlPath(datasourceId);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        String previous = null;
        if (Files.isReadable(yamlPath)) {
            try {
                previous = Files.readString(yamlPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("could not read existing Ossie YAML for {} before overwrite", datasourceId, e);
            }
        }
        try {
            Files.writeString(yamlPath, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("failed to write Ossie YAML for {}", datasourceId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("status", "ERROR", "error", "failed to write schema: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String username = currentUsername();
        if (historyService != null) {
            try {
                historyService.archive(datasourceId, previous, body, username);
            } catch (RuntimeException e) {
                log.warn("schema history archive failed for {} (save still succeeded)", datasourceId, e);
            }
        }
        return Response.ok(Map.of("status", "OK", "action", previous == null ? "CREATE" : "UPDATE"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /* --------------------------- helpers ---------------------------- */

    /** Resolve + validate the datasource's underlying YAML file path (same lookup rules as
     *  {@code OssieDiscoverService.readDocument}). */
    private Path resolveYamlPath(String datasourceId) {
        SaikuDatasource ds = datasourceService.getDatasource(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("No datasource named '" + datasourceId + "'");
        }
        if (ds.getType() != SaikuDatasource.Type.OSSIE) {
            throw new IllegalArgumentException(
                    "Datasource '" + datasourceId + "' is not an OSSIE datasource (type=" + ds.getType() + ")");
        }
        String yamlPath = ds.getProperties().getProperty(ISaikuConnection.OSSIE_YAML_KEY);
        if (yamlPath == null || yamlPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Ossie datasource '" + datasourceId + "' has no '" + ISaikuConnection.OSSIE_YAML_KEY
                            + "' property");
        }
        return Path.of(yamlPath);
    }

    private Response adminGuard() {
        if (userService == null) {
            return null; // test / headless mode
        }
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return null;
    }

    private String currentUsername() {
        if (sessionService == null) {
            return null;
        }
        Object u = sessionService.getAllSessionObjects().get("username");
        return u == null ? null : u.toString();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("status", "NOT_FOUND", "error", "No schema saved for this datasource yet"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
