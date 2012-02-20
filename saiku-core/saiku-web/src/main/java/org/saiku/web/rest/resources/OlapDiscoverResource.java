/*
 * Copyright (C) 2011 OSBI Ltd
 *
 * This program is free software; you can redistribute it and/or modify it 
 * under the terms of the GNU General Public License as published by the Free 
 * Software Foundation; either version 2 of the License, or (at your option) 
 * any later version.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along 
 * with this program; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 *
 */
package org.saiku.web.rest.resources;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.saiku.olap.dto.SaikuConnection;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
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
     * Returns the datasources available.
     * @throws SQLException 
     */
    @GET
    @Produces({"application/json" })
     public List<SaikuConnection> getConnections() {
    	try {
			return olapDiscoverService.getAllConnections();
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
			return new ArrayList<SaikuConnection>();
		}
    }

    @GET
    @Produces({"application/json" })
  	@Path("/refresh")
     public List<SaikuConnection> refreshConnections() {
    	try {
    		olapDiscoverService.refreshAllConnections();
			return olapDiscoverService.getAllConnections();
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
			return new ArrayList<SaikuConnection>();
		}
    }
    
	@GET
    @Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/dimensions")
     public List<SaikuDimension> getDimensions(
    		 @PathParam("connection") String connectionName, 
    		 @PathParam("catalog") String catalogName, 
    		 @PathParam("schema") String schemaName, 
    		 @PathParam("cube") String cubeName) 
    {
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getAllDimensions(cube);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return new ArrayList<SaikuDimension>();
	}
	
	@GET
    @Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}")
     public SaikuDimension getDimension(
    		 @PathParam("connection") String connectionName, 
    		 @PathParam("catalog") String catalogName, 
    		 @PathParam("schema") String schemaName, 
    		 @PathParam("cube") String cubeName,
    		 @PathParam("dimension") String dimensionName) 
    {
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getDimension(cube, dimensionName);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return null;
	}
	
	@GET
    @Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}/hierarchies")
     public List<SaikuHierarchy> getDimensionHierarchies(@PathParam("connection") String connectionName, 
    		 									@PathParam("catalog") String catalogName, 
    		 									@PathParam("schema") String schemaName, 
    		 									@PathParam("cube") String cubeName, 
    		 									@PathParam("dimension") String dimensionName) {
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getAllDimensionHierarchies(cube, dimensionName);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return new ArrayList<SaikuHierarchy>();
	}
	
	@GET
	@Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}/hierarchies/{hierarchy}/levels")
	public List<SaikuLevel> getHierarchy(@PathParam("connection") String connectionName, 
				@PathParam("catalog") String catalogName, 
				@PathParam("schema") String schemaName, 
				@PathParam("cube") String cubeName, 
				@PathParam("dimension") String dimensionName, 
				@PathParam("hierarchy") String hierarchyName)
	{
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getAllHierarchyLevels(cube, dimensionName, hierarchyName);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return new ArrayList<SaikuLevel>();
	}

	/**
	 * Get level information.
	 * @return 
	 */
	@GET
	@Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/dimensions/{dimension}/hierarchies/{hierarchy}/levels/{level}")
	public List<SaikuMember> getLevelMembers(
			@PathParam("connection") String connectionName, 
			@PathParam("catalog") String catalogName, 
			@PathParam("schema") String schemaName, 
			@PathParam("cube") String cubeName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("hierarchy") String hierarchyName,
			@PathParam("level") String levelName)
	{
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getLevelMembers(cube, dimensionName, hierarchyName, levelName);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return new ArrayList<SaikuMember>();
	}
   
	/**
	 * Get root member of that hierarchy.
	 * @return 
	 */
	@GET
	@Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/hierarchies/{hierarchy}/rootmembers")
	public List<SaikuMember> getRootMembers(
			@PathParam("connection") String connectionName, 
			@PathParam("catalog") String catalogName, 
			@PathParam("schema") String schemaName, 
			@PathParam("cube") String cubeName, 
			@PathParam("hierarchy") String hierarchyName)
		{
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getHierarchyRootMembers(cube, hierarchyName);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return null;
	}

	
	@GET
	@Path("/{connection}/{catalog}/{schema}/{cube}/hierarchies/")
    @Produces({"application/json" })
     public List<SaikuHierarchy> getCubeHierarchies(@PathParam("connection") String connectionName, 
    		 									@PathParam("catalog") String catalogName, 
    		 									@PathParam("schema") String schemaName, 
    		 									@PathParam("cube") String cubeName) {
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getAllHierarchies(cube);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return new ArrayList<SaikuHierarchy>();
	}
	
	@GET
	@Path("/{connection}/{catalog}/{schema}/{cube}/measures/")
    @Produces({"application/json" })
     public List<SaikuMember> getCubeMeasures(@PathParam("connection") String connectionName, 
    		 									@PathParam("catalog") String catalogName, 
    		 									@PathParam("schema") String schemaName, 
    		 									@PathParam("cube") String cubeName) {
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getMeasures(cube);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return new ArrayList<SaikuMember>();
	}
	
	/**
	 * Get all info for given member
	 * @return 
	 */
	@GET
	@Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/member/{member}")
	public SaikuMember getMember(
			@PathParam("connection") String connectionName, 
			@PathParam("catalog") String catalogName, 
			@PathParam("schema") String schemaName, 
			@PathParam("cube") String cubeName, 
			@PathParam("member") String memberName)
	{
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getMember(cube, memberName);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return null;
	}
	
	/**
	 * Get child members of given member
	 * @return 
	 */
	@GET
	@Produces({"application/json" })
	@Path("/{connection}/{catalog}/{schema}/{cube}/member/{member}/children")
	public List<SaikuMember> getMemberChildren(
			@PathParam("connection") String connectionName, 
			@PathParam("catalog") String catalogName, 
			@PathParam("schema") String schemaName, 
			@PathParam("cube") String cubeName, 
			@PathParam("member") String memberName)
	{
		if ("null".equals(schemaName)) {
			schemaName = "";
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		try {
			return olapDiscoverService.getMemberChildren(cube, memberName);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
		}
		return new ArrayList<SaikuMember>();
	}

}
