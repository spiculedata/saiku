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


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.VFS;
import org.codehaus.jackson.map.ObjectMapper;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.acl.Acl;
import org.saiku.web.rest.objects.acl.AclEntry;
import org.saiku.web.rest.objects.acl.enumeration.AclMethod;
import org.saiku.web.rest.objects.repository.IRepositoryObject;
import org.saiku.web.rest.objects.repository.RepositoryFileObject;
import org.saiku.web.rest.objects.repository.RepositoryFolderObject;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@Path("/saiku/{username}/repository2")
@XmlAccessorType(XmlAccessType.NONE)
public class BasicRepositoryResource2 {

	private static final Logger log = LoggerFactory.getLogger(BasicRepositoryResource2.class);

	private FileObject repo;
	private SessionService sessionService;
	
	private Acl acl;

	public void setPath(String path) throws Exception {
		FileSystemManager fileSystemManager;
		try {
			 if (!path.endsWith("" + File.separatorChar)) {
				path += File.separatorChar;
			}
			fileSystemManager = VFS.getManager();
			FileObject fileObject;
			fileObject = fileSystemManager.resolveFile(path);
			if (fileObject == null) {
				throw new IOException("File cannot be resolved: " + path);
			}
			if(!fileObject.exists()) {
				throw new IOException("File does not exist: " + path);
			}
			repo = fileObject;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void setAcl(Acl acl) {
		this.acl = acl;
	}
	
	/**
	 * Sets the sessionService
	 * @param sessionService
	 */
	public void setSessionService(SessionService sessionService){
		this.sessionService = sessionService;
	}
	
	/**
	 * Get Saved Queries.
	 * @return A list of SavedQuery Objects.
	 */
	@GET
	@Produces({"application/json" })
	public List<IRepositoryObject> getRepository (
			@QueryParam("path") String path,
			@QueryParam("type") String type) 
	{
		List<IRepositoryObject> objects = new ArrayList<IRepositoryObject>();
		try {
			if (path != null && (path.startsWith("/") || path.startsWith("."))) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + path);
			}

			if (repo != null) {
				FileObject folder = repo;
				if (path != null) {
					folder = repo.resolveFile(path);
				} else {
					path = repo.getName().getRelativeName(folder.getName());
				}
				
				String username = sessionService.getAllSessionObjects().get("username").toString();
				List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
				
				//TODO : shall throw an exception ???
				if ( !acl.canRead(path,username, roles) ) {
					return new ArrayList<IRepositoryObject>(); // empty  
				} else {
					return getRepositoryObjects(folder, type);
				}
			}
			else {
				throw new Exception("repo URL is null");
			}
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
			e.printStackTrace();
		}
		return objects;
	}

	@GET
	@Produces({"application/json" })
	@Path("/resource/acl")
	public AclEntry getResourceAcl(@QueryParam("file") String file) {
		try {
			if (file == null || file.startsWith("/") || file.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + file);
			}
			String username = sessionService.getAllSessionObjects().get("username").toString();
			List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
			if (acl.canGrant(file, username, roles) ) {
				return getAcl(file);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new SaikuServiceException("Error retrieving ACL for file: " + file, e);
		}
		throw new SaikuServiceException("You dont have permission to retrieve ACL for file: " + file);
	}
	
	
	@POST
	@Produces({"application/json" })
	@Path("/resource/acl")
	public Status setResourceAcl(@FormParam("file") String file, @FormParam("acl") String aclEntry) {
		try {
			if (file == null || file.startsWith("/") || file.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + file);
			}
			ObjectMapper mapper = new ObjectMapper();
			AclEntry ae = mapper.readValue(aclEntry, AclEntry.class);
			String username = sessionService.getAllSessionObjects().get("username").toString();
			List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
			FileObject repoFile = repo.resolveFile(file);
			if (repoFile.exists() && acl.canGrant(file, username, roles) ) {
				acl.addEntry(file, ae);
				return Status.OK;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return Status.INTERNAL_SERVER_ERROR;
	}


	/**
	 * Load a resource.
	 * @param file - The name of the repository file to load.
	 * @param path - The path of the given file to load.
	 * @return A Repository File Object.
	 */
	@GET
	@Produces({"text/plain" })
	@Path("/resource")
	public Response getResource (@QueryParam("file") String file)
	{
		try {
			if (file == null || file.startsWith("/") || file.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + file);
			}
			String username = sessionService.getAllSessionObjects().get("username").toString();
			List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
			FileObject repoFile = repo.resolveFile(file);
			if ( !acl.canRead(file, username, roles) ) {
				return Response.status(Status.UNAUTHORIZED).build();
			}
//			System.out.println("path:" + repo.getName().getRelativeName(repoFile.getName()));
			if (repoFile.exists()) {
				InputStreamReader reader = new InputStreamReader(repoFile.getContent().getInputStream());
				BufferedReader br = new BufferedReader(reader);
				String chunk ="",content ="";
				while ((chunk = br.readLine()) != null) {
					content += chunk + "\n";
				}
				byte[] doc = content.getBytes("UTF-8");
				return Response.ok(doc, MediaType.TEXT_PLAIN).header(
								"content-length",doc.length).build();
			}
			else {
				throw new Exception("File does not exist:" + repoFile.getName().getPath());
			}
		} catch(FileNotFoundException e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		} catch(Exception e){
			log.error("Cannot load query (" + file + ")",e);
		}
		return Response.serverError().build();
	}
	
