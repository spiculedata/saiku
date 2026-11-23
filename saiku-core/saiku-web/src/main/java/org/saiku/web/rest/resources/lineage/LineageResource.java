/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.lineage;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.saiku.service.user.UserService;

/**
 * saiku#1120 Phase 1 — {@code GET /saiku/admin/lineage?measure=[Measures].[Store Sales]} lists
 * every dashboard, saved query and calculated measure that references the given measure /
 * dimension / hierarchy / level unique name. Admin-only: this walks the whole repository
 * regardless of per-file ACLs, since "what depends on this measure" is a governance question, not
 * a per-user one.
 */
@Path("/saiku/admin/lineage")
@RolesAllowed("ROLE_ADMIN")
public class LineageResource {

    private MeasureLineageService lineageService;
    private UserService userService;

    public void setLineageService(MeasureLineageService lineageService) {
        this.lineageService = lineageService;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response find(@QueryParam("measure") String measure) {
        if (measure == null || measure.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("status", "VALIDATION_ERROR", "error", "measure query param is required"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String username = userService.getActiveUsername();
        List<String> roles = Arrays.asList(userService.getCurrentUserRoles());
        List<LineageDependent> dependents = lineageService.findDependents(measure, username, roles);
        return Response.ok(dependents).type(MediaType.APPLICATION_JSON).build();
    }
}
