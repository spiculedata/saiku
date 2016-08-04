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

import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.util.SaikuProperties;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.drillthrough.DrillThroughResult;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.export.JSConverter;
import org.saiku.web.export.PdfReport;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.saiku.web.rest.util.RestUtil;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.qmino.miredot.annotations.ReturnType;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/**
 * Saiku Query Endpoints
 */
@Component
@Path("/saiku/api/query")
@XmlAccessorType(XmlAccessType.NONE)
public class Query2Resource {

    private static final Logger log = LoggerFactory.getLogger(Query2Resource.class);

    private ThinQueryService thinQueryService;

    //@Autowired
    public void setThinQueryService(ThinQueryService tqs) {
        thinQueryService = tqs;
    }

    private ISaikuRepository repository;

    //@Autowired
    public void setRepository(ISaikuRepository repository){
        this.repository = repository;
    }


    /**
     * Delete query from the query pool.
     * @summary Delete Query
     * @param queryName The query name
     * @return a HTTP 410(Works) or HTTP 500(Call failed).
     */
    @DELETE
    @Path("/{queryname}")
    public Status deleteQuery(@PathParam("queryname") String queryName){
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"  + "\t/query/" + queryName + "\tDELETE");
        }
        try{
            thinQueryService.deleteQuery(queryName);
            return(Status.GONE);
        }
        catch(Exception e){
            log.error("Cannot delete query (" + queryName + ")",e);
            throw new WebApplicationException(e);
        }
    }

    /**
     * Create a new Saiku Query.
     * @summary Create query.
     * @param queryName The query name
     * @param fileFormParam The file
     * @param jsonFormParam The json
     * @param formParams The form params
     * @return a query model.
     *
     */
    @POST
    @Produces({"application/json" })
    @Path("/{queryname}")
    public ThinQuery createQuery(
            @PathParam("queryname") String queryName,
            @FormParam("json") String jsonFormParam,
            @FormParam("file") String fileFormParam,
            MultivaluedMap<String, String> formParams) throws ServletException
    {
        try {
            ThinQuery tq;
            String file = fileFormParam,
                    json = jsonFormParam;
            if (formParams != null) {
                json = formParams.containsKey("json") ? formParams.getFirst("json") : jsonFormParam;
                file = formParams.containsKey("file") ? formParams.getFirst("file") : fileFormParam;
            }
            String filecontent = null;
            if (StringUtils.isNotBlank(json)) {
                filecontent = json;
            } else if (StringUtils.isNotBlank(file)) {
                Response f = repository.getResource(file);
                filecontent = new String( (byte[]) f.getEntity());
            }
            if (StringUtils.isBlank(filecontent)) {
                throw new SaikuServiceException("Cannot create new query. Empty file content " + StringUtils.isNotBlank(json) + " or read from file:" + file);
            }
            if (thinQueryService.isOldQuery(filecontent)) {
                tq = thinQueryService.convertQuery(filecontent);
            } else {
                ObjectMapper om = new ObjectMapper();
                tq = om.readValue(filecontent, ThinQuery.class);
            }

            if (log.isDebugEnabled()) {
                log.debug("TRACK\t"  + "\t/query/" + queryName + "\tPOST\t tq:" + (tq == null) + " file:" + (file));
            }

            if (tq == null) {
                throw new SaikuServiceException("Cannot create blank query (ThinQuery object = null)");
            }
            tq.setName(queryName);

            //			SaikuCube cube = tq.getCube();
            //			if (StringUtils.isNotBlank(xml)) {
            //				String query = ServletUtil.replaceParameters(formParams, xml);
            //				return thinQueryService.createNewOlapQuery(queryName, query);
            //			}
            return thinQueryService.createQuery(tq);
        } catch (Exception e) {
            log.error("Error creating new query", e);
            throw new WebApplicationException(e);
        }
    }


  /**
   *
   * Execute a Saiku Query
   * @summary Execute Query
   * @param tq Thin Query model
   * @return A query result set.
   */
    @POST
    @Consumes({"application/json" })
    @Path("/execute")
    public QueryResult execute(ThinQuery tq) {
        try {
            if (thinQueryService.isMdxDrillthrough(tq)) {
                Long start = (new Date()).getTime();
                ResultSet rs = thinQueryService.drillthrough(tq);
                QueryResult rsc = RestUtil.convert(rs);
                rsc.setQuery(tq);
                Long runtime = (new Date()).getTime()- start;
                rsc.setRuntime(runtime.intValue());
                return rsc;
            }

            QueryResult qr = RestUtil.convert(thinQueryService.execute(tq));
            ThinQuery tqAfter = thinQueryService.getContext(tq.getName()).getOlapQuery();
            qr.setQuery(tqAfter);
            return qr;
        }
        catch (Exception e) {
            log.error("Cannot execute query (" + tq + ")",e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return new QueryResult(error);
        }
    }

  /**
   * Cancel a running query.
   * @summary Cancel Query.
   * @param queryName The query name
   * @return A 410 on success
   */
    @DELETE
    @Path("/{queryname}/cancel")
    public Response cancel(@PathParam("queryname") String queryName){
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"  + "\t/query/" + queryName + "\tDELETE");
        }
        try{
            thinQueryService.cancel(queryName);
            return Response.ok(Status.GONE).build();
        }
        catch(Exception e){
            log.error("Cannot cancel query (" + queryName + ")",e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(Response.serverError().entity(error).build());
        }
    }

  /**
   * Enrich a thin query model
   * @summary Enrich thin query.
   * @param tq The thin query
   * @return An updated thin query.
   */
    @POST
    @Consumes({"application/json" })
    @Path("/enrich")
    public ThinQuery enrich(ThinQuery tq) {
        try {
            return thinQueryService.updateQuery(tq);
        }
        catch (Exception e) {
            log.error("Cannot enrich query (" + tq + ")",e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(Response.serverError().entity(error).build());
        }
    }

  /**
   * Get level members from a query.
   * @summary Get level members.
   * @param queryName The query name
   * @param hierarchyName The hierarchy name
   * @param levelName The level name
   * @param result Use the current result
   * @param searchString The search string
   * @param searchLimit The search limit
   * @return
   */
    @GET
    @Produces({"application/json" })
    @Path("/{queryname}/result/metadata/hierarchies/{hierarchy}/levels/{level}")
    public List<SimpleCubeElement> getLevelMembers(
            @PathParam("queryname") String queryName,
            @PathParam("hierarchy") String hierarchyName,
            @PathParam("level") String levelName,
            @QueryParam("result") @DefaultValue("true") boolean result,
            @QueryParam("search") String searchString,
            @QueryParam("searchlimit") @DefaultValue("-1") int searchLimit)
    {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"
                    + "\t/query/" + queryName + "/result/metadata"
                    + "/hierarchies/" + hierarchyName + "/levels/" + levelName + "\tGET");
        }
        try {
            return thinQueryService.getResultMetadataMembers(queryName, result, hierarchyName, levelName, searchString, searchLimit);
        }
        catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")",e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(Response.serverError().entity(error).build());
        }
    }


  /**
   * Query export to excel.
   * @summary Excel export
   * @param queryName The query name
   * @return A response containing an excel spreadsheet.
   */
    @GET
    @Produces({"application/vnd.ms-excel" })
    @Path("/{queryname}/export/xls")
    public Response getQueryExcelExport(@PathParam("queryname") String queryName){
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/xls/\tGET");
        }
        return getQueryExcelExport(queryName, "flattened", null);
    }

  /**
   * Query export to excel
   * @summary Excel export
   * @param queryName The query
   * @param format The cellset format
   * @param name The export name
   * @return A response containing and excel spreadsheet.
   */
    @GET
    @Produces({"application/vnd.ms-excel" })
    @Path("/{queryname}/export/xls/{format}")
    public Response getQueryExcelExport(
            @PathParam("queryname") String queryName,
            @PathParam("format") @DefaultValue("flattened") String format, @QueryParam("exportname") @DefaultValue("")
            String name){
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/xls/"+format+"\tGET");
        }
        try {
            byte[] doc = thinQueryService.getExport(queryName,"xls",format);
            if(name == null || name.equals("")) {
                name = SaikuProperties.webExportExcelName + "." + SaikuProperties.webExportExcelFormat;
            }
            return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM).header(
                    "content-disposition",
                    "attachment; filename = " + name).header(
                    "content-length",doc.length).build();
        }
        catch (Exception e) {
            log.error("Cannot get excel for query (" + queryName + ")",e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(Response.serverError().entity(error).build());
        }
    }

  /**
   * Get CSV export of a query.
   * @summary CSV Export.
   * @param queryName The query name
   * @return A response containing a CSV file
   */
    @GET
    @Produces({"text/csv" })
    @Path("/{queryname}/export/csv")
    public Response getQueryCsvExport(@PathParam("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/csv\tGET");
        }
        return getQueryCsvExport(queryName, "flattened", null);
    }

  /**
   * Get CSV export of a query.
   * @summary CSV Export.
   * @param queryName The query name
   * @param format The cell set format
   * @param name The export name
   * @return A response containing a CSV file
   */
    @GET
    @Produces({"text/csv" })
    @Path("/{queryname}/export/csv/{format}")
    public Response getQueryCsvExport(
            @PathParam("queryname") String queryName,
            @PathParam("format") String format, @QueryParam("exportname") @DefaultValue("") String name){
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"  + "\t/query/" + queryName + "/export/csv/"+format+"\tGET");
        }
        try {
            byte[] doc = thinQueryService.getExport(queryName,"csv",format);
            if(name == null || name.equals("")) {
                 name = SaikuProperties.webExportCsvName;
            }

            return Response.ok(doc, MediaType.APPLICATION_OCTET_STREAM).header(
                    "content-disposition",
                    "attachment; filename = " + name + ".csv").header(
                    "content-length",doc.length).build();
        }
        catch (Exception e) {
            log.error("Cannot get csv for query (" + queryName + ")",e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(Response.serverError().entity(error).build());
        }
    }

  /**
   * Zoom into a query result table.
   * @summary Zoom in.
   * @param queryName The query name
   * @param positionListString The zoom position
   * @return A new thin query model with a reduced table.
   */
    @POST
    @Consumes("application/x-www-form-urlencoded")
    @Path("/{queryname}/zoomin")
    public ThinQuery zoomIn(
            @PathParam("queryname") String queryName,
            @FormParam("selections") String positionListString) {
        try {

            if (log.isDebugEnabled()) {
                log.debug("TRACK\t"  + "\t/query/" + queryName + "/zoomIn\tPUT");
            }
            List<List<Integer>> realPositions = new ArrayList<>();
            if (StringUtils.isNotBlank(positionListString)) {
                ObjectMapper mapper = new ObjectMapper();
                String[] positions = mapper.readValue(positionListString,
                    mapper.getTypeFactory().constructArrayType(String.class));
                if (positions != null && positions.length > 0) {
                    for (String position : positions) {
                        String[] rPos = position.split(":");
                        List<Integer> cellPosition = new ArrayList<>();

                        for (String p : rPos) {
                            Integer pInt = Integer.parseInt(p);
                            cellPosition.add(pInt);
                        }
                        realPositions.add(cellPosition);
                    }
                }
            }
            return thinQueryService.zoomIn(queryName, realPositions);

        } catch (Exception e){
            log.error("Cannot zoom in on query (" + queryName + ")",e);
            throw new WebApplicationException(e);
        }
    }

  /**
   * Drill through on the query result set.
   * @summary Drill through
   * @param queryName The query name
   * @param maxrows The max rows returned
   * @param position The position
   * @param returns The returned dimensions and levels
   * @return A query result set.
   */
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
                rs = thinQueryService.drillthrough(queryName, maxrows, returns);
                rsc = RestUtil.convert(rs);
            } else {
                String[] positions = position.split(":");
                List<Integer> cellPosition = new ArrayList<>();

                for (String p : positions) {
                    Integer pInt = Integer.parseInt(p);
                    cellPosition.add(pInt);
                }
                DrillThroughResult drillthrough = thinQueryService.drillthroughWithCaptions(queryName, cellPosition, maxrows, returns);
                rsc = RestUtil.convert(drillthrough);
            }
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
                try {
                    statement = rs.getStatement();
                } catch (Exception e) {
                    throw new SaikuServiceException(e);
                } finally {
                    try {
                        rs.close();
                        if (statement != null) {
                            statement.close();
                        }
                    } catch (Exception ee) {}

                }
            }
        }
        return rsc;

    }


  /**
   * Export the drill through to a CSV file for further analysis
   * @summary Export to CSV
   * @param queryName The query name
   * @param maxrows The max rows
   * @param position The position
   * @param returns The returned dimensions and levels
   * @return A response containing a CSV file
   */
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
                rs = thinQueryService.drillthrough(queryName, maxrows, returns);
            } else {
                String[] positions = position.split(":");
                List<Integer> cellPosition = new ArrayList<>();

                for (String p : positions) {
                    Integer pInt = Integer.parseInt(p);
                    cellPosition.add(pInt);
                }

                rs = thinQueryService.drillthrough(queryName, cellPosition, maxrows, returns);
            }
            byte[] doc = thinQueryService.exportResultSetCsv(rs);
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
                }
            }
        }


    }

  /**
   * Export PDF with chart
   * @summary Export PDF with Chart.
   * @param queryName The query.
   * @param svg The SVG string
   * @return A response with a PDF file
   */
    @POST
    @Produces({"application/pdf" })
    @Path("/{queryname}/export/pdf")
    public Response exportPdfWithChart(
            @PathParam("queryname")  String queryName,
            @PathParam("svg")  @DefaultValue("") String svg)
    {
        return exportPdfWithChartAndFormat(queryName, null, svg, null);
    }

  /**
   * Export table to PDF.
   * @summary Export to PDF.
   * @param queryName The query name
   * @return A response with a PDF export.
   */
    @GET
    @Produces({"application/pdf" })
    @Path("/{queryname}/export/pdf")
    public Response exportPdf(@PathParam("queryname")  String queryName)
    {
        return exportPdfWithChartAndFormat(queryName, null, null, null);
    }

  /**
   * Export to PDF with cellset format.
   * @summary Export with format
   * @param queryName The query
   * @param format The cellset format
   * @param name The name of the export.
   * @return A response with a PDF
   */
    @GET
    @Produces({"application/pdf" })
    @Path("/{queryname}/export/pdf/{format}")
    public Response exportPdfWithFormat(
            @PathParam("queryname")  String queryName,
            @PathParam("format") String format, @QueryParam("exportname") String name)
    {
        return exportPdfWithChartAndFormat(queryName, format, null, name);
    }

  /**
   * Export PDF with chart and cellset format.
   * @summary Export to PDF with chart and cellset format
   * @param queryName The query name
   * @param format The cell set format
   * @param svg The SVG
   * @param name The export name
   * @return A response with a PDF contained.
   */
    @POST
    @Produces({"application/pdf" })
    @Path("/{queryname}/export/pdf/{format}")
    public Response exportPdfWithChartAndFormat(
            @PathParam("queryname")  String queryName,
            @PathParam("format") String format,
            @FormParam("svg") @DefaultValue("") String svg, @QueryParam("name") String name)
    {

        try {
            CellDataSet cellData = thinQueryService.getFormattedResult(queryName, format);
            QueryResult queryResult = RestUtil.convert(cellData);
            PdfReport pdf = new PdfReport();
            byte[] doc  = pdf.createPdf(queryResult, svg);
            if(name==null || name.equals("")){
                name = "export";
            }
            return Response.ok(doc).type("application/pdf").header(
                    "content-disposition",
                    "attachment; filename = "+name+".pdf").header(
                    "content-length",doc.length).build();
        } catch (Exception e) {
            log.error("Error exporting query to  PDF", e);
            return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
        }
    }

  /**
   * Get HTML export
   * @summary HTML export
   * @param queryname The query name
   * @param format The cellset format
   * @param css The css stylesheet
   * @param tableonly Export table only or chart as well
   * @param wrapcontent Wrap content
   * @return A response with a HTML export.
   */
    @GET
    @Produces({"text/html" })
    @Path("/{queryname}/export/html")
    @ReturnType("java.lang.String")
    public Response exportHtml(
            @PathParam("queryname") String queryname,
            @QueryParam("format") String format,
            @QueryParam("css") @DefaultValue("false") Boolean css,
            @QueryParam("tableonly") @DefaultValue("false") Boolean tableonly,
            @QueryParam("wrapcontent") @DefaultValue("true") Boolean wrapcontent)
    {
        ThinQuery tq = thinQueryService.getContext(queryname).getOlapQuery();
        return exportHtml(tq, format, css, tableonly, wrapcontent);
    }

  /**
   * Get HTML export
   * @summary HTML export
   * @param tq The current thin query model
   * @param format The cellset format
   * @param css The css stylesheet
   * @param tableonly Export table only or chart as well
   * @param wrapcontent Wrap content
   * @return A response with a HTML export.
   */
    @POST
    @Produces({"text/html" })
    @Path("/export/html")
    @ReturnType("java.lang.String")
    public Response exportHtml(
            ThinQuery tq,
            @QueryParam("format") String format,
            @QueryParam("css") @DefaultValue("false") Boolean css,
            @QueryParam("tableonly") @DefaultValue("false") Boolean tableonly,
            @QueryParam("wrapcontent") @DefaultValue("true") Boolean wrapcontent)
    {

        try {
            CellDataSet cs;
            if (StringUtils.isNotBlank(format)) {
                cs = thinQueryService.execute(tq, format);
            } else {
                cs = thinQueryService.execute(tq);
            }
            QueryResult qr = RestUtil.convert(cs);
            String content = JSConverter.convertToHtml(qr, wrapcontent);
            String html = "";
            if (!tableonly) {
                html += "<!DOCTYPE html><html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n";
                if (css) {
                    html += "<style>\n";
                    InputStream is = JSConverter.class.getResourceAsStream("saiku.table.full.css");
                    String cssContent = IOUtils.toString(is);
                    html += cssContent;
                    html += "</style>\n";
                }
                html += "</head>\n<body><div class='workspace_results'>\n";
            }
            html += content;
            if (!tableonly) {
                html += "\n</div></body></html>";
            }
            return Response.ok(html).build();
        } catch (Exception e) {
            log.error("Error exporting query to  HTML", e);
            return Response.serverError().entity(e.getMessage()).status(Status.INTERNAL_SERVER_ERROR).build();
        }
    }

  /**
   * Drill across on a result set
   * @summary Drill across
   * @param queryName The query name
   * @param position The drill position
   * @param returns The dimensions and levels returned
   * @return The new thin query object.
   */
    @POST
    @Produces({"application/json" })
    @Path("/{queryname}/drillacross")
    public ThinQuery drillacross(
            @PathParam("queryname") String queryName,
            @FormParam("position") String position,
            @FormParam("drill") String returns)
    {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"  + "\t/query/" + queryName + "/drillacross\tPOST");
        }

        try {
            String[] positions = position.split(":");
            List<Integer> cellPosition = new ArrayList<>();
            for (String p : positions) {
                Integer pInt = Integer.parseInt(p);
                cellPosition.add(pInt);
            }
            ObjectMapper mapper = new ObjectMapper();

          CollectionType ct =
              mapper.getTypeFactory().constructCollectionType(ArrayList.class, String.class);

          JavaType st = mapper.getTypeFactory().uncheckedSimpleType(String.class);


            Map<String, List<String>> levels = mapper.readValue(returns, mapper.getTypeFactory().constructMapType(Map.class, st, ct));
          return thinQueryService.drillacross(queryName, cellPosition, levels);

        }
        catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")",e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new WebApplicationException(Response.serverError().entity(error).build());

        }
    }


  public ThinQueryService getThinQueryService() {
    return thinQueryService;
  }
}
