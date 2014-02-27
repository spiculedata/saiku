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
package org.saiku.web.rest.resources;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.type.TypeFactory;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimensionSelection;
import org.saiku.olap.dto.SaikuQuery;
import org.saiku.olap.dto.SaikuTag;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.dto.filter.SaikuFilter;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query.IQuery;
import org.saiku.olap.util.ObjectUtil;
import org.saiku.olap.util.SaikuProperties;
import org.saiku.olap.util.formatter.CellSetFormatter;
import org.saiku.olap.util.formatter.FlattenedCellSetFormatter;
import org.saiku.olap.util.formatter.HierarchicalCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.OlapQueryService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.MdxQueryObject;
import org.saiku.web.rest.objects.SavedQuery;
import org.saiku.web.rest.objects.SelectionRestObject;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.saiku.web.rest.util.RestUtil;
import org.saiku.web.rest.util.ServletUtil;
import org.saiku.web.svg.PdfReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Tom Barber
 *
 */
@Component
@Path("/saiku/{username}/query")
@XmlAccessorType(XmlAccessType.NONE)
public class QueryResource {

	private static final Logger log = LoggerFactory.getLogger(QueryResource.class);

	private OlapQueryService olapQueryService;
	private OlapDiscoverService olapDiscoverService;
	private ISaikuRepository repository;
	
	@Autowired
	public void setOlapQueryService(OlapQueryService olapqs) {
		olapQueryService = olapqs;
	}

	@Autowired
	public void setRepository(ISaikuRepository repository){
		this.repository = repository;
	}



	@Autowired
	public void setOlapDiscoverService(OlapDiscoverService olapds) {
		olapDiscoverService = olapds;
	}

	/*
	 * Query methods
	 */

	/**
	 * Return a list of open queries.
	 */
	@GET
	@Produces({"application/json" })
	public List<String> getQueries() {
		return olapQueryService.getQueries();
	}

