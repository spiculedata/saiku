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
package org.saiku.plugin.resources;

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
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang.StringUtils;
import org.dom4j.Document;
import org.dom4j.Node;
import org.dom4j.io.DOMReader;
import org.pentaho.platform.api.engine.ICacheManager;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.ISolutionFile;
import org.pentaho.platform.api.repository.ISolutionRepository;
import org.pentaho.platform.api.repository.ISolutionRepositoryService;
import org.pentaho.platform.engine.core.solution.ActionInfo;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.saiku.plugin.util.PluginConfig;
import org.saiku.web.rest.objects.acl.enumeration.AclMethod;
import org.saiku.web.rest.objects.repository.IRepositoryObject;
import org.saiku.web.rest.objects.repository.RepositoryFileObject;
import org.saiku.web.rest.objects.repository.RepositoryFolderObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@Path("/saiku/{username}/pentahorepository2")
@XmlAccessorType(XmlAccessType.NONE)
public class PentahoRepositoryResource2 {

	private static final Logger log = LoggerFactory.getLogger(PentahoRepositoryResource2.class);

	private static final String CACHE_REPOSITORY_DOCUMENT = "CDF_REPOSITORY_DOCUMENT";
	IPentahoSession userSession;
	ICacheManager cacheManager;

	boolean cachingAvailable;

	public PentahoRepositoryResource2() {
		cacheManager = PentahoSystem.getCacheManager(userSession);
		cachingAvailable = cacheManager != null && cacheManager.cacheEnabled();

	}

	private Document getRepositoryDocument(final IPentahoSession userSession) throws ParserConfigurationException
	{      //
		Document repositoryDocument;
		if (cachingAvailable && (repositoryDocument = (Document) cacheManager.getFromSessionCache(userSession, CACHE_REPOSITORY_DOCUMENT)) != null)
		{
			log.debug("Repository Document found in cache");
			return repositoryDocument;
		}
		else
		{
			//System.out.println(Calendar.getInstance().getTime() + ": Getting repository Document");
			final DOMReader reader = new DOMReader();
			repositoryDocument = reader.read(PentahoSystem.get(ISolutionRepositoryService.class, userSession).getSolutionRepositoryDoc(userSession, new String[0]));
			//repositoryDocument = reader.read(new SolutionRepositoryService().getSolutionRepositoryDoc(userSession, new String[0]));
			cacheManager.putInSessionCache(userSession, CACHE_REPOSITORY_DOCUMENT, repositoryDocument);
			//System.out.println(Calendar.getInstance().getTime() + ": Repository Document Returned");
		}
		return repositoryDocument;
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
			Document navDoc = getRepositoryDocument(PentahoSessionHolder.getSession());
			final Node tree = navDoc.getRootElement();
			String context = StringUtils.isNotBlank(path) ? "/" + path : "/";
			return processTree(tree, context, type);
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
			if (file == null || file.startsWith("/") || file.startsWith(".")) {
				throw new IllegalArgumentException("Path cannot be null or start with \"/\" or \".\" - Illegal Path: " + file);
			}
			
			String[] pathParts = file.split("/");
			String solution = pathParts.length > 1 ? pathParts[0] : "";
			String path = "";
			if (pathParts.length > 2) {
				for (int i = 1; i < pathParts.length - 1; i++) {
					path += "/" + pathParts[i];
				}
			}
			String action = pathParts[pathParts.length - 1];
			
			System.out.println("file: " + file + " solution:"+solution+" path:"+path + " action:" + action);

			String fullPath = ActionInfo.buildSolutionPath(solution, path, action);
			ISolutionRepository repository = PentahoSystem.get(ISolutionRepository.class, PentahoSessionHolder.getSession());
			
			if( repository == null ) {
				log.error("Access to Repository has failed");
				throw new NullPointerException("Access to Repository has failed");
			}

			if (repository.resourceExists(fullPath)) {
				String doc = repository.getResourceAsString(fullPath, ISolutionRepository.ACTION_EXECUTE);
				if (doc == null) {
					log.error("Error retrieving document from solution repository"); 
					throw new NullPointerException("Error retrieving saiku document from solution repository"); 
				}
				return Response.ok(doc.getBytes("UTF-8"), MediaType.TEXT_PLAIN).header(
						"content-length",doc.getBytes("UTF-8").length).build();
			}

		}
		catch(Exception e){
			log.error("Cannot load file (" + file + ")",e);
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
			
			String[] pathParts = file.split("/");
			String solution = pathParts.length > 1 ? pathParts[0] : "";
			String path = "";
			if (pathParts.length > 2) {
				for (int i = 1; i < pathParts.length - 1; i++) {
					path += "/" + pathParts[i];
				}
			}
			String action = pathParts[pathParts.length - 1];
			
			System.out.println("file: " + file + " solution:"+solution+" path:"+path + " action:" + action);

			String fullPath = ActionInfo.buildSolutionPath(solution, path, action);
			IPentahoSession userSession = PentahoSessionHolder.getSession();
			ISolutionRepository repository = PentahoSystem.get(ISolutionRepository.class, userSession);

			if( repository == null ) {
				log.error("Access to Repository has failed");
				throw new NullPointerException("Access to Repository has failed");
			}
			String base = PentahoSystem.getApplicationContext().getSolutionRootPath();
			String parentPath = ActionInfo.buildSolutionPath(solution, path, "");
			ISolutionFile parentFile = repository.getSolutionFile(parentPath, ISolutionRepository.ACTION_CREATE);
			String filePath = parentPath + ISolutionRepository.SEPARATOR + action;
			ISolutionFile fileToSave = repository.getSolutionFile(fullPath, ISolutionRepository.ACTION_UPDATE);



			if (fileToSave != null || (!repository.resourceExists(filePath) && parentFile != null)) {
				repository.publish(base, '/' + parentPath, action, content.getBytes() , true);
				log.debug(PluginConfig.PLUGIN_NAME + " : Published " + solution + " / " + path + " / " + action );
			} else {
				throw new Exception("Error ocurred while saving query to solution repository");
			}
			return(Status.OK);
		}
		catch(Exception e){
			log.error("Cannot save file (" + file + ")",e);
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

		return Status.INTERNAL_SERVER_ERROR;
	}

