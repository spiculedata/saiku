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


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.FileUtil;
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

import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;


/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@Path("/saiku/{username}/repository2")
@XmlAccessorType(XmlAccessType.NONE)
public class BasicRepositoryResource2 implements ISaikuRepository {

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
			log.error("Error setting path for repository: " + path, e);
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
	
	/* (non-Javadoc)
	 * @see org.saiku.web.rest.resources.ISaikuRepository#getRepository(java.lang.String, java.lang.String)
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
			log.error("Error retrieving ACL for file: " + file, e);
		}
		throw new SaikuServiceException("You dont have permission to retrieve ACL for file: " + file);
	}
	
	
	@POST
	@Produces({"application/json" })
	@Path("/resource/acl")
	public Response setResourceAcl(@FormParam("file") String file, @FormParam("acl") String aclEntry) {
		try {
			if (file == null || file.startsWith("/") || file.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + file);
			}
			ObjectMapper mapper = new ObjectMapper();
			log.debug("Set ACL to " + file + " : " + aclEntry);
			AclEntry ae = mapper.readValue(aclEntry, AclEntry.class);
			String username = sessionService.getAllSessionObjects().get("username").toString();
			List<String> roles = (List<String> ) sessionService.getAllSessionObjects().get("roles");
			FileObject repoFile = acl.resolveFile(file);
			if (repoFile.exists() && acl.canGrant(file, username, roles) ) {
				acl.addEntry(file, ae);
				return Response.ok().build();
			}
			log.debug("Repo file does not exist or cannot grant access. repo file:" + repoFile + " - file: " + file);
		} catch (Exception e) {
			log.error("An error occured while setting permissions to file: " + file, e);
		}
		return Response.serverError().build();
	}


	/* (non-Javadoc)
	 * @see org.saiku.web.rest.resources.ISaikuRepository#getResource(java.lang.String)
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
				return Response.serverError().status(Status.FORBIDDEN).build();
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
	
	/* (non-Javadoc)
	 * @see org.saiku.web.rest.resources.ISaikuRepository#saveResource(java.lang.String, java.lang.String)
	 */
	@POST
	@Path("/resource")
	public Response saveResource (
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
				return Response.serverError().status(Status.FORBIDDEN)
							.entity("You don't have permissions to save here!")
								.type("text/plain").build();
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
			return Response.ok().build();
		} catch(Exception e){
			log.error("Cannot save resource to ( file: " + file + ")",e);
		}
		return Response.serverError().entity("Cannot save resource to ( file: " + file + ")").type("text/plain").build();
	}
	
	/* (non-Javadoc)
	 * @see org.saiku.web.rest.resources.ISaikuRepository#deleteResource(java.lang.String)
	 */
	@DELETE
	@Path("/resource")
	public Response deleteResource (
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
						return Response.ok().build();
					} else {
						return Response.serverError().status(Status.FORBIDDEN).build();
					} 
				}
		} catch(Exception e){
			log.error("Cannot save resource to (file: " + file + ")",e);
		}
		return Response.serverError().build();
	}
	
	/* (non-Javadoc)
	 * @see org.saiku.web.rest.resources.ISaikuRepository#saveResource(java.lang.String, java.lang.String)
	 */
	@POST
	@Path("/resource/move")
	public Response moveResource(@FormParam("source") String source, @FormParam("target") String target)
	{
		try {
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
		
	}
	
	@GET
	@Path("/zip")
	public Response getResourcesAsZip (
			@QueryParam("directory") String directory,
			@QueryParam("files") String files) 
	{
		try {
			if (StringUtils.isBlank(directory))
				return Response.ok().build();

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ZipOutputStream zos = new ZipOutputStream(bos);

			String[] fileArray = null;
			if (StringUtils.isBlank(files)) {
				FileObject dir = repo.resolveFile(directory);
				for (FileObject fo : dir.getChildren()) {
					if (fo.getType().equals(FileType.FILE)) {
						String entry = fo.getName().getBaseName();
						if ("saiku".equals(fo.getName().getExtension())) {
							byte[] doc = FileUtil.getContent(fo);
							ZipEntry ze = new ZipEntry(entry);
							zos.putNextEntry(ze);
							zos.write(doc);
						}
					}
				}
			} else {
				fileArray = files.split(",");
				for (String f : fileArray) {
					String resource = directory + "/" + f;
					Response r = getResource(resource);
					if (Status.OK.equals(Status.fromStatusCode(r.getStatus()))) {
						byte[] doc = (byte[]) r.getEntity();
						ZipEntry ze = new ZipEntry(f);
						zos.putNextEntry(ze);
						zos.write(doc);
					}
				}
			}
			zos.closeEntry();
			zos.close();
			byte[] zipDoc = bos.toByteArray();
			
			return Response.ok(zipDoc, MediaType.APPLICATION_OCTET_STREAM).header(
					"content-disposition",
					"attachment; filename = " + directory + ".zip").header(
							"content-length",zipDoc.length).build();
			
			
		} catch(Exception e){
			log.error("Cannot zip resources " + files ,e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return Response.serverError().entity(error).build();
		}

	}
	
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
	
	private List<IRepositoryObject> getRepositoryObjects(FileObject root, String fileType) throws Exception {
		List<IRepositoryObject> repoObjects = new ArrayList<IRepositoryObject>();
		FileObject[] objects = new FileObject[0];
		if (root.getType().equals(FileType.FOLDER)) {
			objects = root.getChildren();
		} else { 
			objects = new FileObject[]{ root };
		}
		
		
		for (FileObject file : objects) {
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
					Collections.sort(repoObjects, new Comparator<IRepositoryObject>() {

						public int compare(IRepositoryObject o1, IRepositoryObject o2) {
							if (o1.getType().equals(IRepositoryObject.Type.FOLDER) && o2.getType().equals(IRepositoryObject.Type.FILE))
								return -1;
							if (o1.getType().equals(IRepositoryObject.Type.FILE) && o2.getType().equals(IRepositoryObject.Type.FOLDER))
								return 1;
							return o1.getName().toLowerCase().compareTo(o2.getName().toLowerCase());
							
						}
						
					});
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
