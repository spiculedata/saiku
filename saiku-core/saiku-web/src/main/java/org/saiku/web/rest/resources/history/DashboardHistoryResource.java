/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.history;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.history.DashboardHistoryService;
import org.saiku.service.history.DashboardVersion;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dashboard version history (issue #947), under
 * {@code /saiku/api/dashboards/history}. List + preview are gated on the
 * caller's ability to READ the dashboard (#940 ACL); restore goes through
 * {@code saveFile}, which enforces {@code canWrite}. Under {@code /rest/**}
 * ({@code isFullyAuthenticated()}) so #941 share guests can't reach it.
 */
@Path("/saiku/api/dashboards/history")
public class DashboardHistoryResource {

    private static final Logger log = LoggerFactory.getLogger(DashboardHistoryResource.class);
    private static final String SAVE_OK = "Save Okay";

    private DashboardHistoryService historyService;
    private DatasourceService datasourceService;
    private SessionService sessionService;

    public void setHistoryService(DashboardHistoryService s) {
        this.historyService = s;
    }

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    /** Version metadata (no full snapshots), newest-first. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("dashboard") String dashboard) {
        if (dashboard == null) {
            return badRequest("dashboard required");
        }
        if (!canRead(dashboard)) {
            return forbidden();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardVersion v : historyService.list(dashboard)) {
            out.add(Map.of("version", v.version, "createdAt", v.createdAt, "author", v.author == null ? "" : v.author));
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /** The full dashboard JSON of one archived version (preview). */
    @GET
    @Path("/version")
    @Produces(MediaType.APPLICATION_JSON)
    public Response version(@QueryParam("dashboard") String dashboard, @QueryParam("version") String version) {
        if (dashboard == null || version == null) {
            return badRequest("dashboard+version required");
        }
        if (!canRead(dashboard)) {
            return forbidden();
        }
        DashboardVersion v = historyService.getVersion(dashboard, version);
        if (v == null) {
            return notFound();
        }
        // v.dashboard is already a JSON document — return it verbatim.
        return Response.ok(v.dashboard).type(MediaType.APPLICATION_JSON).build();
    }

    /** Overwrite the live dashboard with an archived version; the pre-restore
     *  state is itself archived so a restore is reversible. */
    @POST
    @Path("/restore")
    @Produces(MediaType.APPLICATION_JSON)
    public Response restore(@QueryParam("dashboard") String dashboard, @QueryParam("version") String version) {
        if (dashboard == null || version == null) {
            return badRequest("dashboard+version required");
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        if (!canRead(dashboard)) {
            return forbidden();
        }
        DashboardVersion v = historyService.getVersion(dashboard, version);
        if (v == null) {
            return notFound();
        }
        // Snapshot the current state before overwriting (so restore is undoable).
        String current;
        try {
            current = datasourceService.getFileData(dashboard, username, roles);
        } catch (RuntimeException e) {
            current = null;
        }
        // saveFile enforces canWrite (#940) — a non-writer is rejected here.
        String resp;
        try {
            resp = datasourceService.saveFile(v.dashboard, dashboard, username, roles);
        } catch (RuntimeException e) {
            return forbidden();
        }
        if (!SAVE_OK.equals(resp)) {
            return forbidden();
        }
        try {
            historyService.archive(dashboard, current, username);
        } catch (RuntimeException e) {
            log.warn("history archive after restore failed for {}", dashboard, e);
        }
        log.info("dashboard {} restored to version {} by {}", dashboard, version, username);
        return Response.ok(Map.of("status", "OK", "restored", version))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /* --------------------------- helpers ---------------------------- */

    /** canRead proxy: a successful dashboard read (getResourceACL is canGrant-only). */
    private boolean canRead(String dashboard) {
        if (dashboard == null || !dashboard.endsWith(".saikudash")) {
            return false;
        }
        try {
            String d = datasourceService.getFileData(dashboard, currentUsername(), currentRoles());
            return d != null && !d.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String currentUsername() {
        if (sessionService == null) {
            return null;
        }
        Object u = sessionService.getAllSessionObjects().get("username");
        return u == null ? null : u.toString();
    }

    // saiku#1752: roles come from the single authoritative SecurityContextHolder reader, not the
    // lazily-seeded session "roles" map (see SessionRoles / saiku#1747).
    private List<String> currentRoles() {
        return org.saiku.web.rest.util.SessionRoles.currentRoles();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("status", "FORBIDDEN", "error", "You don't have access to this dashboard"))
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