	private List<IRepositoryObject> processTree(final Node tree, final String parentPath, String fileType)
	{
		final String xPathDir = "./file[@isDirectory='true']"; //$NON-NLS-1$
		List<IRepositoryObject> repoObjects = new ArrayList<IRepositoryObject>();
		List<AclMethod> acls = new ArrayList<AclMethod>();
		acls.add(AclMethod.READ);
		acls.add(AclMethod.WRITE);
		
		try
		{
			final List nodes = tree.selectNodes(xPathDir); //$NON-NLS-1$
			final String[] parentPathArray = parentPath.split("/");
			final String solutionName = parentPathArray.length > 2 ? parentPathArray[2] : "";
			final String solutionPath = parentPathArray.length > 3 ? parentPath.substring(parentPath.indexOf(solutionName) + solutionName.length() + 1, parentPath.length()) + "/" : "";

			for (final Object node1 : nodes)
			{
				final Node node = (Node) node1;
				String name = node.valueOf("@name");
				if (parentPathArray.length > 0)
				{
					final String localizedName = node.valueOf("@localized-name");
					final boolean visible = node.valueOf("@visible").equals("true");
					final boolean isDirectory = node.valueOf("@isDirectory").equals("true");
					final String path = solutionName.length() == 0 ? "" : solutionPath + name;
					final String solution = solutionName.length() == 0 ? name : solutionName;

					final String relativePath = solution.length() > 0 
													&& path != null 
													&& path.length() > 0 ? solution + "/" + path : solution;

					if (visible && isDirectory)
					{
						List<IRepositoryObject> children = new ArrayList<IRepositoryObject>();
						
						
						List<Node> fileNodes;
						if (StringUtils.isBlank(fileType)) {
							fileNodes = node.selectNodes("./file[@isDirectory='false']");
						}
						else {
							fileNodes = node.selectNodes("./file[@isDirectory='false'][ends-with(string(@name),'." + fileType + "') or ends-with(string(@name),'." + fileType + "')]");
						}
						for (final Node fileNode : fileNodes)
						{
							boolean vis =  fileNode.valueOf("@visible").equals("true");
							String t =  fileNode.valueOf("@localized-name");
							String n = fileNode.valueOf("@name");
							if (vis) {
								children.add(new RepositoryFileObject(t, "#" + relativePath + "/" + n, fileType, relativePath, acls));
							}
						}
						children.addAll(processTree(node, parentPath + "/" + name, fileType));
						repoObjects.add(new RepositoryFolderObject(localizedName, "#" + relativePath, relativePath, acls, children));
					} else if (visible && !isDirectory) {
						if (StringUtils.isBlank(fileType) || name.endsWith(fileType)) {
							repoObjects.add(new RepositoryFileObject(localizedName, "#" + relativePath + "/" + name, fileType, relativePath, acls));
						}
					}

				}
				else
				{
					repoObjects = processTree(tree, tree.valueOf("@path"), fileType);
				}
			}

		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		return repoObjects;
	}



}
