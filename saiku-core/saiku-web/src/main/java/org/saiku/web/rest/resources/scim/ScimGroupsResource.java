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
import org.saiku.web.scim.ScimGroup;
import org.saiku.web.scim.ScimListResponse;
import org.saiku.web.scim.ScimPatchRequest;
import org.saiku.web.scim.ScimResponses;
import org.saiku.web.scim.ScimSchemas;
import org.saiku.web.scim.ScimService;

/**
 * SCIM 2.0 {@code /Groups} resource (issue #1438) at {@code /rest/scim/v2/Groups}.
 *
 * <p>A group's {@code displayName} is the Saiku role name granted to every member, and
 * {@code members} is authoritative: a membership change adds or removes that role in
 * {@code USER_ROLES}. Deleting a group revokes the role from all members first, so a group
 * removed in the IdP cannot leave a stale grant behind.
 */
@Path(ScimSchemas.BASE_PATH + "/Groups")
@Produces(ScimResponses.SCIM_JSON)
// Connectors overwhelmingly send application/scim+json, but several send plain
// application/json; both are accepted so a well-formed call is never a 415.
@Consumes({ScimResponses.SCIM_JSON, jakarta.ws.rs.core.MediaType.APPLICATION_JSON})
public class ScimGroupsResource {

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
        ScimListResponse page = scimService.listGroups(filter, startIndex, count);
        return ScimResponses.list(page);
    }

    @GET
    @Path("{id}")
    public Response get(@PathParam("id") String id) {
        ScimResponses.requireScimPrincipal();
        return ScimResponses.ok(scimService.getGroup(id));
    }

    @POST
    public Response create(ScimGroup group) {
        ScimResponses.requireScimPrincipal();
        ScimGroup created = scimService.createGroup(group);
        return ScimResponses.created(created, ScimSchemas.BASE_PATH + "/Groups/" + created.id);
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id, ScimGroup group) {
        ScimResponses.requireScimPrincipal();
        return ScimResponses.ok(scimService.replaceGroup(id, group));
    }

    @PATCH
    @Path("{id}")
    public Response patch(@PathParam("id") String id, ScimPatchRequest body) {
        ScimResponses.requireScimPrincipal();
        return ScimResponses.ok(scimService.patchGroup(id, body));
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        ScimResponses.requireScimPrincipal();
        scimService.deleteGroup(id);
        return ScimResponses.noContent();
    }
}
