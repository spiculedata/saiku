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
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.VFS;
import org.saiku.service.olap.OlapQueryService;
import org.saiku.web.rest.objects.SavedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@Path("/saiku/{username}/repository")
@XmlAccessorType(XmlAccessType.NONE)
public class BasicRepositoryResource {

	private static final Logger log = LoggerFactory.getLogger(BasicRepositoryResource.class);

	private OlapQueryService olapQueryService;

	private FileObject repo;

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

	@Autowired
	public void setOlapQueryService(OlapQueryService olapqs) {
		olapQueryService = olapqs;
	}

	/**
	 * Get Saved Queries.
	 * @return A list of SavedQuery Objects.
	 */
	@GET
	@Produces({"application/json" })
	public List<SavedQuery> getSavedQueries() {
		List<SavedQuery> queries = new ArrayList<SavedQuery>();
		try {
			if (repo != null) {
				File[] files = new File(repo.getName().getPath()).listFiles();
				for (File file : files) {
					if (!file.isHidden()) {
						SimpleDateFormat sf = new SimpleDateFormat("dd - MMM - yyyy HH:mm:ss");
						String filename = file.getName();
						if (filename.endsWith(".saiku")) {
							filename = filename.substring(0,filename.length() - ".saiku".length());

								FileReader fi = new FileReader(file);
								BufferedReader br = new BufferedReader(fi);
								String chunk ="",xml ="";
								while ((chunk = br.readLine()) != null) {
									xml += chunk + "\n";
								}
							SavedQuery sq = new SavedQuery(filename, sf.format(new Date(file.lastModified())),xml);
							queries.add(sq);
						}
					}
				}

			}
			else {
				throw new Exception("repo URL is null");
			}
		} catch (Exception e) {
			log.error(this.getClass().getName(),e);
			e.printStackTrace();
		}
		Collections.sort(queries);
		return queries;
	}

	/**
	 * Delete Query.
	 * @param queryName - The name of the query.
	 * @return A GONE Status if the query was deleted, otherwise it will return a NOT FOUND Status code.
	 */
	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}")
	public Status deleteQuery(@PathParam("queryname") String queryName){
		try{
			if (repo != null) {
				if (!queryName.endsWith(".saiku")) {
					queryName += ".saiku";
				}
				FileObject queryFile = repo.resolveFile(queryName);
				if (queryFile.delete()) {
					return(Status.GONE);
				}
			}
			throw new Exception("Cannot delete query file:" + queryName);
		}
		catch(Exception e){
			log.error("Cannot delete query (" + queryName + ")",e);
			return(Status.NOT_FOUND);
		}
	}

	/**
	 * 
	 * @param queryName - The name of the query.
	 * @param newName - The saved query name.
	 * @return An OK Status, if the save was good, otherwise a NOT FOUND Status when not saved properly.
	 */
	@POST
	@Produces({"application/json" })
	@Path("/{queryname}")
	public Status saveQuery(
			@PathParam("queryname") String queryName,
			@FormParam("newname") String newName ){
		try{
			String xml = olapQueryService.getQueryXml(queryName);
			if (newName != null) {
				queryName = newName;
			}
			
			if (repo != null && xml != null) {
				if (!queryName.endsWith(".saiku")) {
					queryName += ".saiku";
				}
				String uri = repo.getName().getPath();
				if (!uri.endsWith("" + File.separatorChar)) {
					uri += File.separatorChar;
				}
				
				File queryFile = new File(uri+URLDecoder.decode(queryName, "UTF-8"));
				if (queryFile.exists()) {
					queryFile.delete();
				}
				else {
					queryFile.createNewFile();
				}
				FileWriter fw = new FileWriter(queryFile);
				fw.write(xml);
				fw.close();
				return(Status.OK);
			}
			else {
				throw new Exception("Cannot save query because repo or xml is null repo(" 
						+ (repo == null) + ") xml(" + (xml == null) + " )" );
			}
		}
		catch(Exception e){
			log.error("Cannot save query (" + queryName + ")",e);
			return(Status.NOT_FOUND);
		}
	}

	/**
	 * Load a query.
	 * @param queryName - The name of the query to load.
	 * @return A Saiku Query Object.
	 */
	@GET
	@Produces({"application/json" })
	@Path("/{queryname}")
	public SavedQuery loadQuery(@PathParam("queryname") String queryName){
		try{
			String uri = repo.getName().getPath();
			if (uri != null && !uri.endsWith("" + File.separatorChar)) {
				uri += File.separatorChar;
			}

			String filename = queryName;
			if (uri != null) {
				if (!filename.endsWith(".saiku")) {
					filename += ".saiku";
				}
				String filepath = repo.getName().getPath();
				if (!filepath.endsWith("" + File.separatorChar)) {
					filepath += File.separatorChar;
				}
				
				File queryFile = new File(uri+filename);

				if (queryFile.exists()) {
					FileReader fi = new FileReader(queryFile);
					BufferedReader br = new BufferedReader(fi);
					String chunk ="",xml ="";
					while ((chunk = br.readLine()) != null) {
						xml += chunk + "\n";
					}
					SimpleDateFormat sf = new SimpleDateFormat("dd - MMM - yyyy HH:mm:ss");
					SavedQuery sq = new SavedQuery(filename, sf.format(new Date(queryFile.lastModified())),xml);
					return sq;
				}
				else {
					throw new Exception("File does not exist:" + uri);
				}
			}
			else {
				throw new Exception("Cannot save query because uriis null");
			}
		} catch(Exception e){
			log.error("Cannot load query (" + queryName + ")",e);
		}
		return null;
	}
}
