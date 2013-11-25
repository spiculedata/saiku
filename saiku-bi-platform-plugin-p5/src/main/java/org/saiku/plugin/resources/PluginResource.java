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
package org.saiku.plugin.resources;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.SaikuQuery;
import org.saiku.plugin.util.ResourceManager;
import org.saiku.plugin.util.packager.Packager;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.OlapQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Component
@Path("/saiku/api/{username}/plugin")
@XmlAccessorType(XmlAccessType.NONE)
public class PluginResource {

	private static final Logger log = LoggerFactory.getLogger(PluginResource.class);

	@Autowired
	private OlapQueryService queryService;

	@Autowired
	private DatasourceService datasourceService;

	@GET
	@Produces({"text/plain" })
	@Path("/cda")
	public String getCda(@QueryParam("query") String query) 
	{
		try {
			SaikuQuery sq = queryService.getQuery(query);
			SaikuDatasource ds = datasourceService.getDatasource(sq.getCube().getConnection());
			Properties props = ds.getProperties();

			String cdaFile = getCdaAsString(
					props.getProperty(ISaikuConnection.DRIVER_KEY), 
					props.getProperty(ISaikuConnection.URL_KEY),
					sq.getName(),
					sq.getMdx());

			return cdaFile;
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return "";
	}

	@GET
	@Produces({"application/json" })
	@Path("/cda/execute")
	public Response execute(
			@QueryParam("query") String query,
			@QueryParam("type") String type) 
	{
		try {
			String cdaString = getCda(query);
			Document cda = DocumentHelper.parseText(cdaString);
			
			throw new UnsupportedOperationException("We dont support execution of CDA at this time");
//		    final CdaSettings cdaSettings = new CdaSettings(cda, "cda1", null);
//		    
//		    log.debug("Doing query on Cda - Initializing CdaEngine");
//		    final CdaEngine engine = CdaEngine.getInstance();
//		    final QueryOptions queryOptions = new QueryOptions();
//		    queryOptions.setDataAccessId("1");
//		    queryOptions.setOutputType("json");
//		    log.info("Doing query");
//		    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//		    PrintStream printstream = new PrintStream(outputStream);
//		    engine.doQuery(printstream, cdaSettings, queryOptions);
//			byte[] doc = outputStream.toByteArray();
//			
//			return Response.ok(doc, MediaType.APPLICATION_JSON).header(
//							"content-length",doc.length).build();
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return Response.serverError().build();


	}

  
  
	@GET
	@Produces({"text/plain" })
	@Path("/plugins")
	public String getPlugins() 
	{
      Packager packager = Packager.getInstance();
      String searchRootDir = PentahoSystem.getApplicationContext().getSolutionPath("saiku/plugins");  
      
      File searchRootFile = new File(searchRootDir);
      
      if (!searchRootFile.exists())
        return "";
      
      File[] files = getJsFiles(searchRootFile);      
      
      String pluginRootDir = PentahoSystem.getApplicationContext().getSolutionPath("system/saiku");
      File rootDir = new File(searchRootDir);
          
      packager.registerPackage("scripts", Packager.Filetype.JS, searchRootDir, pluginRootDir + "/../../system/saiku/ui/js/scripts.js", files);          
      packager.minifyPackage("scripts", Packager.Mode.CONCATENATE);
      
      try {
        return ResourceManager.getInstance().getResourceAsString( "ui/js/scripts.js");
      } catch (IOException ioe) {
        ioe.printStackTrace();
      }
            
      return "";
	}
  
  
  private File[] getJsFiles(File rootDir) {
    List<File> result = new ArrayList<File>();
    
    File[] files = rootDir.listFiles(new FilenameFilter() {
      public boolean accept(File file, String name) {
        return name.endsWith(".js");
      }            
    });
    
    if (files != null)
      result.addAll(Arrays.asList(files));

    File[] folders = rootDir.listFiles(new FilenameFilter() {
      public boolean accept(File file, String name) {
        return file.isDirectory();
      }            
    });

    if (folders != null) {
      for (File f : folders) {
        File[] partial = getJsFiles(f);
        if (partial != null)
          result.addAll(Arrays.asList(partial));
      }
    }

    return result.toArray(new File[result.size()]);    
  }
  

	//	private CdaSettings initCda(String sessionId, String domain) throws Exception {
	//		CdaSettings cda = new CdaSettings("cda" + sessionId, null);
	//
	//		String[] domainInfo = domain.split("/");
	//			Connection connection = new MetadataConnection("1", domainInfo[0] + "/" + domainInfo[1], domainInfo[1]);
	//			Connection con = new jdbcconn
	//			
	//		MqlDataAccess dataAccess = new MqlDataAccess(sessionId, sessionId, "1", "");
	//		//dataAccess.setCacheEnabled(true);
	//		cda.addConnection(connection);
	//		cda.addDataAccess(dataAccess);
	//		return cda;
	//	}


//	private CdaSettings getCdaSettings(String sessionId, SaikuDatasource ds, SaikuQuery query) {
//
//		try {		
//			Document document = DocumentHelper.parseText("");
//
//			return new CdaSettings(document, sessionId, null);
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//		return null;
//	}

	private Document getCdaAsDocument(String driver, String url, String name, String query) throws Exception {
		String cda = getCdaAsString(driver, url, name, query);
		return DocumentHelper.parseText(cda);
	}

	private String getCdaAsString(String driver, String url, String name, String query) throws Exception {
		String cda = getCdaTemplate();
		cda = cda.replaceAll("@@DRIVER@@", driver);
		cda = cda.replaceAll("@@NAME@@", name);
		cda = cda.replaceAll("@@URL@@", url);
		cda = cda.replaceAll("@@QUERY@@", query);
		return cda;
	}

	private String getCdaTemplate() {
		String cda = 
			"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
			"<CDADescriptor>\n" +
			"   <DataSources>\n" +
			"        <Connection id=\"1\" type=\"olap4j.jdbc\">\n" +
			"            <Driver>@@DRIVER@@</Driver>\n" +
			"            <Url>@@URL@@</Url>\n" +
			"        </Connection>\n" +
			"    </DataSources>\n" +
			"  <DataAccess id=\"1\" connection=\"1\" type=\"olap4j\" access=\"public\">\n" +
			"		<Name>@@NAME@@</Name>\n" +
			"        <Query><![CDATA[" +
			"			@@QUERY@@" +
			"		]]></Query>\n" +
			"    </DataAccess>\n" +
			"</CDADescriptor>\n";

		return cda;
	}
}
