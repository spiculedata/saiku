/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.sqlworkbench;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Objects;
import org.saiku.service.sqlworkbench.SqlWorkbenchException;
import org.saiku.service.sqlworkbench.SqlWorkbenchService;
import org.saiku.service.user.UserService;

/**
 * REST surface for the SQL workbench (saiku#1107 phase 1) — a Monaco-backed, read-only SQL runner
 * against a datasource's underlying JDBC connection, gated to the {@code ROLE_SQL_EXEC} role the
 * issue introduces (admins get it too). Enforced both here ({@code @RolesAllowed}, Jersey's
 * RolesAllowedDynamicFeature) and at the Spring Security URL layer
 * ({@code /rest/saiku/sql-workbench/**} in {@code applicationContext-saiku.xml}) — defense in
 * depth, the same posture the {@code /admin/**} resources use.
 *
 * <p>Registered as a Spring bean in {@code saiku-beans.xml}; the Jersey application auto-scans
 * {@code @Path} beans. {@code userService} is optional so the constructor stays test-friendly —
 * headless callers fall back to an empty role set / "unknown" username.
 */
@Path("/saiku/sql-workbench")
@RolesAllowed({"ROLE_ADMIN", "ROLE_SQL_EXEC"})
public class SqlWorkbenchResource {

    private final SqlWorkbenchService service;
    private UserService userService;

    public SqlWorkbenchResource(SqlWorkbenchService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** Body for {@code POST /query}. {@code maxRows} is optional and clamped server-side. */
    public record QueryRequest(String datasource, String sql, Integer maxRows) {}

    /** Machine-readable error envelope — {@link SqlWorkbenchException#getCode()} + its safe message. */
    public record ErrorBody(String code, String message) {}

    /** Datasources the current user's roles can see — the same picker the cube UI offers, filtered
     *  to what {@link org.saiku.service.datasource.DatasourceService} already permits. */
    @GET
    @Path("/datasources")
    @Produces(MediaType.APPLICATION_JSON)
    public List<SqlWorkbenchService.DatasourceView> datasources() {
        return service.listDatasources(currentUserRoles());
    }

    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response query(QueryRequest req) {
        if (req == null || req.sql() == null || req.sql().isBlank()) {
            return badRequest("INVALID_REQUEST", "'sql' is required");
        }
        if (req.datasource() == null || req.datasource().isBlank()) {
            return badRequest("INVALID_REQUEST", "'datasource' is required");
        }
        try {
            SqlWorkbenchService.SqlQueryResult result =
                    service.execute(currentUsername(), req.datasource(), req.sql(), req.maxRows());
            return Response.ok(result).build();
        } catch (SqlWorkbenchException e) {
            return badRequest(e.getCode().name(), e.getMessage());
        }
    }

    private static Response badRequest(String code, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorBody(code, message))
                .build();
    }

    private String[] currentUserRoles() {
        return userService == null ? new String[0] : userService.getCurrentUserRoles();
    }

    private String currentUsername() {
        return userService == null ? "unknown" : userService.getActiveUsername();
    }
}
