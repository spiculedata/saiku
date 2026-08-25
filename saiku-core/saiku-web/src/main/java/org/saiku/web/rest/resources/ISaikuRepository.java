/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.saiku.repository.IRepositoryObject;

public interface ISaikuRepository {

    /**
     * Get Saved Queries.
     * @return A list of SavedQuery Objects.
     */
    @GET
    @Produces({"application/json"})
    List<IRepositoryObject> getRepository(@QueryParam("path") String path, @QueryParam("type") String type);

    /**
     * Load a resource.
     * @param file - The name of the repository file to load.
     * @return A Repository File Object.
     */
    @GET
    @Produces({"text/plain"})
    @Path("/resource")
    Response getResource(@QueryParam("file") String file);

    /**
     * Save a resource.
     * @param file - The name of the repository file to load.
     * @param content - The content to save.
     * @return Status
     */
    @POST
    @Path("/resource")
    Response saveResource(@FormParam("file") String file, @FormParam("content") String content);

    /**
     * Delete a resource.
     * @param file - The name of the repository file to load.
     * @return Status
     */
    @DELETE
    @Path("/resource")
    Response deleteResource(@QueryParam("file") String file);
}
