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

import java.io.Serializable;
import java.util.Map;

import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.datasource.SaikuDatasource;

public class DatasourceService implements Serializable {

	/**
	 * Unique serialization UID 
	 */
	private static final long serialVersionUID = -4407446633148181669L;

	private IDatasourceManager datasources;
	
	private IConnectionManager connectionManager;
	
	public void setConnectionManager(IConnectionManager ic) {
		connectionManager = ic;
		datasources = ic.getDataSourceManager();
	}

	public IConnectionManager getConnectionManager() {
		return connectionManager;
	}
	
	public void addDatasource(SaikuDatasource datasource) {
		datasources.addDatasource(datasource);
	}
	
	public void setDatasource(SaikuDatasource datasource) {
		datasources.setDatasource(datasource);
	}
	
	public void removeDatasource(String datasourceName) {
		datasources.removeDatasource(datasourceName);
	}
	
	public SaikuDatasource getDatasource(String datasourceName) {
		return datasources.getDatasource(datasourceName);
	}
	
	public Map<String,SaikuDatasource> getDatasources() {
		return datasources.getDatasources();
	}

}
