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
package org.saiku.plugin;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.sql.DataSource;

import mondrian.spi.DataSourceResolver;

import org.apache.log4j.Logger;
import org.pentaho.platform.api.data.DatasourceServiceException;
import org.pentaho.platform.api.data.IDatasourceService;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;

public class PentahoDataSourceResolver implements DataSourceResolver {
	Logger logger = Logger.getLogger(PentahoDataSourceResolver.class);
	public DataSource lookup(String dataSourceName) throws Exception {
		IDatasourceService datasourceSvc =
			PentahoSystem.getObjectFactory().get(
					IDatasourceService.class,
					PentahoSessionHolder.getSession());
		javax.sql.DataSource datasource;
		try {
			datasource =
				datasourceSvc.getDataSource(
						datasourceSvc.getDSUnboundName(dataSourceName));
		}
		catch (DatasourceServiceException e) {
			Context initContext = new InitialContext();
			if (dataSourceName != null && !dataSourceName.startsWith("java:/comp/env/")) {
				dataSourceName = "java:/comp/env/" + dataSourceName;
			}
			datasource  = (DataSource)initContext.lookup(dataSourceName);
		}
		return datasource;

	}
}