	@GET
	@Produces({"application/json" })
	@Path("/{queryname}")
	public SaikuQuery getQuery(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "\tGET");
		}
		return olapQueryService.getQuery(queryName);
	}

	/**
	 * Delete query from the query pool.
	 * @return a HTTP 410(Works) or HTTP 404(Call failed).
	 */
	@DELETE
	@Path("/{queryname}")
	public Status deleteQuery(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "\tDELETE");
		}
		try{
			olapQueryService.deleteQuery(queryName);
			return(Status.GONE);
		}
		catch(Exception e){
			log.error("Cannot delete query (" + queryName + ")",e);
			return(Status.NOT_FOUND);
		}
	}

	/**
	 * Create a new Saiku Query.
	 * @param connectionName the name of the Saiku connection.
	 * @param cubeName the name of the cube.
	 * @param catalogName the catalog name.
	 * @param schemaName the name of the schema.
	 * @param queryName the name you want to assign to the query.
	 * @return 
	 * 
	 * @return a query model.
	 * 
	 * @see 
	 */
	@POST
	@Produces({"application/json" })
	@Path("/{queryname}")
	public SaikuQuery createQuery(
			@FormParam("connection") String connectionName, 
			@FormParam("cube") String cubeName,
			@FormParam("catalog") String catalogName, 
			@FormParam("schema") String schemaName,
			@FormParam("xml") String xmlOld, 
			@PathParam("queryname") String queryName,
			MultivaluedMap<String, String> formParams) throws ServletException {
		try {
			String file = null, xml = null;
			if (formParams != null) {
				xml = formParams.containsKey("xml") ? formParams.getFirst("xml") : xmlOld;
				file = formParams.containsKey("file") ? formParams.getFirst("file") : null;
				if (StringUtils.isNotBlank(file)) {
					Response f = repository.getResource(file);
					xml = new String( (byte[]) f.getEntity());
				}
			} else {
				xml = xmlOld;
			}
			if (log.isDebugEnabled()) {
				log.debug("TRACK\t"  + "\t/query/" + queryName + "\tPOST\t xml:" + (xml == null) + " file:" + (file));
			}
			SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName,cubeName, catalogName, schemaName);
			if (StringUtils.isNotBlank(xml)) {
				String query = ServletUtil.replaceParameters(formParams, xml);
				return olapQueryService.createNewOlapQuery(queryName, query);
			}
			return olapQueryService.createNewOlapQuery(queryName, cube);
		} catch (Exception e) {
			throw new WebApplicationException(e);
		}
	}

	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/properties")
	public Properties getProperties(@PathParam("queryname") String queryName) {
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/properties\tGET");
		}
		return olapQueryService.getProperties(queryName);
	}


	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/properties")
	public Properties setProperties(
			@PathParam("queryname") String queryName, 
			@FormParam("properties") String properties) 
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/properties\tPOST");
		}
		try {
			Properties props = new Properties();
			StringReader sr = new StringReader(properties);
			props.load(sr);
			return olapQueryService.setProperties(queryName, props);
		} catch(Exception e) {
			log.error("Cannot set properties for query (" + queryName + ")",e);
			return null;
		}

	}

	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/properties/{propertyKey}")
	public Properties setProperties(
			@PathParam("queryname") String queryName, 
			@PathParam("propertyKey") String propertyKey, 
			@FormParam("propertyValue") String propertyValue) 
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/properties/"+ propertyKey + "\tPOST");
		}
		try{
			Properties props = new Properties();
			props.put(propertyKey, propertyValue);
			return olapQueryService.setProperties(queryName, props);
		}catch(Exception e){
			log.error("Cannot set property (" + propertyKey + " ) for query (" + queryName + ")",e);
			return null;
		}

	}

	@GET
	@Path("/{queryname}/mdx")
	public MdxQueryObject getMDXQuery(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/mdx/\tGET");
		}
		try {
			String mdx = olapQueryService.getMDXQuery(queryName);
			return new MdxQueryObject(mdx);
		}
		catch (Exception e) {
			log.error("Cannot get mdx for query (" + queryName + ")",e);
			return null;
		}
	}
	
	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Path("/{queryname}/mdx")
	public void setMDXQuery(@PathParam("queryname") String queryName, @FormParam("mdx") String mdx){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/mdx/\tPOST");
		}
		try {
			olapQueryService.setMdx(queryName, mdx);
		}
		catch (Exception e) {
			log.error("Cannot set mdx for query (" + queryName + ")",e);
		}
	}

	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/xml")
	public SavedQuery getQueryXml(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/xml/\tGET");
		}
		try {
			String xml = olapQueryService.getQueryXml(queryName);
			return new SavedQuery(queryName, null, xml);
		}
		catch (Exception e) {
			log.error("Cannot get xml for query (" + queryName + ")",e);
			return null; 
		}

	}

	@GET
	@Produces({"application/vnd.ms-excel" })
	@Path("/{queryname}/export/xls")
	public Response getQueryExcelExport(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/xls/\tGET");
		}
		return getQueryExcelExport(queryName, "flattened");
	}

	@GET
	@Produces({"application/vnd.ms-excel" })
	@Path("/{queryname}/export/xls/{format}")
	public Response getQueryExcelExport(
			@PathParam("queryname") String queryName,
			@PathParam("format") @DefaultValue("flattened") String format){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/xls/"+format+"\tGET");
		}
		try {
			byte[] doc = olapQueryService.getExport(queryName,"xls",format);
			String name = SaikuProperties.webExportExcelName + "." + SaikuProperties.webExportExcelFormat;
			return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM).header(
					"content-disposition",
					"attachment; filename = " + name).header(
							"content-length",doc.length).build();
		}
		catch (Exception e) {
			log.error("Cannot get excel for query (" + queryName + ")",e);
			return Response.serverError().build();
		}
	}

	@GET
	@Produces({"text/csv" })
	@Path("/{queryname}/export/csv")
	public Response getQueryCsvExport(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/csv\tGET");
		}
		return getQueryCsvExport(queryName, "flattened");
	}

	@GET
	@Produces({"text/csv" })
	@Path("/{queryname}/export/csv/{format}")
	public Response getQueryCsvExport(
			@PathParam("queryname") String queryName,
			@PathParam("format") String format){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/csv/"+format+"\tGET");
		}
		try {
			byte[] doc = olapQueryService.getExport(queryName,"csv",format);
			String name = SaikuProperties.webExportCsvName;
			return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM).header(
					"content-disposition",
					"attachment; filename = " + name + ".csv").header(
							"content-length",doc.length).build();
		}
		catch (Exception e) {
			log.error("Cannot get csv for query (" + queryName + ")",e);
			return Response.serverError().build();
		}
	}
	


	@POST
	@Produces({"application/pdf" })
	@Path("/{queryname}/export/pdf")
	public Response exportPdfWithChart(
			@PathParam("queryname")  String queryName,
			@PathParam("svg")  @DefaultValue("") String svg)
	{
		return exportPdfWithChartAndFormat(queryName, null, svg);
	}
		
	@GET
	@Produces({"application/pdf" })
	@Path("/{queryname}/export/pdf")
	public Response exportPdf(@PathParam("queryname")  String queryName)
	{
		return exportPdfWithChartAndFormat(queryName, null, null);
	}

	@GET
	@Produces({"application/pdf" })
	@Path("/{queryname}/export/pdf/{format}")
	public Response exportPdfWithFormat(
			@PathParam("queryname")  String queryName,
			@PathParam("format") String format)
	{
		return exportPdfWithChartAndFormat(queryName, format, null);
	}
	
	@POST
	@Produces({"application/pdf" })
	@Path("/{queryname}/export/pdf/{format}")
	public Response exportPdfWithChartAndFormat(
			@PathParam("queryname")  String queryName,
			@PathParam("format") String format,
			@FormParam("svg") @DefaultValue("") String svg)
	{
		
		try {
			PdfReport pdf = new PdfReport();
			CellDataSet cs = null;
			if (StringUtils.isNotBlank(format)) {
				cs = olapQueryService.execute(queryName, format);
			} else {
				cs = olapQueryService.execute(queryName);
			}
			
			byte[] doc  = pdf.pdf(cs, svg);
			return Response.ok(doc).type("application/pdf").header(
					"content-disposition",
					"attachment; filename = export.pdf").header(
							"content-length",doc.length).build();
		} catch (Exception e) {
			log.error("Error exporting query to  PDF", e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}

	@DELETE
	@Path("/{queryname}/result")
	public Status cancel(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result\tDELETE");
		}
		try {

			olapQueryService.cancel(queryName);
			return Response.Status.OK;
		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ")",e);
			return Response.Status.INTERNAL_SERVER_ERROR;
		}
	}
	
	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/result")
	public QueryResult execute(@PathParam("queryname") String queryName, @QueryParam("limit") @DefaultValue("0") int limit){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result\tGET");
		}
		try {

			CellDataSet cs = olapQueryService.execute(queryName);
			return RestUtil.convert(cs, limit);
		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ")",e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return new QueryResult(error);
		}
	}

	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/result/{format}")
	public QueryResult executeMdx(
			@PathParam("queryname") String queryName,
			@PathParam("format") String formatter,
			@FormParam("mdx") String mdx, 
			@FormParam("limit") @DefaultValue("0") int limit) 
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result"+formatter+"\tPOST");
		}
		try {
			ICellSetFormatter icf;
			formatter = formatter == null ? "" : formatter.toLowerCase(); 
			if(formatter.equals("flat")) {
				icf = new CellSetFormatter();
			}
			else if (formatter.equals("hierarchical")) {
				icf = new HierarchicalCellSetFormatter();
			}
			else if (formatter.equals("flattened")) {
				icf = new FlattenedCellSetFormatter();
			} else {
				icf = new FlattenedCellSetFormatter();
			}

			olapQueryService.qm2mdx(queryName);
			CellDataSet cs = olapQueryService.executeMdx(queryName,mdx, icf);
			return RestUtil.convert(cs, limit);
		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ") using mdx:\n" + mdx,e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return new QueryResult(error);
		}
	}
		
	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/result")
	public QueryResult executeMdx(
			@PathParam("queryname") String queryName,
			@FormParam("mdx") String mdx,
			@FormParam("limit") @DefaultValue("0") int limit)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result\tPOST\t"+mdx);
		}
		try {
			olapQueryService.qm2mdx(queryName);
			CellDataSet cs = olapQueryService.executeMdx(queryName,mdx);
			return RestUtil.convert(cs, limit);
		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ") using mdx:\n" + mdx,e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return new QueryResult(error);
		}
	}
	
	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/result/metadata/dimensions/{dimension}/hierarchies/{hierarchy}/levels/{level}")
	public List<SimpleCubeElement> getLevelMembers(
			@PathParam("queryname") String queryName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("hierarchy") String hierarchyName,
			@PathParam("level") String levelName,
			@QueryParam("result") @DefaultValue("true") boolean result,
			@QueryParam("search") String searchString,
			@QueryParam("searchlimit") @DefaultValue("-1") int searchLimit)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t" 
					+ "\t/query/" + queryName + "/result/metadata/dimensions/" + dimensionName 
					+ "/hierarchies/" + hierarchyName + "/levels/" + levelName + "\tGET");
		}
		try {
			List<SimpleCubeElement> ms = olapQueryService.getResultMetadataMembers(queryName, result, dimensionName, hierarchyName, levelName, searchString, searchLimit);
			return ms;
		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ")",e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			throw new WebApplicationException(Response.serverError().entity(error).build());
		}
	}

	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/qm2mdx")
	public SaikuQuery transformQm2Mdx(@PathParam("queryname") String queryName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/qm2mdx\tPOST\t");
		}
		try {
			olapQueryService.qm2mdx(queryName);
			return olapQueryService.getQuery(queryName);
		}
		catch (Exception e) {
			log.error("Cannot transform Qm2Mdx query (" + queryName + ")",e);
		}
		return null;
	}
	
	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/explain")
	public QueryResult getExplainPlan(@PathParam("queryname") String queryName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/explain\tGET");
		}
		QueryResult rsc;
		ResultSet rs = null;
		try {
			Long start = (new Date()).getTime();
			rs = olapQueryService.explain(queryName);
			rsc = RestUtil.convert(rs);
			Long runtime = (new Date()).getTime()- start;
			rsc.setRuntime(runtime.intValue());

		}
		catch (Exception e) {
			log.error("Cannot get explain plan for query (" + queryName + ")",e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			rsc =  new QueryResult(error);

		}
		// no need to close resultset, its an EmptyResultset
		return rsc;

	}

	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/drillthrough")
	public QueryResult drillthrough(
			@PathParam("queryname") String queryName, 
			@QueryParam("maxrows") @DefaultValue("100") Integer maxrows,
			@QueryParam("position") String position,
			@QueryParam("returns") String returns)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/drillthrough\tGET");
		}
		QueryResult rsc;
		ResultSet rs = null;
		try {
			Long start = (new Date()).getTime();
			if (position == null) {
				rs = olapQueryService.drillthrough(queryName, maxrows, returns);
			} else {
				String[] positions = position.split(":");
				List<Integer> cellPosition = new ArrayList<Integer>();

				for (String p : positions) {
					Integer pInt = Integer.parseInt(p);
					cellPosition.add(pInt);
				}

				rs = olapQueryService.drillthrough(queryName, cellPosition, maxrows, returns);
			}
			rsc = RestUtil.convert(rs);
			Long runtime = (new Date()).getTime()- start;
			rsc.setRuntime(runtime.intValue());

		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ")",e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			rsc =  new QueryResult(error);

		}
		finally {
			if (rs != null) {
				Statement statement = null;
				Connection con = null;
				try {
					 statement = rs.getStatement();
					 con = rs.getStatement().getConnection();
				} catch (Exception e) {
					throw new SaikuServiceException(e);
				} finally {
					try {
						rs.close();
						if (statement != null) {
							statement.close();
						}
					} catch (Exception ee) {};

					rs = null;
				}
			}
		}
		return rsc;

	}


	@GET
	@Produces({"text/csv" })
	@Path("/{queryname}/drillthrough/export/csv")
	public Response getDrillthroughExport(			
			@PathParam("queryname") String queryName, 
			@QueryParam("maxrows") @DefaultValue("100") Integer maxrows,
			@QueryParam("position") String position,
			@QueryParam("returns") String returns)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/drillthrough/export/csv (maxrows:" + maxrows + " position" + position + ")\tGET");
		}
		ResultSet rs = null;

		try {
			if (position == null) {
				rs = olapQueryService.drillthrough(queryName, maxrows, returns);
			} else {
				String[] positions = position.split(":");
				List<Integer> cellPosition = new ArrayList<Integer>();

				for (String p : positions) {
					Integer pInt = Integer.parseInt(p);
					cellPosition.add(pInt);
				}

				rs = olapQueryService.drillthrough(queryName, cellPosition, maxrows, returns);
			}
			byte[] doc = olapQueryService.exportResultSetCsv(rs);
			String name = SaikuProperties.webExportCsvName;
			return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM).header(
					"content-disposition",
					"attachment; filename = " + name + "-drillthrough.csv").header(
							"content-length",doc.length).build();


		} catch (Exception e) {
			log.error("Cannot export drillthrough query (" + queryName + ")",e);
			return Response.serverError().build();
		}
		finally {
			if (rs != null) {
				try {
					Statement statement = rs.getStatement();
					statement.close();
					rs.close();
				} catch (SQLException e) {
					throw new SaikuServiceException(e);
				} finally {
					rs = null;
				}
			}
		}


	}

	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/result/{format}")
	public QueryResult execute(
			@PathParam("queryname") String queryName,
			@PathParam("format") String formatter,
			@QueryParam("limit") @DefaultValue("0") int limit)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result"+formatter+"\tGET");
		}
		try {
			CellDataSet cs = olapQueryService.execute(queryName,formatter);
			return RestUtil.convert(cs, limit);
		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ")",e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return new QueryResult(error);
		}
	}

	/*
	 * Axis Methods.
	 */

	/**
	 * Return a list of dimensions for an axis in a query.
	 * @param queryName the name of the query.
	 * @param axisName the name of the axis.
	 * @return a list of available dimensions.
	 * @see DimensionRestPojo
	 */
	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}")
	public List<SaikuDimensionSelection> getAxisInfo(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName)
			{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"\tGET");
		}
		return olapQueryService.getAxisSelection(queryName, axisName);
			}

	/**
	 * Remove all dimensions and selections on an axis
	 * @param queryName the name of the query.
	 * @param axisName the name of the axis.
	 */
	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}")
	public Response clearAxis(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName)
	{
		try {
			if (log.isDebugEnabled()) {
				log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"\tDELETE");
			}
			axisName = StringUtils.isNotBlank(axisName) ? axisName.toUpperCase() : null;
			if (axisName != null) {
				IQuery query = olapQueryService.clearAxis(queryName, axisName);
				return Response.ok().entity(ObjectUtil.convert(query)).build();
				
			}
			throw new Exception("Clear Axis: Axis name cannot be null");
		} catch(Exception e) {
			log.error("Cannot clear axis for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}

	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}/axis/")
	public void clearAllAxisSelections(@PathParam("queryname") String queryName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis\tDELETE");
		}
		olapQueryService.resetQuery(queryName);
	}

	@PUT
	@Produces({"application/json" })
	@Path("/{queryname}/swapaxes")
	public SaikuQuery swapAxes(@PathParam("queryname") String queryName)	
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/swapaxes\tPUT");
		}
		IQuery query = olapQueryService.swapAxes(queryName);
		return ObjectUtil.convert(query);
	}

	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/cell/{position}/{value}")
	public Status setCell(@PathParam("queryname") String queryName,
			@PathParam("position") String position,
			@PathParam("value") String value)	
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/cell/" + position+ "/" + value + "\tGET");
		}
		String[] positions = position.split(":");
		List<Integer> cellPosition = new ArrayList<Integer>();

		for (String p : positions) {
			Integer pInt = Integer.parseInt(p);
			cellPosition.add(pInt);
		}

		olapQueryService.setCellValue(queryName, cellPosition, value, null);
		return Status.OK;

	}


	/*
	 * Dimension Methods
	 */


	/**
	 * Return a dimension and its selections for an axis in a query.
	 * @param queryName the name of the query.
	 * @param axis the name of the axis.
	 * @param dimension the name of the axis.
	 * @return a list of available dimensions.
	 * @see DimensionRestPojo
	 */
	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}") 
	public SaikuDimensionSelection getAxisDimensionInfo(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axis,
			@PathParam("dimension") String dimension)
	{
    try {
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axis+"/dimension/"+dimension +"\tGET");
		  }
		  return olapQueryService.getAxisDimensionSelections(queryName, axis, dimension);
    } catch (Exception e) {
      log.error("Cannot decode dimension " + dimension + " for query (" + queryName + ")", e);
		  return olapQueryService.getAxisDimensionSelections(queryName, axis, dimension);
    }
	}

	/**
	 * Move a dimension from one axis to another.
	 * @param queryName the name of the query.
	 * @param axisName the name of the axis.
	 * @param dimensionName the name of the dimension. 
	 * 
	 * @return HTTP 200 or HTTP 500.
	 * 
	 * @see Status
	 */
	@POST
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}")
	public Response moveDimension(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@FormParam("position") @DefaultValue("-1") int position)
	{
		try{
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"\tPOST");
		  }
			olapQueryService.moveDimension(queryName, axisName, dimensionName, position);
			return Response.ok().build();
		} catch(Exception e) {
			log.error("Cannot move dimension "+ dimensionName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}

	/**
	 * Delete a dimension.
	 * @return 
	 */
	@DELETE
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}")
	public Response deleteDimension(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName)
	{
		try {
			if (log.isDebugEnabled()) {
				log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"\tDELETE");
			}
			olapQueryService.removeDimension(queryName, axisName, dimensionName);
			return Response.ok().build();
		} catch(Exception e){
			log.error("Cannot remove dimension "+ dimensionName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}
	
	@PUT
	@Consumes("application/x-www-form-urlencoded")
	@Path("/{queryname}/zoomin")
	public SaikuQuery zoomIn(
			@PathParam("queryname") String queryName,
			@FormParam("selections") String positionListString) {
		try {

			if (log.isDebugEnabled()) {
				log.debug("TRACK\t"  + "\t/query/" + queryName + "/zoomIn\tPUT");
			}
			List<List<Integer>> realPositions = new ArrayList<List<Integer>>();
			if (StringUtils.isNotBlank(positionListString)) {
				ObjectMapper mapper = new ObjectMapper();
				String[] positions = mapper.readValue(positionListString, TypeFactory.arrayType(String.class));
				if (positions != null && positions.length > 0) {
					for (String position : positions) {
						String[] rPos = position.split(":");
						List<Integer> cellPosition = new ArrayList<Integer>();
	
						for (String p : rPos) {
							Integer pInt = Integer.parseInt(p);
							cellPosition.add(pInt);
						}
						realPositions.add(cellPosition);
					}
				}
			}
			IQuery query = olapQueryService.zoomIn(queryName, realPositions);
			return ObjectUtil.convert(query);
			
		} catch (Exception e){
			log.error("Cannot updates selections for query (" + queryName + ")",e);
			throw new WebApplicationException(e);
		}
	}

	@PUT
	@Consumes("application/x-www-form-urlencoded")
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/")
	public Response updateSelections(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@FormParam("selections") String selectionJSON) {
		try{
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName +"\tPUT\t");
		  }

			if (selectionJSON != null) {
				ObjectMapper mapper = new ObjectMapper();
				List<SelectionRestObject> selections = mapper.readValue(selectionJSON, TypeFactory.collectionType(ArrayList.class, SelectionRestObject.class));

				// remove stuff first, then add, removing removes all selections for that level first
				for (SelectionRestObject selection : selections) {
					if (selection.getType() != null && "member".equals(selection.getType().toLowerCase())) {
						if (selection.getAction() != null && "delete".equals(selection.getAction().toLowerCase())) {
							olapQueryService.removeMember(queryName, dimensionName, selection.getUniquename(), "MEMBER");
						}
					}
					if (selection.getType() != null && "level".equals(selection.getType().toLowerCase())) {
						if (selection.getAction() != null && "delete".equals(selection.getAction().toLowerCase())) {
							olapQueryService.removeLevel(queryName, dimensionName, selection.getHierarchy(), selection.getUniquename());
						}
					}
				}
				for (SelectionRestObject selection : selections) {
					if (selection.getType() != null && "member".equals(selection.getType().toLowerCase())) {
						if (selection.getAction() != null && "add".equals(selection.getAction().toLowerCase())) {
							olapQueryService.includeMember(queryName, dimensionName, selection.getUniquename(), "MEMBER", selection.getTotalsFunction(), -1);
						}
					}
					if (selection.getType() != null && "level".equals(selection.getType().toLowerCase())) {
						if (selection.getAction() != null && "add".equals(selection.getAction().toLowerCase())) {
							olapQueryService.includeLevel(queryName, dimensionName, selection.getHierarchy(), selection.getUniquename(), selection.getTotalsFunction());
						}
					}
				}
				SaikuDimensionSelection dimsels = getAxisDimensionInfo(queryName, axisName, dimensionName);
				if (dimsels != null && dimsels.getSelections().size() == 0) {
					moveDimension(queryName, "UNUSED", dimensionName, -1);
				}
				return Response.ok().build();
			}
			throw new Exception("Form did not contain 'selections' parameter");
		} catch (Exception e){
			log.error("Cannot updates selections for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
		
	}
	
	

	@DELETE
	@Consumes("application/x-www-form-urlencoded")
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/member/")
	public Response removeMembers(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			MultivaluedMap<String, String> formParams) {
		try{
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+ dimensionName +"\tPUT");
		  }
			if (formParams.containsKey("selections")) {
				LinkedList<String> sels = (LinkedList<String>) formParams.get("selections");
				String selectionJSON = (String) sels.getFirst();
				ObjectMapper mapper = new ObjectMapper(); // can reuse, share globally
				List<SelectionRestObject> selections = mapper.readValue(selectionJSON, TypeFactory.collectionType(ArrayList.class, SelectionRestObject.class));
				for (SelectionRestObject member : selections) {
					removeMember("MEMBER", queryName, axisName, dimensionName, member.getUniquename());
				}
				return Response.ok().build();
			}
			throw new Exception("Form did not contain 'selections' parameter");
		} catch (Exception e){
			log.error("Cannot updates selections for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}
	/**
	 * Move a member.
	 * @return 
	 */
	@POST
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/member/{member}")
	public Response includeMember(
			@FormParam("selection") @DefaultValue("MEMBER") String selectionType, 
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("member") String uniqueMemberName, 
			@FormParam("position") @DefaultValue("-1") int position, 
			@FormParam("memberposition") @DefaultValue("-1") int memberposition)
	{
		try{
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/member/"+ uniqueMemberName +"\tPOST");
		  }
			olapQueryService.moveDimension(queryName, axisName, dimensionName, position);

			boolean ret = olapQueryService.includeMember(queryName, dimensionName, uniqueMemberName, selectionType, memberposition);
			if(ret == true){
				return Response.ok().status(Status.CREATED).build();
			}
			else{
				throw new Exception("Couldn't include member "+ dimensionName);
				
			}
		} catch (Exception e){
			log.error("Cannot include member "+ dimensionName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}

	@DELETE
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/member/{member}")
	public Response removeMember(
			@FormParam("selection") @DefaultValue("MEMBER") String selectionType, 
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("member") String uniqueMemberName)
	{

		try{
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+ dimensionName +"/member/"+ uniqueMemberName +"\tDELETE");
		  }
			boolean ret = olapQueryService.removeMember(queryName, dimensionName , uniqueMemberName , selectionType);
			if(ret == true){
				SaikuDimensionSelection dimsels = olapQueryService.getAxisDimensionSelections(queryName, axisName, dimensionName);
				if (dimsels != null && dimsels.getSelections().size() == 0) {
					olapQueryService.moveDimension(queryName, "UNUSED", dimensionName, -1);
				}
				return Response.ok().build();
			}
			else{
				throw new Exception("Cannot remove member "+ dimensionName+ " for query (" + queryName + ")");
			}
		} catch (Exception e){
			log.error("Cannot remove member "+ dimensionName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}
	
	@PUT
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/children")
	public Response includeChildren(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@FormParam("member") String uniqueMemberName)
	{
		
		try{
			if (log.isDebugEnabled()) {
				log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/children/"+uniqueMemberName+"\tPOST");
			}

			boolean ret = olapQueryService.includeChildren(queryName, dimensionName, uniqueMemberName);
			if(ret == true){
				return Response.ok().status(Status.CREATED).build();
			}
			else{
				throw new Exception("Couldn't include children for "+ uniqueMemberName);
				
			}
		} catch (Exception e){
			log.error("Cannot include children for "+ dimensionName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}
	
	@DELETE
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/children")
	public Response removeChildren(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@FormParam("member") String uniqueMemberName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/children/"+uniqueMemberName+"\tDELETE");
		}
		try{
			boolean ret = olapQueryService.removeChildren(queryName, dimensionName, uniqueMemberName);
			if(ret == true){
				return Response.ok().status(Status.GONE).build();
			}
			else{
				throw new Exception("Couldn't remove children for "+ uniqueMemberName);
				
			}
		} catch (Exception e){
			log.error("Cannot remove children for "+ dimensionName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}


	@POST
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/hierarchy/{hierarchy}/{level}")
	public Response includeLevel(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("hierarchy") String uniqueHierarchyName, 
			@PathParam("level") String uniqueLevelName, 
			@FormParam("position") @DefaultValue("-1") int position)
	{

		try{
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+ dimensionName +"/hierarchy/"+ uniqueHierarchyName +"/"+ uniqueLevelName +"\tPOST");
		  }
			olapQueryService.moveDimension(queryName, axisName, dimensionName, position);
			boolean ret = olapQueryService.includeLevel(queryName, dimensionName, uniqueHierarchyName, uniqueLevelName);
			if(ret == true){
				return Response.ok().status(Status.CREATED).build();
			}
			else{
				throw new Exception("Something went wrong including level: " + uniqueLevelName);
			}
		} catch (Exception e){
			log.error("Cannot include level of hierarchy "+ uniqueHierarchyName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}

	@DELETE
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/hierarchy/{hierarchy}/{level}")
	public Response removeLevel(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("hierarchy") String uniqueHierarchyName, 
			@PathParam("level") String uniqueLevelName)
	{
		try{
		  if (log.isDebugEnabled()) {
			  log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/hierarchy/"+ uniqueHierarchyName +"/"+ uniqueLevelName+"\tDELETE");
		  }
			boolean ret = olapQueryService.removeLevel(queryName, dimensionName, uniqueHierarchyName, uniqueLevelName);
			
			if(ret == true){
				SaikuDimensionSelection dimsels = olapQueryService.getAxisDimensionSelections(queryName, axisName, dimensionName);
				if (dimsels != null && dimsels.getSelections().size() == 0) {
					olapQueryService.moveDimension(queryName, "UNUSED", dimensionName, -1);
				}
				return Response.ok().build();
			}
			else{
				log.error("Cannot remove level of hierarchy "+ uniqueHierarchyName + " for query (" + queryName + ")");
			}
			throw new Exception("Something went wrong removing level: " + uniqueLevelName + " from " + uniqueHierarchyName+ " for query (" + queryName + ")");
		} catch (Exception e){
			log.error("Cannot include level of hierarchy "+ uniqueHierarchyName+ " for query (" + queryName + ")",e);
			return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
		}
	}
	
	
	
	@PUT
	@Produces({"application/json" })
	@Path("/{queryname}/tag")
	public Status activateTag(
			@PathParam("queryname") String queryName,
			@FormParam("tag") String tagJSON)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/tags\tPUT");
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
		    mapper.setVisibilityChecker(mapper.getVisibilityChecker().withFieldVisibility(Visibility.ANY));
			SaikuTag tag = mapper.readValue(tagJSON, SaikuTag.class);
			
			olapQueryService.setTag(queryName, tag);
			return Status.OK;
		}
		catch (Exception e) {
			log.error("Cannot add tag " + tagJSON + " for query (" + queryName + ")",e);
		}
		return Status.INTERNAL_SERVER_ERROR;

	}
	
	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}/tag")
	public Status deactivateTag(
			@PathParam("queryname") String queryName,
			@PathParam("tagname") String tagName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/tags\tPUT");
		}
		try {
			olapQueryService.disableTag(queryName);
			return Status.OK;
		}
		catch (Exception e) {
			log.error("Cannot remove tag " + tagName + " for query (" + queryName + ")",e);
		}
		return Status.INTERNAL_SERVER_ERROR;

	}
	

	@GET
	@Produces({"application/json" })
	@Path("/{queryname}/filter")
	public Response getFilter(
			@PathParam("queryname") String queryName,			
			@QueryParam("dimension") String dimension,
			@QueryParam("hierarchy") String hierarchy,
			@QueryParam("level") String level)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/filter\tGET");
		}
		try {
			SaikuFilter t = olapQueryService.getFilter(queryName, "new", dimension, hierarchy, level);
			return Response.ok(t).build();
		}
		catch (Exception e) {
			log.error("Cannot get filter for query (" + queryName + ")",e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return Response.serverError().entity(error).build();
		}
	}

	
	@PUT
	@Produces({"application/json" })
	@Path("/{queryname}/filter")
	public Response activateFilter(
			@PathParam("queryname") String queryName,
			@FormParam("filter") String filterJSON)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/tags\tPUT");
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
		    mapper.setVisibilityChecker(mapper.getVisibilityChecker().withFieldVisibility(Visibility.ANY));
			SaikuFilter filter = mapper.readValue(filterJSON, SaikuFilter.class);
			SaikuQuery sq = olapQueryService.applyFilter(queryName, filter);
			return Response.ok(sq).build();
		}
		catch (Exception e) {
			log.error("Cannot activate filter for query (" + queryName + "), json:" +  filterJSON, e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return Response.serverError().entity(error).build();
		}

	}
	
	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}/filter")
	public Response deactivateFilter(@PathParam("queryname") String queryName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/tags\tPUT");
		}
		try {
			SaikuQuery sq = olapQueryService.removeFilter(queryName);
			return Response.ok(sq).build();
		}
		catch (Exception e) {
			log.error("Cannot remove filter for query (" + queryName + ")", e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return Response.serverError().entity(error).build();
		}
	}
		
	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/sort/{sortorder}/{sortliteral}")
	public void sortAxis(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName,
			@PathParam("sortorder") String sortOrder,
			@PathParam("sortliteral") String sortLiteral)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/sort/" + sortOrder + "/" + sortLiteral +"\tPOST");
		}
		olapQueryService.sortAxis(queryName, axisName, sortLiteral, sortOrder);
	}
	
	@PUT
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/show_totals/{function}")
	public SaikuQuery showGrandTotals(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName,
			@PathParam("function") String functionName
			)	
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/" + axisName + "/show_totals/" + functionName + "\tPUT");
		}
		IQuery query = olapQueryService.showGrandTotals(queryName, axisName, functionName);
		return ObjectUtil.convert(query);
	}
	
	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/sort")
	public void clearSortAxis(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/sort/\tDELETE");
		}
		olapQueryService.clearSort(queryName, axisName);
	}
	
	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/limit/{limitfunction}")
	public void limitAxis(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName,
			@PathParam("limitfunction") String limitfunction,
			@FormParam("n") String n,
			@FormParam("sortliteral") String sortLiteral)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/limit/" + limitfunction+ "(" + n  + ", sort:"+ sortLiteral +"\tPOST");
		}
		olapQueryService.limitAxis(queryName, axisName, limitfunction, n, sortLiteral);
	}
	
	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/limit")
	public void clearLimitAxis(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/limit/\tDELETE");
		}
		olapQueryService.clearLimit(queryName, axisName);
	}

	@POST
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/filter")
	public void filterAxis(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName,
			@FormParam("filterCondition") String filterCondition)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/filter/ (" + filterCondition +" )\tPOST");
		}
		olapQueryService.filterAxis(queryName, axisName, filterCondition);
	}
	
	@DELETE
	@Produces({"application/json" })
	@Path("/{queryname}/axis/{axis}/filter")
	public void clearFilter(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/filter/\tDELETE");
		}
		olapQueryService.clearFilter(queryName, axisName);
	}

}
