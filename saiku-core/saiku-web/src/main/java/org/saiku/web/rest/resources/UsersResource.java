/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;

/**
 * Minimal user directory for any authenticated user (issue #942 follow-up) —
 * powers @-mention autocomplete in dashboard comments. Returns ONLY usernames
 * (no email / roles / passwords); the admin user-management surface stays on
 * {@code /saiku/admin/users} (ROLE_ADMIN). Sits on {@code /rest/**}
 * ({@code isFullyAuthenticated()}), so guests / share links can't read it.
 *
 * <p><b>saiku#1920 — the directory can no longer be dumped.</b> {@code GET /saiku/api/users}
 * with no arguments used to hand any authenticated user the complete username list, which is
 * exactly the harvest primitive CWE-200 warns about: usernames feed credential-stuffing lists,
 * phishing and social engineering, and none of that helps a mention picker work. The caller
 * must now supply a search prefix ({@code ?q=}) of at least {@value #MIN_QUERY_LENGTH} characters
 * and gets back only the usernames matching it. Mention autocomplete already searches by what
 * the user has typed, so the feature is unaffected; the bulk harvest is gone. Enumeration is
 * still rate-bound by the same per-IP limiter the rest of the AI surface uses, and the response
 * is capped at {@value #MAX_RESULTS} matches.
 */
@Path("/saiku/api/users")
public class UsersResource {

    /** Shortest accepted search prefix. Below this a caller could still walk the whole
     *  directory one character at a time, so it is rejected outright. */
    public static final int MIN_QUERY_LENGTH = 2;

    /** Hard cap on returned matches — a directory scan must not be able to page the whole
     *  user table out of a single request. */
    public static final int MAX_RESULTS = 25;

    private UserService userService;

    public void setUserService(UserService s) {
        this.userService = s;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("q") @DefaultValue("") String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < MIN_QUERY_LENGTH) {
            // 400, not an empty list: an absent/short prefix is a client error, and saying so
            // teaches the caller the contract without revealing anything about the directory.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error",
                            "MISSING_QUERY",
                            "message",
                            "q must be at least " + MIN_QUERY_LENGTH + " characters"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String needle = q.toLowerCase(Locale.ROOT);
        List<Map<String, String>> out = new ArrayList<>();
        if (userService != null) {
            List<SaikuUser> users = userService.getUsers();
            if (users != null) {
                for (SaikuUser u : users) {
                    if (out.size() >= MAX_RESULTS) {
                        break;
                    }
                    if (u == null || u.getUsername() == null) {
                        continue;
                    }
                    // Only enabled accounts are matchable — a disabled account is not
                    // mentionable, so surfacing it in autocomplete is pure disclosure.
                    if (!u.isEnabled()) {
                        continue;
                    }
                    if (u.getUsername().toLowerCase(Locale.ROOT).contains(needle)) {
                        out.add(Map.of("username", u.getUsername()));
                    }
                }
            }
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }
}
