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
import org.apache.commons.lang.StringUtils;
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
     */
    @GetMapping(path = "/saiku/xls", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportExcel(
            @RequestParam(name = "file", required = false) String file,
            @RequestParam(name = "formatter", required = false) String formatter,
            @RequestParam(name = "name", required = false) String name,
            HttpServletRequest servletRequest) {
        try {
            ResponseEntity<?> f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getBody());
            String queryName = UUID.randomUUID().toString();
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Export the query to a CSV file format.
     */
    @GetMapping(path = "/saiku/csv", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportCsv(
            @RequestParam(name = "file", required = false) String file,
            @RequestParam(name = "formatter", required = false) String formatter,
            HttpServletRequest servletRequest) {
        try {
            ResponseEntity<?> f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getBody());
            String queryName = UUID.randomUUID().toString();
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Export the query response to JSON.
     */
    @GetMapping(path = "/saiku/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportJson(
            @RequestParam(name = "file", required = false) String file,
            @RequestParam(name = "formatter", required = false) String formatter,
            HttpServletRequest servletRequest) {
        try {
            ResponseEntity<?> f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getBody());
            fileContent = ServletUtil.replaceParameters(servletRequest, fileContent);
            String queryName = UUID.randomUUID().toString();
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Export the current resultset to an HTML file.
     */
    @GetMapping(path = "/saiku/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<?> exportHtml(
            @RequestParam(name = "file", required = false) String file,
            @RequestParam(name = "formatter", required = false) String formatter,
            @RequestParam(name = "css", defaultValue = "false") Boolean css,
            @RequestParam(name = "tableonly", defaultValue = "false") Boolean tableonly,
            @RequestParam(name = "wrapcontent", defaultValue = "true") Boolean wrapcontent,
            HttpServletRequest servletRequest) {
        try {
            ResponseEntity<?> f = repository.getResource(file);
            String fileContent = new String((byte[]) f.getBody());
            fileContent = ServletUtil.replaceParameters(servletRequest, fileContent);
            String queryName = UUID.randomUUID().toString();
            query2Resource.createQuery(queryName, fileContent, null, null);
            return query2Resource.exportHtml(queryName, formatter, css, tableonly, wrapcontent);
        } catch (Exception e) {
            log.error("Error exporting JSON for file: " + file, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Export chart to a file.
     */
    @PostMapping(path = "/saiku/chart", produces = "image/*")
    public ResponseEntity<?> exportChart(
            @RequestParam(name = "type", defaultValue = "png") String type,
            @RequestParam(name = "svg", required = false) String svg,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "name", required = false) String name) {
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
            return ResponseEntity.ok()
                    .header("Content-Type", converter.getContentType())
                    .header("content-disposition", "attachment; filename = " + name + "." + converter.getExtension())
                    .header("content-length", String.valueOf(b.length))
                    .body(b);

        } catch (Exception e) {
            log.error("Error exporting Chart to  " + type, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Get the version.
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
