/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.notebooks;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFolderObject;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.web.rest.resources.Query2Resource;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS CRUD over {@code .saikunb} notebook documents (issue #1108: markdown
 * + MDX cells, shareable). Backed by the same {@link DatasourceService} file
 * primitives that {@code .saiku} query files, {@code .saikudash} dashboards
 * and {@code .saikuapp} App Builder documents use, so notebooks inherit the
 * JCR permission model + repository layout without a parallel storage path.
 *
 * <p>This resource is a direct clone of {@link
 * org.saiku.web.rest.resources.apps.AppResource} (itself a clone of {@link
 * org.saiku.web.rest.resources.dashboards.DashboardResource}): it stores an
 * <strong>opaque</strong> {@link JsonNode} and never interprets the document.
 * The UI owns the schema — the backend validates only that the body is a
 * JSON object and that the repository path is safe, then persists the raw
 * JSON verbatim (re-pretty-printed for diff-friendly git-backed repos).
 * There is deliberately no typed Java model for the round-trip: binding to a
 * POJO and re-serialising would silently drop any UI-owned field the model
 * didn't catalogue (the {@code welcome.saikudash} field-loss incident,
 * saiku#1179). The partial typed {@link Notebook}/{@link NotebookCell}
 * models exist only for {@link
 * org.saiku.web.rest.resources.share.NotebookShareViewResource}'s narrower
 * need to enumerate cells — never used here.
 */
@Path("/saiku/api/notebooks")
public class NotebookResource {

    private static final Logger log = LoggerFactory.getLogger(NotebookResource.class);

    /** Repository extension for notebook documents. */
    private static final String NOTEBOOK_EXTENSION = ".saikunb";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            // Notebooks are persisted-from-UI documents, not server-authored types —
            // unknown fields must round-trip, never fail the load (saiku#1179).
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Save-OK and remove-OK sentinels returned by {@link DatasourceService}. */
    private static final String SAVE_OK = "Save Okay";

    private static final String REMOVE_OK = "Remove Okay";

