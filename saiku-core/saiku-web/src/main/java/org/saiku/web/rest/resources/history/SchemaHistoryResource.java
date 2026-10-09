/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.history;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.history.SchemaHistoryService;
import org.saiku.service.history.SchemaVersion;
import org.saiku.service.user.UserService;

/**
 * Schema (Ossie/M4 YAML) version history REST surface — issue #1121, Phase 1: {@code
 * /ui/admin/schema-history?cube=<target>} lists entries newest-first; expanding one fetches the
 * raw old/new YAML for a diff. Writes happen via {@code OssieSchemaResource}, which archives to
 * the same {@link SchemaHistoryService}.
 *
 * <p>Admin-gated ({@code userService.isAdmin()}) — Ossie datasources are admin-managed, so this
 * follows the {@code AdminResource}/{@code CubeDesignerResource} auth posture rather than the
 * dashboard history resource's per-object canRead/canWrite (#947), which doesn't apply here.
 * Restore/rollback (issue Phase 3) is intentionally not exposed yet.
 */
@Path("/saiku/api/schema/history")
public class SchemaHistoryResource {

    private SchemaHistoryService historyService;
    private UserService userService; // optional; null in headless/test mode

    public void setHistoryService(SchemaHistoryService s) {
        this.historyService = s;
    }

    public void setUserService(UserService s) {
        this.userService = s;
    }

    /** Version metadata (no YAML bodies), newest-first. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("target") String target) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (target == null || target.isBlank()) {
            return badRequest("target required");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SchemaVersion v : historyService.list(target)) {
            out.add(Map.of(
                    "id", v.id,
                    "createdAt", v.createdAt,
                    "author", v.author == null ? "" : v.author,
                    "action", v.action == null ? "" : v.action,
                    "target", v.target == null ? "" : v.target));
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /** One entry in full, including old/new YAML, for the expandable diff. */
    @GET
    @Path("/version")
    @Produces(MediaType.APPLICATION_JSON)
    public Response version(@QueryParam("target") String target, @QueryParam("version") String version) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (target == null || target.isBlank() || version == null || version.isBlank()) {
            return badRequest("target+version required");
        }
        SchemaVersion v = historyService.getVersion(target, version);
        if (v == null) {
            return notFound();
        }
        return Response.ok(v).type(MediaType.APPLICATION_JSON).build();
    }

    /* --------------------------- helpers ---------------------------- */

    private Response adminGuard() {
        if (userService == null) {
            return null; // test / headless mode
        }
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return null;
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("status", "NOT_FOUND", "error", "Unknown version"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
