/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;

/**
 * Minimal user directory for any authenticated user (issue #942 follow-up) —
 * powers @-mention autocomplete in dashboard comments. Returns ONLY usernames
 * (no email / roles / passwords); the admin user-management surface stays on
 * {@code /saiku/admin/users} (ROLE_ADMIN). Sits on {@code /rest/**}
 * ({@code isFullyAuthenticated()}), so guests / share links can't read it.
 */
@Path("/saiku/api/users")
public class UsersResource {

    private UserService userService;

    public void setUserService(UserService s) {
        this.userService = s;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        List<Map<String, String>> out = new ArrayList<>();
        if (userService != null) {
            List<SaikuUser> users = userService.getUsers();
            if (users != null) {
                for (SaikuUser u : users) {
                    if (u != null && u.getUsername() != null) {
                        out.add(Map.of("username", u.getUsername()));
                    }
                }
            }
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }
}
