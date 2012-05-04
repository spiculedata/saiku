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
package org.saiku.datasources.connection;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.olap4j.OlapConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.datasource.IDatasourceProcessor;
import org.saiku.service.util.exception.SaikuServiceException;

public abstract class AbstractConnectionManager implements IConnectionManager {


	private IDatasourceManager ds;

	public void setDataSourceManager(IDatasourceManager ds) {
		this.ds = ds;
	}

	public IDatasourceManager getDataSourceManager() {
		return ds;
	}

	public abstract void init();

	private SaikuDatasource preProcess(SaikuDatasource datasource) {
		if (datasource.getProperties().containsKey(ISaikuConnection.DATASOURCE_PROCESSORS)) {
			datasource = datasource.clone();
			String[] processors = datasource.getProperties().getProperty(ISaikuConnection.DATASOURCE_PROCESSORS).split(",");
			for (String processor : processors) {
				try {
				@SuppressWarnings("unchecked")
				final Class<IDatasourceProcessor> clazz =
					(Class<IDatasourceProcessor>)
					Class.forName(processor);
				final Constructor<IDatasourceProcessor> ctor =
					clazz.getConstructor();
				final IDatasourceProcessor dsProcessor = ctor.newInstance();
				datasource = dsProcessor.process(datasource);
				}
				catch (Exception e) {
					throw new SaikuServiceException("Error applying DatasourceProcessor \"" + processor + "\"", e);
				}
			}
		}
		return datasource;
	}

	public ISaikuConnection getConnection(String name) {
		SaikuDatasource datasource = ds.getDatasource(name);
		datasource = preProcess(datasource);
		return getInternalConnection(name, datasource);
	}

	protected abstract ISaikuConnection getInternalConnection(String name, SaikuDatasource datasource);
	
	protected abstract void refreshInternalConnection(String name, SaikuDatasource datasource);

	public void refreshAllConnections() {
		ds.load();
		for (String name : ds.getDatasources().keySet()) {
			refreshConnection(name);
		}
	}

	public void refreshConnection(String name) {
		SaikuDatasource datasource = ds.getDatasource(name);
		datasource = preProcess(datasource);
		refreshInternalConnection(name, datasource);
	}

	public Map<String, ISaikuConnection> getAllConnections() {
		Map<String, ISaikuConnection> resultDs = new HashMap<String, ISaikuConnection>();
		for (String name : ds.getDatasources().keySet()) {
			ISaikuConnection con = getConnection(name);
			if (con != null) {
				resultDs.put(name,con);
			}
		}
		return resultDs;
	}

	public OlapConnection getOlapConnection(String name) {
		ISaikuConnection con = getConnection(name);
		if (con != null) {
			Object o = con.getConnection();
			if (o != null && o instanceof OlapConnection) {
				return (OlapConnection) o;
			}
		}
		return null;
	}

	public Map<String, OlapConnection> getAllOlapConnections() {
		Map<String, ISaikuConnection> connections = getAllConnections();
		Map<String, OlapConnection> ocons = new HashMap<String, OlapConnection>();
		for (ISaikuConnection con : connections.values()) {
			Object o = con.getConnection();
			if (o != null && o instanceof OlapConnection) {
				ocons.put(con.getName(), (OlapConnection) o);
			}
		}

		return ocons;
	}

	public boolean isDatasourceSecurity(SaikuDatasource datasource, String value) {
		if (datasource != null && value != null) {
			Properties props = datasource.getProperties();
			if (props != null && isDatasourceSecurityEnabled(datasource)) {
				if (props.containsKey(ISaikuConnection.SECURITY_TYPE_KEY)) {
					return props.getProperty(ISaikuConnection.SECURITY_TYPE_KEY).equals(value);
				}
			}
		}
		return false;
	}

	public boolean isDatasourceSecurityEnabled(SaikuDatasource datasource) {
		if (datasource != null) {
			Properties props = datasource.getProperties();
			if (props != null && props.containsKey(ISaikuConnection.SECURITY_ENABLED_KEY)) {
				String enabled = props.getProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "false");
				boolean isSecurity = Boolean.parseBoolean(enabled);
				return isSecurity;
			}
		}
		return false;
	}
}
