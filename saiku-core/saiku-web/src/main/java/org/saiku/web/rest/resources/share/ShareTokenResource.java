/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.share;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.repository.AclEntry;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.security.Usernames;
import org.saiku.web.service.SessionService;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner-facing CRUD for dashboard share links (issue #941): mint, list, and
 * revoke. Everything here sits behind {@code isFullyAuthenticated()} — only a
 * logged-in user who can GRANT the target dashboard may mint a link for it, and
 * only the creator / an admin / someone who can still GRANT may revoke one. The
 * account-free guest VIEW path lives in {@link ShareViewResource}.
 */
@Path("/saiku/share")
public class ShareTokenResource {

    private static final Logger log = LoggerFactory.getLogger(ShareTokenResource.class);

    // #941 hardening: account-free public links get a conservative default and
    // a capped maximum lifetime so a leaked link can't grant access for a year.
    private static final long DEFAULT_TTL_HOURS = 72; // 3 days
    private static final long MAX_TTL_HOURS = 720; // 30 days
    private static final int MAX_ACTIVE_TOKENS_PER_USER = 200;

    private ShareTokenStore store;
    private DatasourceService datasourceService;
    private SessionService sessionService;
    private UserService userService;

    public void setStore(ShareTokenStore s) {
        this.store = s;
    }

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setUserService(UserService s) {
        this.userService = s;
    }

    public static class MintRequest {
        public String dashboardPath;
        public Integer ttlHours;
        public String label;
    }

    @POST
    @Path("/mint")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mint(MintRequest body) {
        if (body == null || body.dashboardPath == null || body.dashboardPath.isBlank()) {
            return badRequest("dashboardPath", "dashboardPath required");
        }
        String path = body.dashboardPath;
        if (!path.endsWith(".saikudash")) {
            return badRequest("dashboardPath", "must reference a .saikudash dashboard");
        }
        String username = currentUsername();
        List<String> roles = currentRoles();

        // Authorisation: only someone who can GRANT the dashboard may mint a
        // public link for it. getResourceACL returns null unless the caller
        // has GRANT (the #940 canGrant gate), so a non-null entry == allowed.
        AclEntry acl;
        try {
            acl = datasourceService.getResourceACL(path, username, roles);
        } catch (RuntimeException e) {
            acl = null;
        }
        if (acl == null) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("status", "FORBIDDEN", "error", "You don't have permission to share this dashboard"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        long ttlHours = body.ttlHours == null ? DEFAULT_TTL_HOURS : body.ttlHours;
        if (ttlHours <= 0) {
            return badRequest("ttlHours", "ttlHours must be positive");
        }
        ttlHours = Math.min(ttlHours, MAX_TTL_HOURS);

        long active = store.listByOwner(username).stream()
                .filter(t -> t.isValid(System.currentTimeMillis()))
                .count();
        if (active >= MAX_ACTIVE_TOKENS_PER_USER) {
            return Response.status(429)
                    .entity(Map.of("status", "LIMIT", "error", "Too many active share links; revoke some first"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        ShareToken t = store.create(path, username, roles, ttlHours * 3600_000L, body.label);
        log.info(
                "share-link minted token=<redacted:{}> dashboard={} by={} ttlHours={}",
                t.token.length(),
                path,
                username,
                ttlHours);
        // Token goes in the URL FRAGMENT (#) — fragments are never sent to the
        // server, proxies, access logs, or in the Referer header, so the secret
        // doesn't leak from the share link itself. The SPA reads location.hash
        // and replays it as the X-Saiku-Share-Token header (#941 hardening).
        return Response.ok(Map.of(
                        "status", "OK", "token", t.token, "url", "/ui/share#" + t.token, "expiresAt", t.expiresAt))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @GET
    @Path("/tokens")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("all") @DefaultValue("false") boolean all) {
        String username = currentUsername();
        List<ShareToken> tokens = (all && isAdmin(currentRoles())) ? store.listAll() : store.listByOwner(username);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ShareToken t : tokens) {
            // Return the token id (the owner already holds it) but never the
            // owner role snapshot — that's internal data-scope state.
            out.add(Map.of(
                    "token", t.token,
                    "dashboardPath", t.dashboardPath,
                    "createdBy", t.createdBy,
                    "createdAt", t.createdAt,
                    "expiresAt", t.expiresAt,
                    "revoked", t.revoked,
                    "label", t.label == null ? "" : t.label,
                    "active", t.isValid(System.currentTimeMillis())));
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/tokens/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response revoke(@PathParam("id") String id) {
        ShareToken t = store.load(id);
        if (t == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "Unknown share token"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        boolean canManage = Usernames.sameUser(username, t.createdBy)
                || isAdmin(roles)
                || stillCanGrant(t.dashboardPath, username, roles);
        if (!canManage) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("status", "FORBIDDEN", "error", "You can't revoke this share link"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        store.revoke(id);
        log.info("share-link revoked dashboard={} by={}", t.dashboardPath, username);
        return Response.ok(Map.of("status", "OK"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /* --------------------------- helpers ---------------------------- */

    private boolean stillCanGrant(String path, String username, List<String> roles) {
        try {
            return datasourceService.getResourceACL(path, username, roles) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isAdmin(List<String> roles) {
        if (roles == null || userService == null) return false;
        List<String> admin = userService.getAdminRoles();
        if (admin == null) return false;
        for (String r : roles) {
            if (admin.contains(r)) return true;
        }
        return false;
    }

    private String currentUsername() {
        if (sessionService == null) return null;
        Object u = sessionService.getAllSessionObjects().get("username");
        return u == null ? null : u.toString();
    }

    // saiku#1752: roles come from the single authoritative SecurityContextHolder reader, not the
    // lazily-seeded session "roles" map (see SessionRoles / saiku#1747). ROLE_SHARE_GUEST is carried
    // as a SecurityContextHolder authority on the guest principal, so it resolves here identically.
    private List<String> currentRoles() {
        return org.saiku.web.rest.util.SessionRoles.currentRoles();
    }

    private static Response badRequest(String field, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "field", field, "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
