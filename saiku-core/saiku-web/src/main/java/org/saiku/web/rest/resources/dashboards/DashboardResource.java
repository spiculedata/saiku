/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS CRUD over {@code .saikudash} dashboard JSON files. Backed by the
 * same {@link DatasourceService} file primitives that {@code .saiku} query
 * files use, so dashboards inherit the JCR permission model + repository
 * layout without a parallel storage path.
 *
 * <p>v1 surface is CRUD only — query execution still flows through
 * {@code /rest/saiku/api/ai/query} unchanged. The dashboard layer is a
 * layout-only thing on the backend; the frontend computes effective
 * filters and re-issues queries per tile. See
 * {@code docs/plans/2026-05-16-dashboards-design.md}.
 */
@Path("/saiku/api/dashboards")
public class DashboardResource {

    private static final Logger log = LoggerFactory.getLogger(DashboardResource.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Save-OK and remove-OK sentinels returned by {@link DatasourceService}.
     *  Re-checked here so a future signature change there gets caught locally. */
    private static final String SAVE_OK = "Save Okay";

    private static final String REMOVE_OK = "Remove Okay";

    private DatasourceService datasourceService;
    private SessionService sessionService;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    /**
     * Load a dashboard by repository path. {@code path} is the full JCR
     * path including the {@code .saikudash} extension, URL-encoded if
     * it contains slashes.
     */
    @GET
    @Path("/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response load(@PathParam("path") String path) {
        String username = currentUsername();
        List<String> roles = currentRoles();
        String body;
        try {
            body = datasourceService.getFileData(path, username, roles);
        } catch (RuntimeException e) {
            log.warn("dashboard load failed for {} (user={})", path, username, e);
            return notFound(path, "Dashboard not found or not readable");
        }
        if (body == null || body.isEmpty()) {
            return notFound(path, "Dashboard not found");
        }
        try {
            Dashboard dash = MAPPER.readValue(body, Dashboard.class);
            return Response.ok(dash).type(MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            log.error("dashboard {} is unparseable as Dashboard JSON", path, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("status", "ERROR", "error", "Stored dashboard is not valid JSON: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /**
     * Create or overwrite a dashboard. The JSON body is canonicalised
     * (pretty-printed, NULL fields dropped) before write so the stored
     * file is diff-friendly under git-backed repos.
     */
    @POST
    @Path("/{path:.+}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("path") String path, Dashboard dashboard) {
        if (path == null || path.isBlank()) {
            return badRequest("path", "path required");
        }
        if (dashboard == null) {
            return badRequest("body", "dashboard body required");
        }
        if (dashboard.layout == null) {
            return badRequest("layout", "layout required");
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        String body;
        try {
            body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(dashboard);
        } catch (JsonProcessingException e) {
            log.error("dashboard {} serialisation failed", path, e);
            return Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Failed to serialise dashboard: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String resp = datasourceService.saveFile(body, path, username, roles);
        if (SAVE_OK.equals(resp)) {
            return Response.ok(Map.of("status", "OK", "path", path))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        log.warn("dashboard save rejected for {} (user={}) — datasourceService returned '{}'", path, username, resp);
        return Response.serverError()
                .entity(Map.of("status", "ERROR", "error", "Save rejected: " + resp))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @DELETE
    @Path("/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("path") String path) {
        String username = currentUsername();
        List<String> roles = currentRoles();
        String resp = datasourceService.removeFile(path, username, roles);
        if (REMOVE_OK.equals(resp)) {
            return Response.ok(Map.of("status", "OK", "path", path))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return notFound(path, "Delete rejected: " + resp);
    }

    /* --------------------------- helpers ---------------------------- */

    private String currentUsername() {
        if (sessionService == null) return null;
        Object u = sessionService.getAllSessionObjects().get("username");
        return u == null ? null : u.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> currentRoles() {
        if (sessionService == null) return List.of();
        Object r = sessionService.getAllSessionObjects().get("roles");
        if (r instanceof List<?>) return (List<String>) r;
        return List.of();
    }

    private static Response notFound(String path, String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("status", "NOT_FOUND", "path", path, "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response badRequest(String field, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "field", field, "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
