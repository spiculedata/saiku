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
package org.saiku.web.rest.resources;

import java.io.StringReader;
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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.type.TypeFactory;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimensionSelection;
import org.saiku.olap.dto.SaikuQuery;
import org.saiku.olap.dto.SaikuTag;
import org.saiku.olap.dto.resultset.CellDataSet;
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

	@Autowired
	public void setOlapQueryService(OlapQueryService olapqs) {
		olapQueryService = olapqs;
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
			@FormParam("xml") @DefaultValue("") String xml,
			@PathParam("queryname") String queryName) throws ServletException 
			{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "\tPOST\t xml:" + (xml == null));
		}
		SaikuCube cube = new SaikuCube(connectionName, cubeName,cubeName, catalogName, schemaName);
		if (xml != null && xml.length() > 0) {
			return olapQueryService.createNewOlapQuery(queryName, xml);
		}
		return olapQueryService.createNewOlapQuery(queryName, cube);
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
			@PathParam("format") @DefaultValue("HIERARCHICAL") String format){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/xls/"+format+"\tGET");
		}
		try {
			byte[] doc = olapQueryService.getExport(queryName,"xls",format);
			String name = SaikuProperties.webExportExcelName;
			return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM).header(
					"content-disposition",
					"attachment; filename = " + name + ".xls").header(
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
	public QueryResult execute(@PathParam("queryname") String queryName){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result\tGET");
		}
		try {

			CellDataSet cs = olapQueryService.execute(queryName);
			return RestUtil.convert(cs);
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
			@FormParam("mdx") String mdx) 
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
			return RestUtil.convert(cs);
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
			@FormParam("mdx") String mdx)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result\tPOST\t"+mdx);
		}
		try {
			olapQueryService.qm2mdx(queryName);
			CellDataSet cs = olapQueryService.executeMdx(queryName,mdx);
			return RestUtil.convert(cs);
		}
		catch (Exception e) {
			log.error("Cannot execute query (" + queryName + ") using mdx:\n" + mdx,e);
			String error = ExceptionUtils.getRootCauseMessage(e);
			return new QueryResult(error);
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
			@PathParam("format") String formatter){
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/result"+formatter+"\tGET");
		}
		try {
			CellDataSet cs = olapQueryService.execute(queryName,formatter);
			return RestUtil.convert(cs);
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
	public void deleteAxis(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"\tDELETE");
		}
		olapQueryService.clearAxis(queryName, axisName);
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
	public Status swapAxes(@PathParam("queryname") String queryName)	
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/swapaxes\tPUT");
		}
		olapQueryService.clearSort(queryName, "ROWS");
		olapQueryService.clearSort(queryName, "COLUMNS");
		olapQueryService.swapAxes(queryName);
		return Status.OK;

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
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axis+"/dimension/"+dimension+"\tGET");
		}
		return olapQueryService.getAxisDimensionSelections(queryName, axis, dimension);
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
	public Status moveDimension(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@FormParam("position") @DefaultValue("-1") int position)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"\tPOST");
		}
		try{
			olapQueryService.moveDimension(queryName, axisName, dimensionName, position);
			return Status.OK;
		} catch(Exception e) {
			log.error("Cannot move dimension "+ dimensionName+ " for query (" + queryName + ")",e);
			return Status.INTERNAL_SERVER_ERROR;
		}
	}

	/**
	 * Delete a dimension.
	 * @return 
	 */
	@DELETE
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}")
	public Status deleteDimension(
			@PathParam("queryname") String queryName, 
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"\tDELETE");
		}
		try{
			olapQueryService.removeDimension(queryName, axisName, dimensionName);
			return Status.OK;
		}catch(Exception e){
			log.error("Cannot remove dimension "+ dimensionName+ " for query (" + queryName + ")",e);
			return Status.INTERNAL_SERVER_ERROR;
		}
	}

	@PUT
	@Consumes("application/x-www-form-urlencoded")
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/")
	public Status updateSelections(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@FormParam("selections") String selectionJSON) {
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"\tPUT");
		}
		try{
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
							olapQueryService.includeMember(queryName, dimensionName, selection.getUniquename(), "MEMBER", -1);
						}
					}
					if (selection.getType() != null && "level".equals(selection.getType().toLowerCase())) {
						if (selection.getAction() != null && "add".equals(selection.getAction().toLowerCase())) {
							olapQueryService.includeLevel(queryName, dimensionName, selection.getHierarchy(), selection.getUniquename());
						}
					}
				}
				SaikuDimensionSelection dimsels = getAxisDimensionInfo(queryName, axisName, dimensionName);
				if (dimsels != null && dimsels.getSelections().size() == 0) {
					moveDimension(queryName, "UNUSED", dimensionName, -1);
				}
				return Status.OK;
			}
		} catch (Exception e){
			log.error("Cannot updates selections for query (" + queryName + ")",e);
		}
		return Status.INTERNAL_SERVER_ERROR;
	}
	
	

	@DELETE
	@Consumes("application/x-www-form-urlencoded")
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/member/")
	public Status removeMembers(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			MultivaluedMap<String, String> formParams) {
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"\tPUT");
		}
		try{
			if (formParams.containsKey("selections")) {
				LinkedList<String> sels = (LinkedList<String>) formParams.get("selections");
				String selectionJSON = (String) sels.getFirst();
				ObjectMapper mapper = new ObjectMapper(); // can reuse, share globally
				List<SelectionRestObject> selections = mapper.readValue(selectionJSON, TypeFactory.collectionType(ArrayList.class, SelectionRestObject.class));
				for (SelectionRestObject member : selections) {
					removeMember("MEMBER", queryName, axisName, dimensionName, member.getUniquename());
				}
				return Status.OK;
			}
		} catch (Exception e){
			log.error("Cannot updates selections for query (" + queryName + ")",e);
		}
		return Status.INTERNAL_SERVER_ERROR;


	}
	/**
	 * Move a member.
	 * @return 
	 */
	@POST
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/member/{member}")
	public Status includeMember(
			@FormParam("selection") @DefaultValue("MEMBER") String selectionType, 
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("member") String uniqueMemberName, 
			@FormParam("position") @DefaultValue("-1") int position, 
			@FormParam("memberposition") @DefaultValue("-1") int memberposition)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/member/"+uniqueMemberName+"\tPOST");
		}
		try{
			olapQueryService.moveDimension(queryName, axisName, dimensionName, position);

			boolean ret = olapQueryService.includeMember(queryName, dimensionName, uniqueMemberName, selectionType, memberposition);
			if(ret == true){
				return Status.CREATED;
			}
			else{
				log.error("Cannot include member "+ dimensionName+ " for query (" + queryName + ")");
				return Status.INTERNAL_SERVER_ERROR;
			}
		} catch (Exception e){
			log.error("Cannot include member "+ dimensionName+ " for query (" + queryName + ")",e);
			return Status.INTERNAL_SERVER_ERROR;
		}
	}

	@DELETE
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/member/{member}")
	public Status removeMember(
			@FormParam("selection") @DefaultValue("MEMBER") String selectionType, 
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("member") String uniqueMemberName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/member/"+uniqueMemberName+"\tDELETE");
		}

		try{
			boolean ret = olapQueryService.removeMember(queryName, dimensionName, uniqueMemberName, selectionType);
			if(ret == true){
				SaikuDimensionSelection dimsels = olapQueryService.getAxisDimensionSelections(queryName, axisName, dimensionName);
				if (dimsels != null && dimsels.getSelections().size() == 0) {
					olapQueryService.moveDimension(queryName, "UNUSED", dimensionName, -1);
				}
				return Status.OK;
			}
			else{
				log.error("Cannot remove member "+ dimensionName+ " for query (" + queryName + ")");
				return Status.INTERNAL_SERVER_ERROR;
			}
		} catch (Exception e){
			log.error("Cannot remove member "+ dimensionName+ " for query (" + queryName + ")",e);
			return Status.INTERNAL_SERVER_ERROR;
		}
	}


	@POST
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/hierarchy/{hierarchy}/{level}")
	public Status includeLevel(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("hierarchy") String uniqueHierarchyName, 
			@PathParam("level") String uniqueLevelName, 
			@FormParam("position") @DefaultValue("-1") int position)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/hierarchy/"+uniqueHierarchyName+"/"+uniqueLevelName+"\tPOST");
		}

		try{
			olapQueryService.moveDimension(queryName, axisName, dimensionName, position);
			boolean ret = olapQueryService.includeLevel(queryName, dimensionName, uniqueHierarchyName, uniqueLevelName);
			if(ret == true){
				return Status.CREATED;
			}
			else{
				log.error("Cannot include level of hierarchy "+ uniqueHierarchyName+ " for query (" + queryName + ")");
				return Status.INTERNAL_SERVER_ERROR;
			}
		} catch (Exception e){
			log.error("Cannot include level of hierarchy "+ uniqueHierarchyName+ " for query (" + queryName + ")",e);
			return Status.INTERNAL_SERVER_ERROR;
		}
	}

	@DELETE
	@Path("/{queryname}/axis/{axis}/dimension/{dimension}/hierarchy/{hierarchy}/{level}")
	public Status removeLevel(
			@PathParam("queryname") String queryName,
			@PathParam("axis") String axisName, 
			@PathParam("dimension") String dimensionName, 
			@PathParam("hierarchy") String uniqueHierarchyName, 
			@PathParam("level") String uniqueLevelName)
	{
		if (log.isDebugEnabled()) {
			log.debug("TRACK\t"  + "\t/query/" + queryName + "/axis/"+axisName+"/dimension/"+dimensionName+"/hierarchy/"+uniqueHierarchyName+"/"+uniqueLevelName+"\tDELETE");
		}
		try{
			boolean ret = olapQueryService.removeLevel(queryName, dimensionName, uniqueHierarchyName, uniqueLevelName);
			
			if(ret == true){
				SaikuDimensionSelection dimsels = olapQueryService.getAxisDimensionSelections(queryName, axisName, dimensionName);
				if (dimsels != null && dimsels.getSelections().size() == 0) {
					olapQueryService.moveDimension(queryName, "UNUSED", dimensionName, -1);
				}
				return Status.OK;
			}
			else{
				log.error("Cannot remove level of hierarchy "+ uniqueHierarchyName+ " for query (" + queryName + ")");
				return Status.INTERNAL_SERVER_ERROR;
			}
		} catch (Exception e){
			log.error("Cannot include level of hierarchy "+ uniqueHierarchyName+ " for query (" + queryName + ")",e);
			return Status.INTERNAL_SERVER_ERROR;
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

}
