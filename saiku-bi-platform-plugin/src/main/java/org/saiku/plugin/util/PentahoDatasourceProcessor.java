package org.saiku.plugin.util;

import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.IDatasourceProcessor;

public class PentahoDatasourceProcessor implements IDatasourceProcessor {

	public SaikuDatasource process(SaikuDatasource ds) {
		String url = ds.getProperties().getProperty("location");
		url = url.replace("solution:", "file:" + PentahoSystem.getApplicationContext().getSolutionPath("") );
		ds.getProperties().put("location", url);
		return ds;
	}

}