	/**
	 * Save a resource.
	 * @param file - The name of the repository file to load.
	 * @param path - The path of the given file to load.
	 * @param content - The content to save.
	 * @return Status
	 */
	@POST
	@Path("/resource")
	public Status saveResource (
			@FormParam("file") String file, 
			@FormParam("content") String content)
	{
		try {
			if (file == null || file.startsWith("/") || file.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + file);
			}

			String username = sessionService.getAllSessionObjects().get("username").toString();
			List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
			FileObject repoFile = repo.resolveFile(file);

			if ( !acl.canWrite(file,username, roles) ) {
				return Status.UNAUTHORIZED;
			}

			if (repoFile == null) throw new Exception("Repo File not found");

			if (repoFile.exists()) {
				repoFile.delete();
			}
			if (!StringUtils.isNotBlank(content)) {
				repoFile.createFolder();
			} else {
				repoFile.createFile();
				OutputStreamWriter ow = new OutputStreamWriter(repoFile.getContent().getOutputStream());
				BufferedWriter bw = new BufferedWriter(ow);
				bw.write(content);
				bw.close();
			}
			return Status.OK;
		} catch(Exception e){
			log.error("Cannot save resource to ( file: " + file + ")",e);
		}
		return Status.INTERNAL_SERVER_ERROR;
	}
	
	/**
	 * Delete a resource.
	 * @param file - The name of the repository file to load.
	 * @param path - The path of the given file to load.
	 * @return Status
	 */
	@DELETE
	@Path("/resource")
	public Status deleteResource (
			@QueryParam("file") String file)
	{
		try {
			if (file == null || file.startsWith("/") || file.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + file);
			}
	
			
			String username = sessionService.getAllSessionObjects().get("username").toString();
			List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
				FileObject repoFile = repo.resolveFile(file);

				if (repoFile != null && repoFile.exists() ) {
					if ( acl.canWrite(file, username, roles) ){
						repoFile.delete();
						return Status.OK;
					} else {
						return Status.UNAUTHORIZED;
					} 
				}
		} catch(Exception e){
			log.error("Cannot save resource to (file: " + file + ")",e);
		}
		return Status.INTERNAL_SERVER_ERROR;
	}
	
	private List<IRepositoryObject> getRepositoryObjects(FileObject root, String fileType) throws Exception {
		List<IRepositoryObject> repoObjects = new ArrayList<IRepositoryObject>();
		for (FileObject file : root.getChildren()) {
			if (!file.isHidden()) {
				String filename = file.getName().getBaseName();
				String relativePath = repo.getName().getRelativeName(file.getName());

				String username = sessionService.getAllSessionObjects().get("username").toString();
				List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
				if ( acl.canRead(relativePath,username, roles) ) {
					List<AclMethod> acls = acl.getMethods(relativePath, username, roles);
					if (file.getType().equals(FileType.FILE)) {
						if (StringUtils.isNotEmpty(fileType) && !filename.endsWith(fileType)) {
							continue;
						}
						String extension = file.getName().getExtension();

						repoObjects.add(new RepositoryFileObject(filename, "#" + relativePath, extension, relativePath, acls));
					}
					if (file.getType().equals(FileType.FOLDER)) { 
						repoObjects.add(new RepositoryFolderObject(filename, "#" + relativePath, relativePath, acls, getRepositoryObjects(file, fileType)));
					}
				}
			}
		}
		return repoObjects;
	}
	


	private AclEntry getAcl(String path){
		AclEntry entry = this.acl.getEntry(path);
		if ( entry == null ) entry = new AclEntry();
		return entry;
	}

}
