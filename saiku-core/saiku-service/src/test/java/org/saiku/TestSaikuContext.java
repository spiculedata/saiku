
package org.saiku;

import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.SimpleConnectionManager;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.discover.OlapMetaExplorer;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.service.datasource.ClassPathResourceDatasourceManager;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ThinQueryService;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;


public class TestSaikuContext {
    
	private static TestSaikuContext instance;
	public static boolean DEBUG = false;
	private IDatasourceManager datasourceManager;
	private IConnectionManager connectionManager;
	private OlapMetaExplorer olapMetaExplorer;
	public OlapDiscoverService olapDiscoverService;
	public DatasourceService datasourceService;
	public ThinQueryService thinQueryService;

	private TestSaikuContext() throws Exception {
		setup();
	}

	private void setup() throws Exception {
		/*File f = new File(System.getProperty("java.io.tmpdir") + "/files/");
		f.mkdir();

		this.datasourceManager = new ClassPathResourceDatasourceManager(System.getProperty("java.io.tmpdir") + "/files/");
		InputStream inputStream = TestSaikuContext.class.getResourceAsStream("connection.properties");
		Properties testProps = new Properties();
		testProps.load(inputStream);
		this.datasourceManager.setDatasource(new SaikuDatasource("test", SaikuDatasource.Type.OLAP, testProps));

		this.connectionManager = new SimpleConnectionManager();
		this.connectionManager.setDataSourceManager(datasourceManager);
		this.connectionManager.init();

		this.olapMetaExplorer = new OlapMetaExplorer(connectionManager);

		this.datasourceService = new DatasourceService();
		this.datasourceService.setConnectionManager(connectionManager);

		this.olapDiscoverService = new OlapDiscoverService();
		this.olapDiscoverService.setDatasourceService(datasourceService);

		this.thinQueryService = new ThinQueryService();
		thinQueryService.setOlapDiscoverService(olapDiscoverService);*/
	}
	
	

	public static TestSaikuContext instance() throws Exception {
		if (instance == null) {
			instance = new TestSaikuContext();
		}
		return instance;
	}
	
	public static SaikuCube getSalesCube() {
	  return new SaikuCube("test", "Sales", "Sales", "Sales", "FoodMart", "FoodMart");
	}

}

