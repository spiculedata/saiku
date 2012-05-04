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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mondrian.olap4j.SaikuMondrianHelper;

import org.olap4j.OlapConnection;
import org.pentaho.platform.api.engine.IConnectionUserRoleMapper;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.plugin.services.connections.mondrian.MDXConnection;
import org.saiku.datasources.connection.AbstractConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuConnectionFactory;
import org.saiku.datasources.datasource.SaikuDatasource;

public class PentahoSecurityAwareConnectionManager extends AbstractConnectionManager {

	public static final String MDX_CONNECTION_MAPPER_KEY = "Mondrian-UserRoleMapper"; //$NON-NLS-1$

	private Map<String, ISaikuConnection> connections = new HashMap<String, ISaikuConnection>();

	private List<String> errorConnections = new ArrayList<String>();

	private Boolean userAware;

	@Override
	public void init() {
		this.connections = getAllConnections();
	}
	
	public void setUserAware(Boolean aware) {
		this.userAware = aware;
	}

	@Override
	protected ISaikuConnection getInternalConnection(String name, SaikuDatasource datasource) {
		ISaikuConnection con;
		if (userAware && PentahoSessionHolder.getSession().getName() != null) {
			name = name + "-" + PentahoSessionHolder.getSession().getName();
		}
		if (!connections.containsKey(name)) {
			con =  connect(name, datasource);
			if (con != null) {
				connections.put(name, con);
			} else {
				if (!errorConnections.contains(name)) {
					errorConnections.add(name);
				}
			}

		} else {
			con = connections.get(name);
		}

		try {
			con = applySecurity(con, datasource);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		return con;
	}

	@Override
	protected void refreshInternalConnection(String name, SaikuDatasource datasource) {
		try {
			String newname = name;
			if (userAware && PentahoSessionHolder.getSession().getName() != null) {
				newname = name + "-" + PentahoSessionHolder.getSession().getName();
			}
			ISaikuConnection con = connections.remove(newname);
			if (con != null) {
				con.clearCache();
			}
			con = null;
			getInternalConnection(name, datasource);
		}
		catch (Exception e) {
			e.printStackTrace();
		}

	}

	private ISaikuConnection applySecurity(ISaikuConnection con, SaikuDatasource datasource) throws Exception {
		if (con == null) {
			throw new IllegalArgumentException("Cannot apply Security to NULL connection object");
		}

		if (PentahoSystem.getObjectFactory().objectDefined(MDXConnection.MDX_CONNECTION_MAPPER_KEY)) {
			IConnectionUserRoleMapper mondrianUserRoleMapper = PentahoSystem.get(IConnectionUserRoleMapper.class, MDXConnection.MDX_CONNECTION_MAPPER_KEY, null);
			if (mondrianUserRoleMapper != null) {
				OlapConnection c = (OlapConnection) con.getConnection();
				String[] validMondrianRolesForUser = mondrianUserRoleMapper.mapConnectionRoles(PentahoSessionHolder.getSession(), c.getCatalog());
				if (setRole(con, validMondrianRolesForUser, datasource)) {
					return con;
				}
			}
		}

		return con;

	}

	private boolean setRole(ISaikuConnection con, String[] validMondrianRolesForUser, SaikuDatasource datasource) {
		if (con.getConnection() instanceof OlapConnection) 
		{
			try {
				OlapConnection c = (OlapConnection) con.getConnection();
				String roles = "";
				if (validMondrianRolesForUser != null && validMondrianRolesForUser.length > 0) {
					for (String r : validMondrianRolesForUser) {
						// lets make sure the role is actually available, just to be safe
						if (c.getAvailableRoleNames().contains(r)) {
							roles += r +";";
						}
					}
				}
				System.out.println("Setting role to datasource:" + datasource.getName() + " role: " + roles);

				SaikuMondrianHelper.setRoles(c, validMondrianRolesForUser);
				return true;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return false;
	}

	private ISaikuConnection connect(String name, SaikuDatasource datasource) {
		try {
			ISaikuConnection con = SaikuConnectionFactory.getConnection(datasource);
			if (con.initialized()) {
				return con;
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}


}
