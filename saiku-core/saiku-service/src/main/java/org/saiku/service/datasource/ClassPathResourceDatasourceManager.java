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
package org.saiku.service.datasource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.VFS;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.datasources.datasource.SaikuDatasource.Type;
import org.saiku.service.util.exception.SaikuServiceException;

public class ClassPathResourceDatasourceManager implements IDatasourceManager {

	private URL repoURL;

	private Map<String,SaikuDatasource> datasources = Collections.synchronizedMap(new HashMap<String,SaikuDatasource>());

	public ClassPathResourceDatasourceManager() {

	}


	public ClassPathResourceDatasourceManager(String path) {
		try {
			setPath(path);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void setPath(String path) {

		FileSystemManager fileSystemManager;
		try {
			fileSystemManager = VFS.getManager();

			FileObject fileObject;
			fileObject = fileSystemManager.resolveFile(path);
			if (fileObject == null) {
				throw new IOException("File cannot be resolved: " + path);
			}
			if(!fileObject.exists()) {
				throw new IOException("File does not exist: " + path);
			}
			repoURL = fileObject.getURL();
			if (repoURL == null) {
				throw new Exception("Cannot load connection repository from path: "+path);
			} else {
				load();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public void load() {
		datasources.clear();
		try {
			if (repoURL != null) {
				File[] files =  new File(repoURL.getFile()).listFiles();

				for (File file : files) {
					if (!file.isHidden()) {
						Properties props = new Properties();
						props.load(new FileInputStream(file));
						String name = props.getProperty("name");
						String type = props.getProperty("type");
						if (name != null && type != null) {
							Type t = SaikuDatasource.Type.valueOf(type.toUpperCase());
							SaikuDatasource ds = new SaikuDatasource(name,t,props);
							datasources.put(name, ds);
						}
					}
				}
			}
			else {
				throw new Exception("repo URL is null");
			}
		} catch (Exception e) {
			throw new SaikuServiceException(e.getMessage(),e);
		}		
	}

	public SaikuDatasource addDatasource(SaikuDatasource datasource) {
		try { 
			String uri = repoURL.toURI().toString();
			if (uri != null && datasource != null) {
				uri += datasource.getName().replace(" ", "_");
				File dsFile = new File(new URI(uri));
				if (dsFile.exists()) {
					dsFile.delete();
				}
				else {
					dsFile.createNewFile();
				}
				FileWriter fw = new FileWriter(dsFile);
				Properties props = datasource.getProperties();
				props.store(fw, null);
				fw.close();
				datasources.put(datasource.getName(), datasource);
				return datasource;

			}
			else {
				throw new SaikuServiceException("Cannot save datasource because uri or datasource is null uri(" 
						+ (uri == null) + ")" );
			}
		}
		catch (Exception e) {
			throw new SaikuServiceException("Error saving datasource",e);
		}
	}

	public SaikuDatasource setDatasource(SaikuDatasource datasource) {
		return addDatasource(datasource);
	}

	public List<SaikuDatasource> addDatasources(List<SaikuDatasource> datasources) {
		for (SaikuDatasource ds : datasources) {
			addDatasource(ds);
		}
		return datasources;
	}

	public boolean removeDatasource(String datasourceName) {
		try {
			String uri = repoURL.toURI().toString();
			if (uri != null) {
				// seems like we don't have to do this anymore
				//uri.toString().endsWith(String.valueOf(File.separatorChar))) {
				uri += datasourceName;
				File dsFile = new File(new URI(uri));
				if (dsFile.delete()) {
					datasources.remove(datasourceName);
					return true;
				}
			}
			throw new Exception("Cannot delete datasource file uri:" + uri);
		}
		catch(Exception e){
			throw new SaikuServiceException("Cannot delete datasource",e);
		}
	}

	public Map<String,SaikuDatasource> getDatasources() {
		return datasources;
	}

	public SaikuDatasource getDatasource(String datasourceName) {
		return datasources.get(datasourceName);
	}
}
