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
package org.saiku.plugin.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.pentaho.platform.api.engine.IAuthorizationPolicy;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.security.policy.rolebased.actions.AdministerSecurityAction;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.acl.enumeration.AclMethod;
import org.saiku.web.rest.objects.repository.IRepositoryObject;
import org.saiku.web.rest.objects.repository.RepositoryFileObject;
import org.saiku.web.rest.objects.repository.RepositoryFolderObject;
import org.saiku.web.rest.resources.ISaikuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import pt.webdetails.cpf.repository.api.FileAccess;
import pt.webdetails.cpf.repository.api.IBasicFile;
import pt.webdetails.cpf.repository.api.IBasicFileFilter;
import pt.webdetails.cpf.repository.api.IContentAccessFactory;
import pt.webdetails.cpf.repository.api.IUserContentAccess;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@Path("/saiku/api/{username}/repository")
@XmlAccessorType(XmlAccessType.NONE)
public class PentahoRepositoryResource2 implements ISaikuRepository {

	private static final Logger log = LoggerFactory.getLogger(PentahoRepositoryResource2.class);

	@Autowired
	private IContentAccessFactory contentAccessFactory;

	/**
	 * Get Saved Queries.
	 * @return A list of SavedQuery Objects.
	 */
	@GET
	@Produces({"application/json" })
	public List<IRepositoryObject> getRepository (
			final @QueryParam("path") String path,
			final @QueryParam("type") String type)  
	{
		List<IRepositoryObject> objects = new ArrayList<IRepositoryObject>();
		try {
//			if (path != null && (path.startsWith("/") || path.startsWith("."))) {
//				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + path);
//			}
			
			IUserContentAccess access = contentAccessFactory.getUserContentAccess("/");
			String root = (StringUtils.isBlank(path)) ? "/" : path;
			return getRepositoryObjects(access, root, type);
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
			e.printStackTrace();
		}
		return objects;
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
			if (StringUtils.isBlank(file)) {
				throw new IllegalArgumentException("Path cannot be null  - Illegal Path: " + file);
			}

			log.debug("Get repository file: " + file);

			IUserContentAccess access = contentAccessFactory.getUserContentAccess(null);

			if( !access.fileExists(file) && access.hasAccess(file, FileAccess.READ)) {
				log.error("Access to Repository has failed File does not exist: " + file);
				throw new NullPointerException("Access to Repository has failed");
			}
			IBasicFile bf = access.fetchFile(file);

			String doc = IOUtils.toString(bf.getContents());
			if (doc == null) {
				throw new SaikuServiceException("Error retrieving saiku document from solution repository: " + file); 
			}
			return Response.ok(doc.getBytes("UTF-8"), MediaType.TEXT_PLAIN).header(
					"content-length",doc.getBytes("UTF-8").length).build();

		}
		catch(Exception e){
			log.error("Cannot load file from repository (" + file + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
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
	public Response saveResource (
			@FormParam("file") String file, 
			@FormParam("content") String content)
	{
		try {
			if (StringUtils.isBlank(file)) {
				throw new IllegalArgumentException("Path cannot be null  - Illegal Path: " + file);
			}
			if (StringUtils.isBlank(content)) {
				throw new IllegalArgumentException("Cannot save empty file to: " + file);

			}
			log.debug("Save repository file: " + file);

			IUserContentAccess access = contentAccessFactory.getUserContentAccess(null);

			boolean ok = access.saveFile(file, IOUtils.toInputStream(content));
			if (!ok) {
				throw new SaikuServiceException("Failed to write file: " + file);
			}
			return Response.ok().build();
		}
		catch(Exception e){
			log.error("Cannot save file (" + file + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}

	}

	/**
	 * Delete a resource.
	 * @param file - The name of the repository file to load.
	 * @param path - The path of the given file to load.
	 * @return Status
	 */
	@DELETE
	@Path("/resource")
	public Response deleteResource (@QueryParam("file") String file) {
		try {
			if (StringUtils.isBlank(file)) {
				throw new IllegalArgumentException("Path cannot be null  - Illegal Path: " + file);
			}

			log.debug("Delete repository file: " + file);

			IUserContentAccess access = contentAccessFactory.getUserContentAccess(null);

			if( !access.fileExists(file) && access.hasAccess(file, FileAccess.DELETE)) {
				throw new SaikuServiceException("Access to Repository has failed File does not exist or no delete right: " + file);
			}
			boolean ok = access.deleteFile(file);
			if (!ok) {
				throw new SaikuServiceException("Failed to write file: " + file);
			}
			return Response.ok().build();
		}
		catch(Exception e){
			log.error("Cannot load file from repository (" + file + ")",e);
		}
		return Response.serverError().build();
	}	

//	@GET
//	@Path("/zip")
//	public Response getResourcesAsZip (
//			@QueryParam("directory") String directory,
//			@QueryParam("files") String files) 
//	{
//		try {
//			if (StringUtils.isBlank(directory))
//				return Response.ok().build();
//
//			IPentahoSession userSession = PentahoSessionHolder.getSession();
//			ISolutionRepository repository = PentahoSystem.get(ISolutionRepository.class, userSession);
//			ByteArrayOutputStream bos = new ByteArrayOutputStream();
//			ZipOutputStream zos = new ZipOutputStream(bos);
//
//			String[] fileArray = null;
//			if (StringUtils.isBlank(files)) {
//				ISolutionFile dir = repository.getSolutionFile(directory);
//				for (ISolutionFile fo : dir.listFiles()) {
//					if (!fo.isDirectory()) {
//						String entry = fo.getFileName();
//						if (".saiku".equals(fo.getExtension())) {
//							byte[] doc = fo.getData();
//							ZipEntry ze = new ZipEntry(entry);
//							zos.putNextEntry(ze);
//							zos.write(doc);
//						}
//					}
//				}
//			} else {
//				fileArray = files.split(",");
//				for (String f : fileArray) {
//					String resource = directory + "/" + f;
//					Response r = getResource(resource);
//					if (Status.OK.equals(Status.fromStatusCode(r.getStatus()))) {
//						byte[] doc = (byte[]) r.getEntity();
//						ZipEntry ze = new ZipEntry(f);
//						zos.putNextEntry(ze);
//						zos.write(doc);
//					}
//				}
//			}
//			zos.closeEntry();
//			zos.close();
//			byte[] zipDoc = bos.toByteArray();
//
//			return Response.ok(zipDoc, MediaType.APPLICATION_OCTET_STREAM).header(
//					"content-disposition",
//					"attachment; filename = " + directory + ".zip").header(
//							"content-length",zipDoc.length).build();
//
//
//		} catch(Exception e){
//			log.error("Cannot zip resources " + files ,e);
//			String error = ExceptionUtils.getRootCauseMessage(e);
//			return Response.serverError().entity(error).build();
//		}
//
//	}
//
//	@POST
//	@Path("/zipupload")
//	@Consumes(MediaType.MULTIPART_FORM_DATA)
//	public Response uploadArchiveZip(
//			@QueryParam("test") String test,
//			@FormDataParam("file") InputStream uploadedInputStream,
//			@FormDataParam("file") FormDataContentDisposition fileDetail, 
//			@FormDataParam("directory") String directory) 
//	{
//		String zipFile = fileDetail.getFileName();
//		String output = "";
//		try {
//			if (StringUtils.isBlank(zipFile))
//				throw new Exception("You must specify a zip file to upload");
//
//			output = "Uploding file: " + zipFile + " ...\r\n";
//			ZipInputStream zis = new ZipInputStream(uploadedInputStream);
//			ZipEntry ze = zis.getNextEntry();
//			byte[] doc = null;
//			boolean isFile = false;
//			if (ze == null) {
//				doc = IOUtils.toByteArray(uploadedInputStream);
//				isFile = true;
//			}
//			while (ze != null || doc != null) {
//				String fileName = null; 
//				if (!isFile) {
//					fileName = ze.getName();
//					doc = IOUtils.toByteArray(zis);
//				} else {
//					fileName = zipFile;
//				}
//
//				output += "Saving " + fileName + "... ";
//				String fullPath = (StringUtils.isNotBlank(directory)) ? directory + "/" + fileName : fileName;		    	   
//
//				String content = new String(doc);
//				Response r = saveResource(fullPath, content);
//				doc = null;
//
//				if (Status.OK.getStatusCode() != r.getStatus()) {
//					output += " ERROR: " + r.getEntity().toString() + "\r\n";
//				} else {
//					output += " OK\r\n";
//				}
//				if (!isFile)
//					ze = zis.getNextEntry();
//			}
//
//			if (!isFile) {
//				zis.closeEntry();
//				zis.close();
//			}
//			uploadedInputStream.close();
//
//			output += " SUCCESSFUL!\r\n";
//			return Response.ok(output).build();
//
//		} catch(Exception e){
//			log.error("Cannot unzip resources " + zipFile ,e);
//			String error = ExceptionUtils.getRootCauseMessage(e);
//			return Response.serverError().entity(output + "\r\n" + error).build();
//		}	
//	}

	private List<IRepositoryObject> getRepositoryObjects(final IUserContentAccess root, final String path, final String type) throws Exception {
		List<IRepositoryObject> repoObjects = new ArrayList<IRepositoryObject>();
		IBasicFileFilter txtFilter = StringUtils.isBlank(type) ? null : new IBasicFileFilter() {
			public boolean accept(IBasicFile file) {
				return file.isDirectory() || file.getExtension().equals(type);
			}
		};
		List<IBasicFile> files = new ArrayList<IBasicFile>();
		IUserContentAccess access = contentAccessFactory.getUserContentAccess(null);
		if (access.fileExists(path)) {
			IBasicFile bf = access.fetchFile(path);
			if (!bf.isDirectory()) {
				files.add(bf);
				log.debug("Found file in " + path);
			} else {
				files = root.listFiles(path, txtFilter, 0, true);
				log.debug("Found files in " + path + " : " + files.size());
			}
		}

		
		
		for (IBasicFile file : files) {

			String filename = file.getName();
			// WHY IS GETPATH NULL?????
			String relativePath = file.getFullPath();
			
			// Let's not include /etc for now
//			if ("/etc".equals(relativePath)) {
//				continue;
//			}

			List<AclMethod> acls = getAcl(path, false);
			if (!file.isDirectory()) {
				String extension = file.getExtension();
				repoObjects.add(new RepositoryFileObject(filename, "#" + relativePath, extension, relativePath, acls));
			} else { 
				repoObjects.add(new RepositoryFolderObject(filename, "#" + relativePath, relativePath, acls, getRepositoryObjects(root, relativePath, type)));
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
		return repoObjects;
	}

	
	private List<AclMethod> getAcl(String file, boolean folder) {
		List<AclMethod> acls = new ArrayList<AclMethod>();
	    IAuthorizationPolicy policy = PentahoSystem.get( IAuthorizationPolicy.class );
		boolean isAdmin = policy.isAllowed( AdministerSecurityAction.NAME );
		IUserContentAccess access = contentAccessFactory.getUserContentAccess(null);
		if (access.fileExists(file)) {
			acls.add(AclMethod.READ);
			if (isAdmin || access.hasAccess(file, FileAccess.WRITE)) {
				acls.add(AclMethod.WRITE);	
			}
			if (isAdmin) {
				acls.add(AclMethod.GRANT);
			}
		}
		return acls;
	}


}
