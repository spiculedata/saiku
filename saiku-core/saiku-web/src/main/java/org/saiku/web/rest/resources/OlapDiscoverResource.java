/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.resources;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.olap.dto.*;
import org.saiku.service.olap.OlapDiscoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/saiku/{username}/discover")
public class OlapDiscoverResource implements Serializable {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    private OlapDiscoverService olapDiscoverService;

    private static final Logger log = LoggerFactory.getLogger(OlapDiscoverResource.class);

    public void setOlapDiscoverService(OlapDiscoverService olapds) {
        olapDiscoverService = olapds;
    }

    /**
     * Returns the org.saiku.datasources available.
     * @summary Get datasources.
     */
    @GET
    @Produces({"application/json"})
    public List<SaikuConnection> getConnections() {
        try {
            return olapDiscoverService.getAllConnections();
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Returns the org.saiku.datasources available.
     * @summary Get connections by connectionName.
     * @param connectionName The connection name
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}")
    public List<SaikuConnection> getConnections(@PathParam("connection") String connectionName) {
        try {
            List<SaikuConnection> conns = olapDiscoverService.getConnection(connectionName);
            // saiku#867: empty result here means the underlying service
            // swallowed a SaikuOlapException ("Cannot find connection: ...")
            // — the SPA / agents can't tell "no connection by this name"
            // from "connection has no schemas" when both return []. Surface
            // the missing-name case as a typed 404 envelope.
            if (conns == null || conns.isEmpty()) {
                throw notFound("connection", connectionName, "Cannot find connection: '" + connectionName + "'", null);
            }
            return conns;
        } catch (WebApplicationException pass) {
            throw pass;
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            throw notFound("connection", connectionName, "Cannot find connection: '" + connectionName + "'", e);
        }
    }

    /**
     * Refresh the Saiku connections.
     * @Summary Refresh connections.
     * @return The existing connections.
     */
    @GET
    @Produces({"application/json"})
    @Path("/refresh")
    public List<SaikuConnection> refreshConnections() {
        try {
            olapDiscoverService.refreshAllConnections();
            return olapDiscoverService.getAllConnections();
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Refresh a specific connection by connection name.
     * @summary Refresh connection.
     * @param connectionName The connection name.
     * @return A List of available connections.
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/refresh")
    public List<SaikuConnection> refreshConnection(@PathParam("connection") String connectionName) {
        try {
            olapDiscoverService.refreshConnection(connectionName);
            return olapDiscoverService.getConnection(connectionName);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Get Cube Metadata
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @return A metadata object.
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/metadata")
    public SaikuCubeMetadata getMetadata(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            List<SaikuDimension> dimensions = olapDiscoverService.getAllDimensions(cube);
            List<SaikuMember> measures = olapDiscoverService.getMeasures(cube);
            Map<String, Object> properties = olapDiscoverService.getProperties(cube);
            // saiku#867: empty dimensions+measures indicates the cube isn't
            // resolvable (the service swallows the lookup and returns []s).
            // Surface as 404 rather than {dimensions:null, measures:null}.
            if ((dimensions == null || dimensions.isEmpty()) && (measures == null || measures.isEmpty())) {
                throw notFound(
                        "cube",
                        cubeName,
                        "Cube '" + cubeName + "' not found in " + connectionName + "/" + catalogName + "/" + schemaName,
                        null);
            }
            return new SaikuCubeMetadata(dimensions, measures, properties);
        } catch (WebApplicationException pass) {
            throw pass;
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            throw notFound("cube", cubeName, "Cube metadata lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build a {@link WebApplicationException} carrying the standard JSON
     * not-found envelope: {@code {error,field,available}}. saiku#867: replaces
     * silent 200-with-null-body / 204 No Content returns so clients can
     * distinguish "doesn't exist" from "exists but empty".
     */
    private static WebApplicationException notFound(String field, String value, String message, Throwable cause) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "NOT_FOUND");
        body.put("field", field);
        body.put("value", value);
        body.put("error", message);
        return new WebApplicationException(
                cause,
                Response.status(Response.Status.NOT_FOUND)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(body)
                        .build());
    }

    /**
     * Get the dimensions from a cube.
     * @Summary Get Dimensions
     * @param connectionName The connection name.
     * @param catalogName The catalog name.
     * @param schemaName The schema name.
     * @param cubeName The cube name.
     * @return A list of Dimensions.
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/dimensions")
    public List<SaikuDimension> getDimensions(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            return olapDiscoverService.getAllDimensions(cube);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return new ArrayList<>();
    }

    /**
     * Get a dimension from cube
     * @summary Get dimension
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @param dimensionName The dimension name
     * @return
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}")
    public SaikuDimension getDimension(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @PathParam("dimension") String dimensionName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            SaikuDimension dim = olapDiscoverService.getDimension(cube, dimensionName);
            // saiku#867: null return from the service means the dimension
            // isn't on the cube. Surface as 404 rather than the 204 the
            // JAX-RS null-mapper otherwise emits.
            if (dim == null) {
                throw notFound(
                        "dimension",
                        dimensionName,
                        "Dimension '" + dimensionName + "' not found on cube '" + cubeName + "'",
                        null);
            }
            return dim;
        } catch (WebApplicationException pass) {
            throw pass;
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            throw notFound("dimension", dimensionName, "Dimension lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get hierarchies from a dimension.
     * @summary Get Hierarchies
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @param dimensionName The dimension name
     * @return A list of hierarchies
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}/hierarchies")
    public List<SaikuHierarchy> getDimensionHierarchies(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @PathParam("dimension") String dimensionName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            return olapDiscoverService.getAllDimensionHierarchies(cube, dimensionName);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return new ArrayList<>();
    }

    /**
     * Get a hierarchy
     * @summary Get a hierarchy.
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @param dimensionName The dimension name
     * @param hierarchyName The hierarchy name
     * @return A list of Saiku Levels
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}/hierarchies/{hierarchy}/levels")
    public List<SaikuLevel> getHierarchy(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @PathParam("dimension") String dimensionName,
            @PathParam("hierarchy") String hierarchyName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            return olapDiscoverService.getAllHierarchyLevels(cube, dimensionName, hierarchyName);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return new ArrayList<>();
    }

    /**
     * Get level information.
     * @summary Level information
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @param dimensionName The dimension name
     * @param hierarchyName The hierarchy name
     * @param levelName The level name
     * @return A list of level information.
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}/hierarchies/{hierarchy}/levels/{level}")
    public List<SimpleCubeElement> getLevelMembers(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @PathParam("dimension") String dimensionName,
            @PathParam("hierarchy") String hierarchyName,
            @PathParam("level") String levelName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }

        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);

        try {
            return olapDiscoverService.getLevelMembers(cube, hierarchyName, levelName);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return new ArrayList<>();
    }

    /**
     * Get root member of that hierarchy.
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @param hierarchyName The hierarchy name
     * @return A list of Saiku members
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/hierarchies/{hierarchy}/rootmembers")
    public List<SaikuMember> getRootMembers(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @PathParam("hierarchy") String hierarchyName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            return olapDiscoverService.getHierarchyRootMembers(cube, hierarchyName);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return null;
    }

    /**
     * Get Cube Hierachy Information
     * @summary Get Cube Hierarchies
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @return A list of Saiku Hierarchies
     */
    @GET
    @Path("/{connection}/{catalog}/{schema}/{cube}/hierarchies/")
    @Produces({"application/json"})
    public List<SaikuHierarchy> getCubeHierarchies(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            return olapDiscoverService.getAllHierarchies(cube);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return new ArrayList<>();
    }

    /**
     * Get Cube Measure Information
     * @summary Get Cube Measures
     * @param connectionName The connection name
     * @param catalogName The catalog name
     * @param schemaName The schema name
     * @param cubeName The cube name
     * @return A list of Saiku Members
     */
    @GET
    @Path("/{connection}/{catalog}/{schema}/{cube}/measures/")
    @Produces({"application/json"})
    public List<SaikuMember> getCubeMeasures(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @QueryParam("includeHidden") @DefaultValue("false") boolean includeHidden) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            List<SaikuMember> measures = olapDiscoverService.getMeasures(cube);
            // saiku#778: analyst UIs get visible-only by default; admin tooling
            // passes includeHidden=true to inspect helper measures the schema
            // author marked visible="false". Server-side filtering so a
            // misconfigured client can't accidentally show what it shouldn't.
            if (!includeHidden && measures != null) {
                List<SaikuMember> filtered = new ArrayList<>(measures.size());
                for (SaikuMember m : measures) {
                    // Null → treat as visible (legacy fixtures / pre-#778 data).
                    if (m.isVisible() == null || m.isVisible()) {
                        filtered.add(m);
                    }
                }
                return filtered;
            }
            return measures;
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return new ArrayList<>();
    }

    /**
     * Get all info for given member
     * @summary Get Member Information
     * @param catalogName The catalog name
     * @param connectionName The connection name
     * @param cubeName The cube name
     * @param memberName The member name
     * @param schemaName The schema name
     * @return  A Saiku Member
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/member/{member}")
    public SaikuMember getMember(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @PathParam("member") String memberName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            return olapDiscoverService.getMember(cube, memberName);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return null;
    }

    /**
     * Get child members of given member.
     * @summary Get child members
     * @param connectionName The connection name
     * @param schemaName The schema name
     * @param catalogName The catalog name
     * @param memberName The member name
     * @param cubeName The cube name
     * @return A list of Saiku Members
     */
    @GET
    @Produces({"application/json"})
    @Path("/{connection}/{catalog}/{schema}/{cube}/member/{member}/children")
    public List<SaikuMember> getMemberChildren(
            @PathParam("connection") String connectionName,
            @PathParam("catalog") String catalogName,
            @PathParam("schema") String schemaName,
            @PathParam("cube") String cubeName,
            @PathParam("member") String memberName) {
        if ("null".equals(schemaName)) {
            schemaName = "";
        }
        SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
        try {
            return olapDiscoverService.getMemberChildren(cube, memberName);
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
        }
        return new ArrayList<>();
    }
}
