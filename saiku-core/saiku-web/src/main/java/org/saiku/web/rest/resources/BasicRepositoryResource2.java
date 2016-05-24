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


import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.VFS;

import org.saiku.repository.AclEntry;
import org.saiku.repository.IRepositoryObject;
import org.saiku.service.ISessionService;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.util.exception.SaikuServiceException;

import com.qmino.miredot.annotations.ReturnType;
import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.ws.rs.Consumes;
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

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@Path("/saiku/api/repository")
public class BasicRepositoryResource2 implements ISaikuRepository {

  private static final Logger log = LoggerFactory.getLogger(BasicRepositoryResource2.class);

  private ISessionService sessionService;

  //private Acl acl;
  private DatasourceService datasourceService;
  private FileObject repo;

  public void setDatasourceService(DatasourceService ds) {
	datasourceService = ds;
  }

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
	  log.error("Error setting path for repository: " + path, e);
	}
  }
	
	/*public void setAcl(Acl acl) {
		this.acl = acl;
	}*/

  /**
   * Sets the sessionService
   * @summary Set the session service
   * @param sessionService The session service
   */
  public void setSessionService(ISessionService sessionService){
	this.sessionService = sessionService;
  }

  /* (non-Javadoc)
 * @see org.saiku.web.rest.resources.ISaikuRepository#getRepository(java.lang.String, java.lang.String)
 */
  @GET
  @Produces({"application/json" })
  public List<IRepositoryObject> getRepository (
	  @QueryParam("path") String path,
	  @QueryParam("type") String type)
  {
		if (sessionService == null || sessionService.getAllSessionObjects() == null || sessionService.getAllSessionObjects().get("username") == null) {
			return new ArrayList<IRepositoryObject>();
		}

		String username = sessionService.getAllSessionObjects().get("username").toString();
		List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
		String[] t = type.split(",");
		List<IRepositoryObject> l = new ArrayList<>();
	  List<IRepositoryObject> l2;

	  if(path == null) {
			l = (datasourceService.getFiles(Arrays.asList(t), username, roles));
	  } else{
			l = (datasourceService.getFiles(Arrays.asList(t), username, roles, path));
	  }

		return l;
  }


  /**
   * Get the ACL information for a given resource.
   * @summary Get ACL information.
   * @param file The file object
   * @return An AclEntry Object.
   */
  @GET
  @Produces({"application/json" })
  @Path("/resource/acl")
  @ReturnType("org.saiku.repository.AclEntry")
  public AclEntry getResourceAcl(@QueryParam("file") String file) {
	try {
	  String username = sessionService.getAllSessionObjects().get("username").toString();
	  List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
	  return datasourceService.getResourceACL(file, username, roles);

	} catch (Exception e) {
	  log.error("Error retrieving ACL for file: " + file, e);
	}
	throw new SaikuServiceException("You dont have permission to retrieve ACL for file: " + file);


  }


  /**
   * Set the ACL information for a file/folder.
   * @summary Set the ACL information
   * @param file The file you want to change
   * @param aclEntry The ACL information.
   * @return A response 200.
   */
  @POST
  @Produces({"application/json" })
  @Path("/resource/acl")
  public Response setResourceAcl(@FormParam("file") String file, @FormParam("acl") String aclEntry) {
	try {
	  String username = sessionService.getAllSessionObjects().get("username").toString();
	  List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
	  datasourceService.setResourceACL(file, aclEntry, username, roles);
	  return Response.ok().build();

	  //log.debug("Repo file does not exist or cannot grant access. repo file:" + repoFile + " - file: " + file);
	} catch (Exception e) {
	  log.error("An error occured while setting permissions to file: " + file, e);
	}
	return Response.serverError().build();

  }


  /**
   * Get an object from the repository.
   * @summary Fetch from the repository.
   * @param file - The name of the repository file to load.
   * @return A response containing the file data.
   */
  @GET
  @Produces({"text/plain" })
  @Path("/resource")
  public Response getResource (@QueryParam("file") String file)
  {
	String username = sessionService.getAllSessionObjects().get("username").toString();
	List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");

	byte[] data = new byte[0];
	try {
	  data = datasourceService.getFileData(file, username, roles).getBytes("UTF-8");
	} catch (UnsupportedEncodingException e) {
	  log.error("Error reading file encoding",e);
	}
	return Response.ok(data, MediaType.TEXT_PLAIN).header(
		"content-length",data.length).build();
	/*
			if ( !acl.canRead(file, username, roles) ) {
				return Response.serverError().status(Status.FORBIDDEN).build();
			}
*/
  }

  /**
   * Save an object to the repository.
   * @summary Save object
   * @param file - The name of the repository file to load.
   * @param content - The content to save.
   * @return A response status 200.
   */
  @POST
  @Path("/resource")
  public Response saveResource (
	  @FormParam("file") String file,
	  @FormParam("content") String content)
  {
	String username = sessionService.getAllSessionObjects().get("username").toString();
	List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
	String resp = datasourceService.saveFile(content, file, username, roles);
	if(resp.equals("Save Okay")){
	  return Response.ok().build();
	}
	else{
	  return Response.serverError().entity("Cannot save resource to ( file: " + file + ")").type("text/plain").build();
	}
		/*
				return Response.serverError().status(Status.FORBIDDEN)
							.entity("You don't have permissions to save here!")
								.type("text/plain").build();
		*/
  }

  /**
   * Delete a resource from the repository
   * @param file - The name of the repository file to load.
   * @return a response status 200.
   */
  @DELETE
  @Path("/resource")
  public Response deleteResource (
	  @QueryParam("file") String file)
  {
	String username = sessionService.getAllSessionObjects().get("username").toString();
	List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
	String resp = datasourceService.removeFile(file, username, roles);
	if(resp.equals("Remove Okay")){
	  return Response.ok().build();
	}
	else{
	  return Response.serverError().entity("Cannot save resource to ( file: " + file + ")").type("text/plain").build();
	}

  }

  /**
   * Move an object within the repository.
   * @summary Move object.
   * @param source Source object
   * @param target Target location
   * @return A response status 200
   */
  @POST
  @Path("/resource/move")
  public Response moveResource(@FormParam("source") String source, @FormParam("target") String target)
  {
	String username = sessionService.getAllSessionObjects().get("username").toString();
	List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
	String resp = datasourceService.moveFile(source, target, username, roles);
	if(resp.equals("Move Okay")){
	  return Response.ok().entity("{}").build();
	}
	else{
	  return Response.serverError().entity("Cannot move resource to ( file: " + target + ")").type("text/plain").build();
	}



		/*try {
			if (source == null || source.startsWith("/") || source.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + source);
			}
			if (target == null || target.startsWith("/") || target.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + target);
			}
			
			String username = sessionService.getAllSessionObjects().get("username").toString();
			List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
			FileObject targetFile = repo.resolveFile(target);

			if ( !acl.canWrite(target,username, roles) ) {
				return Response.serverError().status(Status.FORBIDDEN)
							.entity("You don't have permissions to save here!")
								.type("text/plain").build();
			}

			if (targetFile == null) throw new Exception("Repo File not found");

			if (targetFile.exists()) {
				throw new Exception("Target file exists already. Cannot write: " + target);
			}
			
			FileObject sourceFile = repo.resolveFile(source);
			if ( !acl.canRead(source, username, roles) ) {
				return Response.serverError().status(Status.FORBIDDEN).entity("You don't have permissions to read the source file: " + source).build();
			}

			if (!sourceFile.exists()) {
				throw new Exception("Source file does not exist: " + source);
			}
			if (!sourceFile.canRenameTo(targetFile)) {
				throw new Exception("Cannot rename " + source + " to " + target);
			}
			sourceFile.moveTo(targetFile);
			return Response.ok().build();
		} catch(Exception e){
			log.error("Cannot move resource from " + source + " to " + target ,e);
			return Response.serverError().entity("Cannot move resource from " + source + " to " + target + " ( " + e.getMessage() + ")").type("text/plain").build();
		}
		*/
  }


  /**
   * Upload a zip archive to the server.
   * @param test Not used.
   * @param uploadedInputStream File Info
   * @param fileDetail File Info
   * @param directory Location
   * @return A response status 200
   */
  @POST
  @Path("/zipupload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  public Response uploadArchiveZip(
	  @QueryParam("test") String test,
	  @FormDataParam("file") InputStream uploadedInputStream,
	  @FormDataParam("file") FormDataContentDisposition fileDetail,
	  @FormDataParam("directory") String directory)
  {
	String zipFile = fileDetail.getFileName();
	String output = "";
	try {
	  if (StringUtils.isBlank(zipFile))
		throw new Exception("You must specify a zip file to upload");

	  output = "Uploding file: " + zipFile + " ...\r\n";
	  ZipInputStream zis = new ZipInputStream(uploadedInputStream);
	  ZipEntry ze = zis.getNextEntry();
	  byte[] doc = null;
	  boolean isFile = false;
	  if (ze == null) {
		doc = IOUtils.toByteArray(uploadedInputStream);
		isFile = true;
	  }
	  while (ze != null || doc != null) {
		String fileName = null;
		if (!isFile) {
		  fileName = ze.getName();
		  doc = IOUtils.toByteArray(zis);
		} else {
		  fileName = zipFile;
		}

		output += "Saving " + fileName + "... ";
		String fullPath = (StringUtils.isNotBlank(directory)) ? directory + "/" + fileName : fileName;

		String content = new String(doc);
		Response r = saveResource(fullPath, content);
		doc = null;

		if (Status.OK.getStatusCode() != r.getStatus()) {
		  output += " ERROR: " + r.getEntity().toString() + "\r\n";
		} else {
		  output += " OK\r\n";
		}
		if (!isFile)
		  ze = zis.getNextEntry();
	  }

	  if (!isFile) {
		zis.closeEntry();
		zis.close();
	  }
	  uploadedInputStream.close();

	  output += " SUCCESSFUL!\r\n";
	  return Response.ok(output).build();

	} catch(Exception e){
	  log.error("Cannot unzip resources " + zipFile ,e);
	  String error = ExceptionUtils.getRootCauseMessage(e);
	  return Response.serverError().entity(output + "\r\n" + error).build();
	}


  }

}
