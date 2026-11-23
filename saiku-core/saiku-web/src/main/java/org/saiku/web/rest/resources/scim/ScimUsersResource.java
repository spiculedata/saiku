/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.scim;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.saiku.web.scim.ScimListResponse;
import org.saiku.web.scim.ScimPatchRequest;
import org.saiku.web.scim.ScimResponses;
import org.saiku.web.scim.ScimSchemas;
import org.saiku.web.scim.ScimService;
import org.saiku.web.scim.ScimUser;

/**
 * SCIM 2.0 {@code /Users} resource (issue #1438), mounted at {@code /rest/scim/v2/Users} — parallel
 * to {@code /rest/saiku/api/*} and, like it, behind the container's {@code /rest/*} servlet
 * mapping.
 *
 * <p>Every method is idempotent in the SCIM sense: PUT replaces, PATCH merges, and
 * {@code DELETE} deactivates ({@code active=false}) rather than deleting the row, so an IdP that
 * re-sends a deprovision never destroys a person's query history and ACL ownership.
 *
 * <p>Auth is the SCIM bearer resolved by {@code ScimAuthFilter} on the way in; the resources
 * re-check the principal so a misrouted request fails closed.
 */
@Path(ScimSchemas.BASE_PATH + "/Users")
@Produces(ScimResponses.SCIM_JSON)
// Connectors overwhelmingly send application/scim+json, but several send plain
// application/json; both are accepted so a well-formed call is never a 415.
@Consumes({ScimResponses.SCIM_JSON, jakarta.ws.rs.core.MediaType.APPLICATION_JSON})
public class ScimUsersResource {

    private ScimService scimService;

    public void setScimService(ScimService scimService) {
        this.scimService = scimService;
    }

    @GET
    public Response list(
            @QueryParam("filter") String filter,
            @QueryParam("startIndex") @DefaultValue("1") int startIndex,
            @QueryParam("count") @DefaultValue("0") int count) {
        ScimResponses.requireScimPrincipal();
        ScimListResponse page = scimService.listUsers(filter, startIndex, count);
        return ScimResponses.list(page);
    }

    @GET
    @Path("{id}")
    public Response get(@PathParam("id") String id) {
        ScimResponses.requireScimPrincipal();
        return ScimResponses.ok(scimService.getUser(id));
    }

    @POST
    public Response create(ScimUser user) {
        ScimResponses.requireScimPrincipal();
        ScimUser created = scimService.createUser(user);
        return ScimResponses.created(created, ScimSchemas.BASE_PATH + "/Users/" + created.id);
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id, ScimUser user) {
        ScimResponses.requireScimPrincipal();
        return ScimResponses.ok(scimService.replaceUser(id, user));
    }

    @PATCH
    @Path("{id}")
    public Response patch(@PathParam("id") String id, ScimPatchRequest body) {
        ScimResponses.requireScimPrincipal();
        return ScimResponses.ok(scimService.patchUser(id, body));
    }

    @DELETE
    @Path("{id}")
    public Response deactivate(@PathParam("id") String id) {
        ScimResponses.requireScimPrincipal();
        scimService.deactivateUser(id);
        return ScimResponses.noContent();
    }
}
