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

import java.util.Properties;

/**
 * @author pmac
 *
 */
public interface ISaikuConnection {
	
    public static final String OLAP_DATASOURCE = "OLAP"; //$NON-NLS-1$
    public static final String NAME_KEY = "name"; //$NON-NLS-1$
    public static final String DRIVER_KEY = "driver"; //$NON-NLS-1$
    public static final String URL_KEY = "location"; //$NON-NLS-1$
    public static final String USERNAME_KEY = "username"; //$NON-NLS-1$
    public static final String PASSWORD_KEY = "password"; //$NON-NLS-1$
    public static final String SECURITY_ENABLED_KEY = "security.enabled"; //$NON-NLS-1$
    public static final String SECURITY_TYPE_KEY = "security.type"; //$NON-NLS-1$
    public static final String SECURITY_TYPE_SPRING2MONDRIAN_VALUE = "one2one"; //$NON-NLS-1$
    public static final String SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE = "lookup"; //$NON-NLS-1$
    public static final String SECURITY_TYPE_PASSTHROUGH_VALUE = "passthrough"; //$NON-NLS-1$
    public static final String SECURITY_LOOKUP_KEY = "security.mapping"; //$NON-NLS-1$
    public static final String DATASOURCE_PROCESSORS = "datasource.processors"; //$NON-NLS-1$
    

    public static final String[] KEYS = new String[] { NAME_KEY, DRIVER_KEY, URL_KEY,
	      USERNAME_KEY, PASSWORD_KEY, SECURITY_ENABLED_KEY, SECURITY_TYPE_KEY, SECURITY_TYPE_PASSTHROUGH_VALUE,
	      SECURITY_TYPE_SPRING2MONDRIAN_VALUE, SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE, DATASOURCE_PROCESSORS};

    public static final String[] DATASOURCES = new String[] { OLAP_DATASOURCE };

	  /**
	   * Sets the properties to be used when the connection is made. The standard 
	   * keys for the properties are defined in this interface
	   * @param props
	   */
	  void setProperties( Properties props );
	  

	  /**
	   * @return the last resultset from the last query executed
	   *
	  IUnifiedResultSet getResultSet();
	  
	  /**
	   * Connects to the data source using the supplied properties.
	   * @param props Datasource connection properties
	   * @return true if the connection was successful
	   */
	  boolean connect(Properties props) throws Exception;
	  
	  boolean connect() throws Exception;
	  
	  boolean clearCache() throws Exception;

	  
	  /**
	   * @return true if the connection has been properly initialized.
	   */
	  public boolean initialized();

	  /**
	   * returns the type of connection
	   * @return
	   */
	  public String getDatasourceType();
	  
	  public Object getConnection();
	  	  
	  public String getName();

}
