/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiSavedQueryRequest;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.rest.resources.dashboards.Dashboard;
import org.saiku.web.rest.resources.dashboards.DashboardTile;
import org.saiku.web.rest.resources.dashboards.TileQuery;
import org.saiku.web.security.share.ShareTokenAuthFilter.ShareGuestDetails;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The ONLY data surface an account-free share guest can reach (issue #941).
 * Reachable only with a valid {@code ROLE_SHARE_GUEST} established by
 * {@link ShareTokenAuthFilter}; the Spring Security rules forbid that role
 * everywhere else, so a guest cannot hit {@code /ai/query}, drill-through,
 * other dashboards, the repository, or the mint endpoints.
 *
 * <p>The dashboard the guest may see is pinned by the token (read from the
 * Authentication details, never from client input). Tile queries run under the
 * share owner's data scope via {@link SessionService#runAs} — a delegation by
 * someone who held GRANT — so the guest sees exactly what was shared, but with
 * no ability to author a query or widen the cube.
 */
@Path("/saiku/share/view")
public class ShareViewResource {

    private static final Logger log = LoggerFactory.getLogger(ShareViewResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatasourceService datasourceService;
    private SessionService sessionService;
    private AiQueryResource aiQueryResource;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setAiQueryResource(AiQueryResource r) {
        this.aiQueryResource = r;
    }

    /** The pinned dashboard's layout, so the viewer can render its tiles. */
    @GET
    @Path("/dashboard")
    @Produces(MediaType.APPLICATION_JSON)
    public Response dashboard() {
        ShareGuestDetails g = guest();
        if (g == null) {
            return invalid();
        }
        Dashboard dash = loadDashboard(g);
        if (dash == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "Shared dashboard is no longer available"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return Response.ok(dash).type(MediaType.APPLICATION_JSON).build();
    }

    /** Run a single tile's authored query under the owner's scope. The guest
     *  supplies no query body — only the tile id, which must belong to the
     *  pinned dashboard. */
    @POST
    @Path("/tile/{tileId}/query")
    @Produces(MediaType.APPLICATION_JSON)
    public Response tileQuery(@PathParam("tileId") String tileId) {
        ShareGuestDetails g = guest();
        if (g == null) {
            return invalid();
        }
        Dashboard dash = loadDashboard(g);
        if (dash == null || dash.layout == null || dash.layout.tiles == null) {
            return invalid();
        }
        DashboardTile tile = null;
        for (DashboardTile t : dash.layout.tiles) {
            if (tileId.equals(t.id)) {
                tile = t;
                break;
            }
        }
        if (tile == null || tile.query == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "No such queryable tile"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        final TileQuery q = tile.query;
        try {
            // Execute under the share owner's identity (Mondrian role + JCR
            // file ACL). The query comes from the stored dashboard, never the
            // client, so the guest cannot pivot the cube or read raw data.
            return sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                if ("inline".equals(q.kind) && q.body != null) {
                    return aiQueryResource.executeAi(q.body, "records");
                } else if ("reference".equals(q.kind) && q.path != null) {
                    AiSavedQueryRequest sreq = new AiSavedQueryRequest();
                    sreq.setPath(q.path);
                    return aiQueryResource.executeSaved(sreq);
                }
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("status", "VALIDATION_ERROR", "error", "Tile has no runnable query"))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            });
        } catch (RuntimeException e) {
            log.warn("share-view tile query failed for {}", tileId, e);
            return Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Tile query failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /* --------------------------- helpers ---------------------------- */

    private Dashboard loadDashboard(ShareGuestDetails g) {
        // Read the dashboard file as the owner — the token authorises viewing
        // exactly this one dashboard, so the owner's ACL is the right scope.
        String raw;
        try {
            raw = datasourceService.getFileData(g.dashboardPath, g.ownerUser, g.ownerRoles);
        } catch (RuntimeException e) {
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Dashboard.class);
        } catch (Exception e) {
            log.error("shared dashboard {} is unparseable", g.dashboardPath, e);
            return null;
        }
    }

    /** The guest details the filter pinned to the request, or null if absent. */
    private ShareGuestDetails guest() {
        Authentication auth = SecurityContextHolder.getContext() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof ShareGuestDetails) {
            return (ShareGuestDetails) auth.getDetails();
        }
        return null;
    }

    private static Response invalid() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("status", "SHARE_INVALID", "error", "Share link is invalid or expired."))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
