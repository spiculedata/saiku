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
package org.saiku.plugin;

import java.util.Properties;

import mondrian.olap4j.SaikuMondrianHelper;
import mondrian.rolap.RolapConnection;

import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.ISaikuConnection;

public class SaikuReadyOlapConnection implements ISaikuConnection {

	private String name;
	private boolean initialized = true;
	private OlapConnection olapConnection;

	public SaikuReadyOlapConnection(String name, OlapConnection con) {
		this.olapConnection = con;
		this.name = name;
	}
	
	
	public boolean connect() throws Exception {
		return true;
	}

	
	public boolean connect(Properties props) throws Exception {
		return true;
	}
	
	public boolean clearCache() throws Exception {
		if (SaikuMondrianHelper.isMondrianConnection(olapConnection)) {
			RolapConnection rcon = SaikuMondrianHelper.getMondrianConnection(olapConnection);
			rcon.getCacheControl(null).flushSchemaCache();
		}
		return true;
	}


	public String getDatasourceType() {
		return ISaikuConnection.OLAP_DATASOURCE;
	}

	public boolean initialized() {
		return initialized;
	}

	public Object getConnection() {
		return olapConnection;
	}
	
	public String getName() {
		return name;
	}


	public void setProperties(Properties props) {
		// TODO Auto-generated method stub
	}

}
