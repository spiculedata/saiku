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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.web.rest.util.ServletUtil;
import org.saiku.web.svg.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Paul Stoellberger
 *
 */
@Path("/saiku/{username}/export")
@XmlAccessorType(XmlAccessType.NONE)
public class ExporterResource {

    private static final Logger log = LoggerFactory.getLogger(ExporterResource.class);

    private ISaikuRepository repository;

    private Query2Resource query2Resource;

    public void setQuery2Resource(Query2Resource qr) {
        this.query2Resource = qr;
    }

    public void setRepository(ISaikuRepository repository) {
        this.repository = repository;
    }

    /**
     * Export query to excel file format.
     * @summary Export to excel.
     * @param file The file
     * @param formatter The cellset formatter
     * @param name The name
     * @param servletRequest The servlet request.
     * @return A response containing an excel file.
     */
    @GET
    @Produces({"application/json"})
    @Path("/saiku/xls")
    public Response exportExcel(
            @QueryParam("file") String file,
            @QueryParam("formatter") String formatter,
            @QueryParam("name") String name,
            @Context HttpServletRequest servletRequest) {
        try {
            Response f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getEntity());
            String queryName = UUID.randomUUID().toString();
            // fileContent = ServletUtil.replaceParameters(servletRequest, fileContent);
            //			queryResource.createQuery(queryName,  null,  null, null, fileContent, queryName, null);
            //			queryResource.execute(queryName, formatter, 0);
            Map<String, String> parameters = ServletUtil.getParameters(servletRequest);
            ThinQuery tq = query2Resource.createQuery(queryName, fileContent, null, null);
            if (parameters != null) {
                tq.getParameters().putAll(parameters);
            }
            if (StringUtils.isNotBlank(formatter)) {
                HashMap<String, Object> p = new HashMap<>();
                p.put("saiku.olap.result.formatter", formatter);
                if (tq.getProperties() == null) {
                    tq.setProperties(p);
                } else {
                    tq.getProperties().putAll(p);
                }
            }
            query2Resource.execute(tq, null);
            return query2Resource.getQueryExcelExport(queryName, formatter, name);
        } catch (Exception e) {
            log.error("Error exporting XLS for file: " + file, e);
            return Response.serverError()
                    .entity(e.getMessage())
                    .status(Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    /**
     * Export the query to a CSV file format.
     * @summary Export to CSV.
     * @param file The file
     * @param formatter The cellset formatter
     * @param servletRequest The servlet request
     * @return A response containing a CSV file.
     */
    @GET
    @Produces({"application/json"})
    @Path("/saiku/csv")
    public Response exportCsv(
            @QueryParam("file") String file,
            @QueryParam("formatter") String formatter,
            @Context HttpServletRequest servletRequest) {
        try {
            Response f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getEntity());
            // fileContent = ServletUtil.replaceParameters(servletRequest, fileContent);
            String queryName = UUID.randomUUID().toString();
            //			query2Resource.createQuery(null,  null,  null, null, fileContent, queryName, null);
            //			query2Resource.execute(queryName,formatter, 0);
            Map<String, String> parameters = ServletUtil.getParameters(servletRequest);
            ThinQuery tq = query2Resource.createQuery(queryName, fileContent, null, null);
            if (parameters != null) {
                tq.getParameters().putAll(parameters);
            }

            if (StringUtils.isNotBlank(formatter)) {
                HashMap<String, Object> p = new HashMap<>();
                p.put("saiku.olap.result.formatter", formatter);
                if (tq.getProperties() == null) {
                    tq.setProperties(p);
                } else {
                    tq.getProperties().putAll(p);
                }
            }
            query2Resource.execute(tq, null);
            return query2Resource.getQueryCsvExport(queryName);
        } catch (Exception e) {
            log.error("Error exporting CSV for file: " + file, e);
            return Response.serverError()
                    .entity(e.getMessage())
                    .status(Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    /**
     * Export the query response to JSON.
     * @summary Export to JSON
     * @param file The file
     * @param formatter The cellset formatter
     * @param servletRequest The servlet request
     * @return A response containing a JSON query response.
     */
    @GET
    @Produces({"application/json"})
    @Path("/saiku/json")
    public Response exportJson(
            @QueryParam("file") String file,
            @QueryParam("formatter") String formatter,
            @Context HttpServletRequest servletRequest) {
        try {
            Response f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getEntity());
            fileContent = ServletUtil.replaceParameters(servletRequest, fileContent);
            String queryName = UUID.randomUUID().toString();
            //			query2Resource.createQuery(null,  null,  null, null, fileContent, queryName, null);
            //			QueryResult qr = query2Resource.execute(queryName, formatter, 0);
            Map<String, String> parameters = ServletUtil.getParameters(servletRequest);
            ThinQuery tq = query2Resource.createQuery(queryName, fileContent, null, null);
            if (parameters != null) {
                tq.getParameters().putAll(parameters);
            }
            if (StringUtils.isNotBlank(formatter)) {
                HashMap<String, Object> p = new HashMap<>();
                p.put("saiku.olap.result.formatter", formatter);
                if (tq.getProperties() == null) {
                    tq.setProperties(p);
                } else {
                    tq.getProperties().putAll(p);
                }
            }
            return query2Resource.execute(tq, null);
        } catch (Exception e) {
            log.error("Error exporting JSON for file: " + file, e);
            return Response.serverError()
                    .entity(e.getMessage())
                    .status(Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    /**
     * Export the current resultset to an HTML file.
     * @summary Export to HTML
     * @param file The file
     * @param formatter The formatter
     * @param css The css
     * @param tableonly Table only, or include chart
     * @param wrapcontent Wrap content
     * @param servletRequest The servlet reaquest.
     * @return A reponse containing the HTML export.
     */
    @GET
    @Produces({"text/html"})
    @Path("/saiku/html")
    public Response exportHtml(
            @QueryParam("file") String file,
            @QueryParam("formatter") String formatter,
            @QueryParam("css") @DefaultValue("false") Boolean css,
            @QueryParam("tableonly") @DefaultValue("false") Boolean tableonly,
            @QueryParam("wrapcontent") @DefaultValue("true") Boolean wrapcontent,
            @Context HttpServletRequest servletRequest) {
        try {
            Response f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getEntity());
            fileContent = ServletUtil.replaceParameters(servletRequest, fileContent);
            String queryName = UUID.randomUUID().toString();
            query2Resource.createQuery(queryName, fileContent, null, null);
            return query2Resource.exportHtml(queryName, formatter, css, tableonly, wrapcontent);
        } catch (Exception e) {
            log.error("Error exporting JSON for file: " + file, e);
            return Response.serverError()
                    .entity(e.getMessage())
                    .status(Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    /**
     * Export chart to a file.
     * @summary Export Chart.
     * @param type The export type (png, svg, jpeg)
     * @param svg The SVG
     * @param size The size
     * @param name The name
     * @return A reponse containing the chart export.
     */
    @POST
    @Produces({"image/*"})
    @Path("/saiku/chart")
    public Response exportChart(
            @FormParam("type") @DefaultValue("png") String type,
            @FormParam("svg") String svg,
            @FormParam("size") Integer size,
            @FormParam("name") String name) {
        try {
            if (StringUtils.isBlank(svg)) {
                throw new Exception("Missing 'svg' parameter");
            }
            if (getVersion() != null && !getVersion().contains("EE")) {
                String watermark =
                        IOUtils.toString(ExporterResource.class.getResource("/org/saiku/web/svg/watermark.svg"));
                svg = svg.replace("</svg>", watermark + "</svg>");
            }
            final InputStream in = new ByteArrayInputStream(svg.getBytes("UTF-8"));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.flush();
            Converter converter = Converter.byType(type.toUpperCase());
            if (converter == null) {
                throw new Exception("Missing converter.");
            }
            converter.convert(in, out, size);
            byte[] b = out.toByteArray();

            if (name == null || name.equals("")) {
                name = "chart-" + new SimpleDateFormat("yyyy-MM-dd-hhmmss").format(new Date());
            }
            return Response.ok(b)
                    .type(converter.getContentType())
                    .header("content-disposition", "attachment; filename = " + name + "." + converter.getExtension())
                    .header("content-length", b.length)
                    .build();

        } catch (Exception e) {
            log.error("Error exporting Chart to  " + type, e);
            return Response.serverError()
                    .entity(e.getMessage())
                    .status(Status.INTERNAL_SERVER_ERROR)
                    .build();
        }
    }

    /**
     * Get the version.
     * @summary Get the Saiku version.
     * @return A String containing the current version.
     */
    private static String getVersion() {
        Properties prop = new Properties();
        InputStream input = null;
        String version = "";
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        InputStream is = classloader.getResourceAsStream("org/saiku/web/rest/resources/version.properties");
        try {
            prop.load(is);
            version = prop.getProperty("VERSION");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return version;
    }
}
