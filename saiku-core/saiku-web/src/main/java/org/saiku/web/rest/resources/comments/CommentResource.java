/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.comments;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.saiku.service.comments.Comment;
import org.saiku.service.comments.CommentService;
import org.saiku.service.user.UserService;
import org.saiku.web.service.SessionService;

/**
 * Per-tile dashboard comments (issue #942), under {@code /saiku/api/dashboards/comments}.
 * Sits on {@code /rest/**} = {@code isFullyAuthenticated()}, so a #941 share-link
 * guest ({@code ROLE_SHARE_GUEST}, which only unlocks {@code /share/view/**})
 * cannot reach it. Every operation is gated on the caller's ability to READ the
 * dashboard (the #940 ACL); deleting a comment additionally requires being its
 * author or an admin.
 */
@Path("/saiku/api/dashboards/comments")
public class CommentResource {

    private CommentService commentService;
    private SessionService sessionService;
    private UserService userService;

    public void setCommentService(CommentService s) {
        this.commentService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setUserService(UserService s) {
        this.userService = s;
    }

    public static class CommentRequest {
        public String dashboard;
        public String tile;
        public String body;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("dashboard") String dashboard, @QueryParam("tile") String tile) {
        if (dashboard == null || tile == null) {
            return badRequest("dashboard+tile required");
        }
        if (!commentService.canReadDashboard(dashboard, currentUsername(), currentRoles())) {
            return forbidden();
        }
        return Response.ok(commentService.list(dashboard, tile))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response add(CommentRequest req) {
        if (req == null || req.dashboard == null || req.tile == null || req.body == null || req.body.isBlank()) {
            return badRequest("dashboard, tile and non-empty body required");
        }
        String username = currentUsername();
        if (!commentService.canReadDashboard(req.dashboard, username, currentRoles())) {
            return forbidden();
        }
        Comment c = commentService.add(req.dashboard, req.tile, username, req.body);
        return Response.ok(c).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/{commentId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("commentId") String commentId, @QueryParam("dashboard") String dashboard) {
        if (dashboard == null) {
            return badRequest("dashboard required");
        }
        String username = currentUsername();
        if (!commentService.canReadDashboard(dashboard, username, currentRoles())) {
            return forbidden();
        }
        Comment c = commentService.findById(dashboard, commentId);
        if (c == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "Unknown comment"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        boolean canDelete = (username != null && username.equals(c.author)) || isAdmin(currentRoles());
        if (!canDelete) {
            return forbidden();
        }
        commentService.softDelete(dashboard, commentId);
        return Response.ok(Map.of("status", "OK"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /* --------------------------- helpers ---------------------------- */

    private boolean isAdmin(List<String> roles) {
        if (roles == null || userService == null) {
            return false;
        }
        List<String> admin = userService.getAdminRoles();
        if (admin == null) {
            return false;
        }
        for (String r : roles) {
            if (admin.contains(r)) {
                return true;
            }
        }
        return false;
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
}