    private DatasourceService datasourceService;
    private SessionService sessionService;
    private OlapDiscoverService olapDiscoverService;
    private Query2Resource query2Resource;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setOlapDiscoverService(OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    public void setQuery2Resource(Query2Resource r) {
        this.query2Resource = r;
    }

    /** Body of {@link #run}: the cell's MDX text plus a name-only cube
     *  reference (never a raw {@link SaikuCube} — see {@link NotebookCell}). */
    public static class RunRequest {
        public String mdx;
        public AiCubeRef cube;
    }

    /**
     * Execute one MDX cell's authored query (issue #1108) — a thin,
     * synchronous wrapper around {@link Query2Resource#execute}: resolve the
     * client-supplied {@link AiCubeRef} against the live schema via {@link
     * NotebookCubeResolver}, build a {@link ThinQuery} from the resolved
     * cube (never from client JSON — see {@link NotebookCell}'s javadoc for
     * why), and delegate. Not under {@code /{path:.+}} — a literal segment
     * always wins JAX-RS's specificity-based matching over that template, so
     * this can never collide with a repository path.
     */
    @POST
    @Path("/run")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response run(RunRequest body) {
        if (body == null || body.mdx == null || body.mdx.isBlank()) {
            return badRequest("mdx", "mdx required");
        }
        if (body.cube == null) {
            return badRequest("cube", "cube required");
        }
        SaikuCube cube = NotebookCubeResolver.resolve(olapDiscoverService, body.cube);
        if (cube == null) {
            return badRequest("cube", "Unknown cube '" + body.cube + "'");
        }
        ThinQuery tq = new ThinQuery(java.util.UUID.randomUUID().toString(), cube, body.mdx);
        return query2Resource.execute(tq, null);
    }

    /**
     * List saved notebooks. Scoped to the {@code .saikunb} extension so the
     * catalogue returns only notebook documents the caller can read (the
     * repository layer applies the per-file ACL).
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        String username = currentUsername();
        List<String> roles = currentRoles();
        // getFiles returns the repository TREE (top-level folders, with matching
        // files nested inside), not a flat list — mirror AppResource's flatten
        // (saiku#1636) so the catalogue shows the .saikunb files themselves.
        List<IRepositoryObject> tree = datasourceService.getFiles(List.of(NOTEBOOK_EXTENSION), username, roles);
        List<IRepositoryObject> notebooks = new ArrayList<>();
        flattenNotebooks(tree, notebooks);
        return Response.ok(notebooks).type(MediaType.APPLICATION_JSON).build();
    }

    /** Depth-first walk collecting only {@code .saikunb} file nodes from the
     *  repository tree {@link DatasourceService#getFiles} returns. */
    private static void flattenNotebooks(List<IRepositoryObject> nodes, List<IRepositoryObject> out) {
        if (nodes == null) {
            return;
        }
        for (IRepositoryObject node : nodes) {
            if (node instanceof RepositoryFolderObject folder) {
                flattenNotebooks(folder.getRepoObjects(), out);
            } else if (node != null
                    && node.getType() == IRepositoryObject.Type.FILE
                    && node.getName() != null
                    && node.getName().endsWith(NOTEBOOK_EXTENSION)) {
                out.add(node);
            }
        }
    }

    /**
     * Load a notebook by repository path. {@code path} is the full JCR path
     * including the {@code .saikunb} extension, URL-encoded if it contains
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
            log.warn("notebook load failed for {} (user={})", path, username, e);
            return notFound(path, "Notebook not found or not readable");
        }
        if (body == null || body.isEmpty()) {
            return notFound(path, "Notebook not found");
        }
        // Disk -> wire passthrough keeps the UI as the schema authority. Parse
        // as a JsonNode for a shallow shape check, then return the raw JSON so
        // no UI-owned field can be dropped by a typed re-serialise (saiku#1179).
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null || !node.isObject()) {
                log.error("notebook {} is not a JSON object", path);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("status", "ERROR", "error", "Stored notebook is not a JSON object"))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            log.error("notebook {} is unparseable JSON", path, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("status", "ERROR", "error", "Stored notebook is not valid JSON: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /**
     * Create or overwrite a notebook. The JSON body is canonicalised
     * (pretty-printed, NULL fields dropped) before write so the stored file
     * is diff-friendly under git-backed repos.
     */
    @POST
    @Path("/{path:.+}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("path") String path, String rawBody) {
        return write(path, rawBody);
    }

    /**
     * Update an existing notebook. Semantically an alias of {@link #save} —
     * the repository primitive is an idempotent write keyed on the path, so
     * create and update share one implementation (mirrors the dashboard /
     * app resources' single-write posture).
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
            return badRequest("body", "notebook body required");
        }
        // Persist the RAW request JSON (re-pretty-printed) rather than a typed
        // POJO round-trip: the notebook document is opaque and the UI owns
        // its schema, so every field must survive verbatim (saiku#1179).
        JsonNode node;
        try {
            node = MAPPER.readTree(rawBody);
        } catch (JsonProcessingException e) {
            return badRequest("body", "invalid notebook JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            return badRequest("body", "notebook body must be a JSON object");
        }
        if (!node.hasNonNull("cells") || !node.get("cells").isArray()) {
            return badRequest("body", "notebook body must have a 'cells' array");
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        String body;
        try {
            body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("notebook {} serialisation failed", path, e);
            return Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Failed to serialise notebook: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String resp = datasourceService.saveFile(body, path, username, roles);
        if (SAVE_OK.equals(resp)) {
            return Response.ok(Map.of("status", "OK", "path", path))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        log.warn("notebook save rejected for {} (user={}) — datasourceService returned '{}'", path, username, resp);
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
     * Reject path traversal. DashboardResource delegates path safety wholly
     * to the JCR ACL layer; here we add a cheap, defence-in-depth guard so a
     * {@code ..} segment (raw or percent-encoded) can never reach the
     * storage primitive and escape the repository root. This strengthens —
     * never weakens — the dashboard posture (mirrors AppResource).
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

    // saiku#1752: roles come from the single authoritative SecurityContextHolder reader, not the
    // lazily-seeded session "roles" map (see SessionRoles / saiku#1747).
    private List<String> currentRoles() {
        return org.saiku.web.rest.util.SessionRoles.currentRoles();
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
