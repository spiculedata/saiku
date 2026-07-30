/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.apps;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.saiku.repository.IRepositoryObject;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS CRUD over {@code .saikuapp} App Builder documents. Backed by the same
 * {@link DatasourceService} file primitives that {@code .saiku} query files and
 * {@code .saikudash} dashboards use, so apps inherit the JCR permission model +
 * repository layout without a parallel storage path.
 *
 * <p>This resource is a direct clone of
 * {@link org.saiku.web.rest.resources.dashboards.DashboardResource}: it stores
 * an <strong>opaque</strong> {@link JsonNode} and never interprets the document.
 * The UI owns the schema — the backend validates only that the body is a JSON
 * object and that the repository path is safe, then persists the raw JSON
 * verbatim (re-pretty-printed for diff-friendly git-backed repos). There is
 * deliberately no typed Java model for the app document: binding to a POJO and
 * re-serialising would silently drop any UI-owned field the model didn't
 * catalogue (the {@code welcome.saikudash} field-loss incident, saiku#1179).
 */
@Path("/saiku/api/apps")
public class AppResource {

    private static final Logger log = LoggerFactory.getLogger(AppResource.class);

    /** Repository extension for App Builder documents. */
    private static final String APP_EXTENSION = ".saikuapp";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            // Apps are persisted-from-UI documents, not server-authored types —
            // unknown fields must round-trip, never fail the load (saiku#1179).
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Save-OK and remove-OK sentinels returned by {@link DatasourceService}. */
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
     * List saved apps. Scoped to the {@code .saikuapp} extension so the
     * catalogue returns only App Builder documents the caller can read (the
     * repository layer applies the per-file ACL).
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        String username = currentUsername();
        List<String> roles = currentRoles();
        List<IRepositoryObject> files = datasourceService.getFiles(List.of(APP_EXTENSION), username, roles);
        return Response.ok(files).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Load an app by repository path. {@code path} is the full JCR path
     * including the {@code .saikuapp} extension, URL-encoded if it contains
     * slashes.
     */
    @GET
    @Path("/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response load(@PathParam("path") String path) {
        if (isUnsafePath(path)) {
            return badRequest("path", "invalid path");
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        String body;
        try {
            body = datasourceService.getFileData(path, username, roles);
        } catch (RuntimeException e) {
            log.warn("app load failed for {} (user={})", path, username, e);
            return notFound(path, "App not found or not readable");
        }
        if (body == null || body.isEmpty()) {
            return notFound(path, "App not found");
        }
        // Disk -> wire passthrough keeps the UI as the schema authority. Parse
        // as a JsonNode for a shallow shape check, then return the raw JSON so
        // no UI-owned field can be dropped by a typed re-serialise (saiku#1179).
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null || !node.isObject()) {
                log.error("app {} is not a JSON object", path);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("status", "ERROR", "error", "Stored app is not a JSON object"))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            log.error("app {} is unparseable JSON", path, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("status", "ERROR", "error", "Stored app is not valid JSON: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /**
     * Create or overwrite an app. The JSON body is canonicalised (pretty-printed,
     * NULL fields dropped) before write so the stored file is diff-friendly under
     * git-backed repos.
     */
    @POST
    @Path("/{path:.+}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("path") String path, String rawBody) {
        return write(path, rawBody);
    }

    /**
     * Update an existing app. Semantically an alias of {@link #save} — the
     * repository primitive is an idempotent write keyed on the path, so create
     * and update share one implementation (mirrors the dashboard resource's
     * single-write posture).
     */
    @PUT
    @Path("/{path:.+}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("path") String path, String rawBody) {
        return write(path, rawBody);
    }

    private Response write(String path, String rawBody) {
        if (path == null || path.isBlank()) {
            return badRequest("path", "path required");
        }
        if (isUnsafePath(path)) {
            return badRequest("path", "invalid path");
        }
        if (rawBody == null || rawBody.isBlank()) {
            return badRequest("body", "app body required");
        }
        // Persist the RAW request JSON (re-pretty-printed) rather than a typed
        // POJO round-trip: the app document is opaque and the UI owns its
        // schema, so every field must survive verbatim (saiku#1179).
        JsonNode node;
        try {
            node = MAPPER.readTree(rawBody);
        } catch (JsonProcessingException e) {
            return badRequest("body", "invalid app JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            return badRequest("body", "app body must be a JSON object");
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        String body;
        try {
            body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("app {} serialisation failed", path, e);
            return Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Failed to serialise app: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String resp = datasourceService.saveFile(body, path, username, roles);
        if (SAVE_OK.equals(resp)) {
            return Response.ok(Map.of("status", "OK", "path", path))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        log.warn("app save rejected for {} (user={}) — datasourceService returned '{}'", path, username, resp);
        return Response.serverError()
                .entity(Map.of("status", "ERROR", "error", "Save rejected: " + resp))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @DELETE
    @Path("/{path:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("path") String path) {
        if (isUnsafePath(path)) {
            return badRequest("path", "invalid path");
        }
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

    /**
     * Reject path traversal. DashboardResource delegates path safety wholly to
     * the JCR ACL layer; here we add a cheap, defence-in-depth guard so a
     * {@code ..} segment (raw or percent-encoded) can never reach the storage
     * primitive and escape the repository root. This strengthens — never
     * weakens — the dashboard posture.
     */
    private static boolean isUnsafePath(String path) {
        if (path == null) {
            return false; // null handled by the caller's own validation
        }
        String decoded = path;
        try {
            decoded = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // malformed encoding — treat the raw form below as authoritative
        }
        return containsTraversal(path) || containsTraversal(decoded);
    }

    private static boolean containsTraversal(String p) {
        String normalised = p.replace('\\', '/');
        return normalised.contains("../")
                || normalised.contains("/..")
                || normalised.equals("..")
                || normalised.startsWith("../");
    }

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
