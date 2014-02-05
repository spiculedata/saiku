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
package org.saiku.datasources.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import mondrian.rolap.RolapConnection;

import org.olap4j.OlapConnection;
import org.olap4j.OlapWrapper;

public class SaikuOlapConnection implements ISaikuConnection {

	private String name;
	private boolean initialized = false;
	private Properties properties;
	private OlapConnection olapConnection;
	private String username;
	private String password;

	public SaikuOlapConnection(String name, Properties props) {
		this.name = name;
		this.properties = props;
	}
	public SaikuOlapConnection(Properties props) {
		this.properties = props;
		this.name = props.getProperty(ISaikuConnection.NAME_KEY);
	}

	public boolean connect() throws Exception {
		return connect(properties);
	}


	public boolean connect(Properties props) throws Exception {
		this.username = props.getProperty(ISaikuConnection.USERNAME_KEY);
		this.password = props.getProperty(ISaikuConnection.PASSWORD_KEY);
		String driver = props.getProperty(ISaikuConnection.DRIVER_KEY);
		this.properties = props;
		String url = props.getProperty(ISaikuConnection.URL_KEY);
		System.out.println("name:" + name);
		System.out.println("driver:" + driver);
		System.out.println("url:" + url);
		System.out.flush();
		
		if (url.length() > 0 && url.charAt(url.length()-1) != ';') {
			url += ";";
		}
		if (driver.equals("mondrian.olap4j.MondrianOlap4jDriver")) {
			if (username != null && username.length() > 0) {
				url += "JdbcUser=" + username + ";";
			}
			if (password != null && password.length() > 0) {
				url += "JdbcPassword=" + password + ";";
			}
		}

		Class.forName(driver);
		OlapConnection connection;
		connection = (OlapConnection) DriverManager.getConnection(url, username,password);
		final OlapWrapper wrapper = connection;
		OlapConnection tmpolapConnection = (OlapConnection) wrapper.unwrap(OlapConnection.class);
		
		

		if (tmpolapConnection == null) {
			throw new Exception("Connection is null");
		}

		System.out.println("Catalogs:" + tmpolapConnection.getOlapCatalogs().size());
		olapConnection = tmpolapConnection;
		initialized = true;
		return true;
	}
	
	public boolean clearCache() throws Exception {
		if (olapConnection.isWrapperFor(RolapConnection.class)) {
			System.out.println("Clearing cache");
			RolapConnection rcon = olapConnection.unwrap(RolapConnection.class);
			rcon.getCacheControl(null).flushSchema(rcon.getSchema());
		}
		return true;
	}

	

	public String getDatasourceType() {
		return ISaikuConnection.OLAP_DATASOURCE;
	}

	public boolean initialized() {
		return initialized;
	}

	public Connection getConnection() {
		return olapConnection;
	}

	public void setProperties(Properties props) {
		properties = props;
	}

	public String getName() {
		return name;
	}

}
