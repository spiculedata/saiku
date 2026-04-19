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

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import javax.servlet.ServletException;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.saiku.olap.dto.*;
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
import org.saiku.web.export.JSConverter;
import org.saiku.web.rest.objects.MdxQueryObject;
import org.saiku.web.rest.objects.SavedQuery;
import org.saiku.web.rest.objects.SelectionRestObject;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.saiku.web.rest.util.RestUtil;
import org.saiku.web.rest.util.ServletUtil;
import org.saiku.web.svg.PdfReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QueryServlet contains all the methods required when manipulating an OLAP Query.
 * @author Tom Barber
 *
 */
@Component
@RestController
@RequestMapping("/saiku/{username}/query")
@XmlAccessorType(XmlAccessType.NONE)
@Deprecated
public class QueryResource {

    private static final Logger log = LoggerFactory.getLogger(QueryResource.class);

    private OlapQueryService olapQueryService;
    private ISaikuRepository repository;

    // @Autowired
    public void setOlapQueryService(OlapQueryService olapqs) {
        olapQueryService = olapqs;
    }

    // @Autowired
    public void setRepository(ISaikuRepository repository) {
        this.repository = repository;
    }

    // @Autowired
    public void setOlapDiscoverService(OlapDiscoverService olapds) {
        OlapDiscoverService olapDiscoverService = olapds;
    }

    /*
     * Query methods
     */

    /**
     * Return a list of open queries.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> getQueries() {
        return olapQueryService.getQueries();
    }

    @GetMapping(path = "/{queryname}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SaikuQuery getQuery(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "\tGET");
        }
        return olapQueryService.getQuery(queryName);
    }

    /**
     * Delete query from the query pool.
     * @return a HTTP 410(Works) or HTTP 404(Call failed).
     */
    @DeleteMapping("/{queryname}")
    public HttpStatus deleteQuery(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "\tDELETE");
        }
        try {
            olapQueryService.deleteQuery(queryName);
            return (HttpStatus.GONE);
        } catch (Exception e) {
            log.error("Cannot delete query (" + queryName + ")", e);
            throw new RuntimeException(String.valueOf(e));
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
    @PostMapping(path = "/{queryname}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SaikuQuery createQuery(
            @RequestParam(name = "connection", required = false) String connectionName,
            @RequestParam(name = "cube", required = false) String cubeName,
            @RequestParam(name = "catalog", required = false) String catalogName,
            @RequestParam(name = "schema", required = false) String schemaName,
            @RequestParam(name = "xml", required = false) String xmlOld,
            @PathVariable("queryname") String queryName,
            Map<String, String> formParams)
            throws ServletException {
        try {
            String file = null, xml = null;
            if (formParams != null) {
                xml = formParams.containsKey("xml") ? formParams.get("xml") : xmlOld;
                file = formParams.containsKey("file") ? formParams.get("file") : null;
                if (StringUtils.isNotBlank(file)) {
                    ResponseEntity<?> f = repository.getResource(file);
                    xml = new String((byte[]) f.getBody());
                }
            } else {
                xml = xmlOld;
            }
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "\tPOST\t xml:" + (xml == null) + " file:" + (file));
            }
            SaikuCube cube = new SaikuCube(connectionName, cubeName, cubeName, cubeName, catalogName, schemaName);
            if (StringUtils.isNotBlank(xml)) {
                String query = ServletUtil.replaceParameters(formParams, xml);
                return olapQueryService.createNewOlapQuery(queryName, query);
            }
            return olapQueryService.createNewOlapQuery(queryName, cube);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the query properties.
     * @param queryName
     * @return
     */
    @GetMapping(path = "/{queryname}/properties", produces = MediaType.APPLICATION_JSON_VALUE)
    public Properties getProperties(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/properties\tGET");
        }
        return olapQueryService.getProperties(queryName);
    }

    /**
     * Set the query properties
     * @param queryName
     * @param properties
     * @return
     */
    @PostMapping(path = "/{queryname}/properties", produces = MediaType.APPLICATION_JSON_VALUE)
    public Properties setProperties(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "properties", required = false) String properties) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/properties\tPOST");
        }
        try {
            Properties props = new Properties();
            StringReader sr = new StringReader(properties);
            props.load(sr);
            return olapQueryService.setProperties(queryName, props);
        } catch (Exception e) {
            log.error("Cannot set properties for query (" + queryName + ")", e);
            return null;
        }
    }

    @PostMapping(path = "/{queryname}/properties/{propertyKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Properties setProperties(
            @PathVariable("queryname") String queryName,
            @PathVariable("propertyKey") String propertyKey,
            @RequestParam(name = "propertyValue", required = false) String propertyValue) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/properties/" + propertyKey + "\tPOST");
        }
        try {
            Properties props = new Properties();
            props.put(propertyKey, propertyValue);
            return olapQueryService.setProperties(queryName, props);
        } catch (Exception e) {
            log.error("Cannot set property (" + propertyKey + " ) for query (" + queryName + ")", e);
            return null;
        }
    }

    @GetMapping("/{queryname}/mdx")
    public MdxQueryObject getMDXQuery(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/mdx/\tGET");
        }
        try {
            String mdx = olapQueryService.getMDXQuery(queryName);
            return new MdxQueryObject(mdx);
        } catch (Exception e) {
            log.error("Cannot get mdx for query (" + queryName + ")", e);
            return null;
        }
    }

    @PostMapping(path = "/{queryname}/mdx", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void setMDXQuery(
            @PathVariable("queryname") String queryName, @RequestParam(name = "mdx", required = false) String mdx) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/mdx/\tPOST");
        }
        try {
            olapQueryService.setMdx(queryName, mdx);
        } catch (Exception e) {
            log.error("Cannot set mdx for query (" + queryName + ")", e);
        }
    }

    @GetMapping(path = "/{queryname}/xml", produces = MediaType.APPLICATION_JSON_VALUE)
    public SavedQuery getQueryXml(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/xml/\tGET");
        }
        try {
            String xml = olapQueryService.getQueryXml(queryName);
            return new SavedQuery(queryName, null, xml);
        } catch (Exception e) {
            log.error("Cannot get xml for query (" + queryName + ")", e);
            return null;
        }
    }

    @GetMapping(path = "/{queryname}/export/xls", produces = "application/vnd.ms-excel")
    public ResponseEntity<?> getQueryExcelExport(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/xls/\tGET");
        }
        return getQueryExcelExport(queryName, "flattened");
    }

    @GetMapping(path = "/{queryname}/export/xls/{format}", produces = "application/vnd.ms-excel")
    public ResponseEntity<?> getQueryExcelExport(
            @PathVariable("queryname") String queryName, @PathVariable("format") String format) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/xls/" + format + "\tGET");
        }
        try {
            byte[] doc = olapQueryService.getExport(queryName, "xls", format);
            String name = SaikuProperties.webExportExcelName + "." + SaikuProperties.webExportExcelFormat;
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name)
                    .header("content-length", String.valueOf(doc.length))
                    .body(doc);
        } catch (Exception e) {
            log.error("Cannot get excel for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping(path = "/{queryname}/export/csv", produces = "text/csv")
    public ResponseEntity<?> getQueryCsvExport(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/csv\tGET");
        }
        return getQueryCsvExport(queryName, "flattened");
    }

    @GetMapping(path = "/{queryname}/export/csv/{format}", produces = "text/csv")
    public ResponseEntity<?> getQueryCsvExport(
            @PathVariable("queryname") String queryName, @PathVariable("format") String format) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/csv/" + format + "\tGET");
        }
        try {
            byte[] doc = olapQueryService.getExport(queryName, "csv", format);
            String name = SaikuProperties.webExportCsvName;
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name + ".csv")
                    .header("content-length", String.valueOf(doc.length))
                    .body(doc);
        } catch (Exception e) {
            log.error("Cannot get csv for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping(path = "/{queryname}/export/pdf", produces = "application/pdf")
    public ResponseEntity<?> exportPdfWithChart(
            @PathVariable("queryname") String queryName, @RequestParam(name = "svg", defaultValue = "") String svg) {
        return exportPdfWithChartAndFormat(queryName, null, svg);
    }

    @GetMapping(path = "/{queryname}/export/pdf", produces = "application/pdf")
    public ResponseEntity<?> exportPdf(@PathVariable("queryname") String queryName) {
        return exportPdfWithChartAndFormat(queryName, null, null);
    }

    @GetMapping(path = "/{queryname}/export/pdf/{format}", produces = "application/pdf")
    public ResponseEntity<?> exportPdfWithFormat(
            @PathVariable("queryname") String queryName, @PathVariable("format") String format) {
        return exportPdfWithChartAndFormat(queryName, format, null);
    }

    @PostMapping(path = "/{queryname}/export/pdf/{format}", produces = "application/pdf")
    public ResponseEntity<?> exportPdfWithChartAndFormat(
            @PathVariable("queryname") String queryName,
            @PathVariable("format") String format,
            @RequestParam(name = "svg", defaultValue = "") String svg) {

        try {
            PdfReport pdf = new PdfReport();
            CellDataSet cs = null;
            if (StringUtils.isNotBlank(format)) {
                cs = olapQueryService.execute(queryName, format);
            } else {
                cs = olapQueryService.execute(queryName);
            }

            byte[] doc = pdf.pdf(cs, svg);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("content-disposition", "attachment; filename = export.pdf")
                    .header("content-length", String.valueOf(doc.length))
                    .body(doc);
        } catch (Exception e) {
            log.error("Error exporting query to  PDF", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping(path = "/{queryname}/export/html/{format}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<?> exportHtml(
            @PathVariable("queryname") String queryName,
            @PathVariable("format") String format,
            @RequestParam(name = "css", defaultValue = "false") Boolean css,
            @RequestParam(name = "tableonly", defaultValue = "false") Boolean tableonly,
            @RequestParam(name = "wrapcontent", defaultValue = "true") Boolean wrapcontent) {
        try {
            CellDataSet cs = null;
            if (StringUtils.isNotBlank(format)) {
                cs = olapQueryService.execute(queryName, format);
            } else {
                cs = olapQueryService.execute(queryName);
            }
            QueryResult qr = RestUtil.convert(cs);
            String content = JSConverter.convertToHtml(qr, wrapcontent);
            String html = "";
            if (!tableonly) {
                html +=
                        "<!DOCTYPE html><html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n";
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
            return ResponseEntity.ok(html);
        } catch (Exception e) {
            log.error("Error exporting query to HTML", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @DeleteMapping("/{queryname}/result")
    public HttpStatus cancel(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/result\tDELETE");
        }
        try {

            olapQueryService.cancel(queryName);
            return HttpStatus.OK;
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    @GetMapping(path = "/{queryname}/result", produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResult execute(
            @PathVariable("queryname") String queryName, @RequestParam(name = "limit", defaultValue = "0") int limit) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/result\tGET");
        }
        try {

            CellDataSet cs = olapQueryService.execute(queryName);
            return RestUtil.convert(cs, limit);
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return new QueryResult(error);
        }
    }

    @PostMapping(path = "/{queryname}/result/{format}", produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResult executeMdx(
            @PathVariable("queryname") String queryName,
            @PathVariable("format") String formatter,
            @RequestParam(name = "mdx", required = false) String mdx,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/result" + formatter + "\tPOST");
        }
        try {
            ICellSetFormatter icf;
            formatter = formatter == null ? "" : formatter.toLowerCase();
            switch (formatter) {
                case "flat":
                    icf = new CellSetFormatter();
                    break;
                case "hierarchical":
                    icf = new HierarchicalCellSetFormatter();
                    break;
                case "flattened":
                    icf = new FlattenedCellSetFormatter();
                    break;
                default:
                    icf = new FlattenedCellSetFormatter();
                    break;
            }

            olapQueryService.qm2mdx(queryName);

            if (olapQueryService.isMdxDrillthrough(queryName, mdx)) {
                Long start = (new Date()).getTime();
                ResultSet rs = olapQueryService.drillthrough(queryName, mdx);
                QueryResult rsc = RestUtil.convert(rs);
                Long runtime = (new Date()).getTime() - start;
                rsc.setRuntime(runtime.intValue());
                return rsc;
            }
            CellDataSet cs = olapQueryService.executeMdx(queryName, mdx, icf);
            return RestUtil.convert(cs, limit);
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ") using mdx:\n" + mdx, e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return new QueryResult(error);
        }
    }

    @PostMapping(path = "/{queryname}/result", produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResult executeMdx(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "mdx", required = false) String mdx,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/result\tPOST\t" + mdx);
        }
        try {
            return executeMdx(queryName, null, mdx, limit);
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ") using mdx:\n" + mdx, e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return new QueryResult(error);
        }
    }

    @GetMapping(
            path = "/{queryname}/result/metadata/dimensions/{dimension}/hierarchies/{hierarchy}/levels/{level}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SimpleCubeElement> getLevelMembers(
            @PathVariable("queryname") String queryName,
            @PathVariable("dimension") String dimensionName,
            @PathVariable("hierarchy") String hierarchyName,
            @PathVariable("level") String levelName,
            @RequestParam(name = "result", defaultValue = "true") boolean result,
            @RequestParam(name = "search", required = false) String searchString,
            @RequestParam(name = "searchlimit", defaultValue = "-1") int searchLimit) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"
                    + "\t/query/" + queryName + "/result/metadata/dimensions/" + dimensionName
                    + "/hierarchies/" + hierarchyName + "/levels/" + levelName + "\tGET");
        }
        try {
            return olapQueryService.getResultMetadataMembers(
                    queryName, result, dimensionName, hierarchyName, levelName, searchString, searchLimit);
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(String.valueOf(error));
        }
    }

    @PostMapping(path = "/{queryname}/qm2mdx", produces = MediaType.APPLICATION_JSON_VALUE)
    public SaikuQuery transformQm2Mdx(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/qm2mdx\tPOST\t");
        }
        try {
            olapQueryService.qm2mdx(queryName);
            return olapQueryService.getQuery(queryName);
        } catch (Exception e) {
            log.error("Cannot transform Qm2Mdx query (" + queryName + ")", e);
        }
        return null;
    }

    @GetMapping(path = "/{queryname}/explain", produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResult getExplainPlan(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/explain\tGET");
        }
        QueryResult rsc;
        ResultSet rs = null;
        try {
            Long start = (new Date()).getTime();
            rs = olapQueryService.explain(queryName);
            rsc = RestUtil.convert(rs);
            Long runtime = (new Date()).getTime() - start;
            rsc.setRuntime(runtime.intValue());

        } catch (Exception e) {
            log.error("Cannot get explain plan for query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            rsc = new QueryResult(error);
        }
        // no need to close resultset, its an EmptyResultset
        return rsc;
    }

    @GetMapping(path = "/{queryname}/drillthrough", produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResult drillthrough(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "maxrows", defaultValue = "100") Integer maxrows,
            @RequestParam(name = "position", required = false) String position,
            @RequestParam(name = "returns", required = false) String returns) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/drillthrough\tGET");
        }
        QueryResult rsc;
        ResultSet rs = null;
        try {
            Long start = (new Date()).getTime();
            if (position == null) {
                rs = olapQueryService.drillthrough(queryName, maxrows, returns);
            } else {
                String[] positions = position.split(":");
                List<Integer> cellPosition = new ArrayList<>();

                for (String p : positions) {
                    Integer pInt = Integer.parseInt(p);
                    cellPosition.add(pInt);
                }

                rs = olapQueryService.drillthrough(queryName, cellPosition, maxrows, returns);
            }
            rsc = RestUtil.convert(rs);
            Long runtime = (new Date()).getTime() - start;
            rsc.setRuntime(runtime.intValue());

        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            rsc = new QueryResult(error);

        } finally {
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
                    } catch (Exception ee) {
                    }

                    rs = null;
                }
            }
        }
        return rsc;
    }

    @PostMapping(path = "/{queryname}/drillacross", produces = MediaType.APPLICATION_JSON_VALUE)
    public SaikuQuery drillacross(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "position", required = false) String position,
            @RequestParam(name = "drill", required = false) String returns) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/drillacross\tPOST");
        }
        try {
            String[] positions = position.split(":");
            List<Integer> cellPosition = new ArrayList<>();
            for (String p : positions) {
                Integer pInt = Integer.parseInt(p);
                cellPosition.add(pInt);
            }
            ObjectMapper mapper = new ObjectMapper();
            JavaType ct = mapper.getTypeFactory().constructCollectionType(ArrayList.class, String.class);

            JavaType st = mapper.getTypeFactory().uncheckedSimpleType(String.class);
            Map<String, List<String>> levels =
                    mapper.readValue(returns, mapper.getTypeFactory().constructMapType(Map.class, st, ct));

            return olapQueryService.drillacross(queryName, cellPosition, levels);
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(String.valueOf(error));
        }
    }

    @GetMapping(path = "/{queryname}/drillthrough/export/csv", produces = "text/csv")
    public ResponseEntity<?> getDrillthroughExport(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "maxrows", defaultValue = "100") Integer maxrows,
            @RequestParam(name = "position", required = false) String position,
            @RequestParam(name = "returns", required = false) String returns) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/drillthrough/export/csv (maxrows:" + maxrows + " position"
                    + position + ")\tGET");
        }
        ResultSet rs = null;

        try {
            if (position == null) {
                rs = olapQueryService.drillthrough(queryName, maxrows, returns);
            } else {
                String[] positions = position.split(":");
                List<Integer> cellPosition = new ArrayList<>();

                for (String p : positions) {
                    Integer pInt = Integer.parseInt(p);
                    cellPosition.add(pInt);
                }

                rs = olapQueryService.drillthrough(queryName, cellPosition, maxrows, returns);
            }
            byte[] doc = olapQueryService.exportResultSetCsv(rs);
            String name = SaikuProperties.webExportCsvName;
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name + "-drillthrough.csv")
                    .header("content-length", String.valueOf(doc.length))
                    .body(doc);

        } catch (Exception e) {
            log.error("Cannot export drillthrough query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
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

    @GetMapping(path = "/{queryname}/result/{format}", produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResult execute(
            @PathVariable("queryname") String queryName,
            @PathVariable("format") String formatter,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/result" + formatter + "\tGET");
        }
        try {
            CellDataSet cs = olapQueryService.execute(queryName, formatter);
            return RestUtil.convert(cs, limit);
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
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
    @GetMapping(path = "/{queryname}/axis/{axis}", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SaikuDimensionSelection> getAxisInfo(
            @PathVariable("queryname") String queryName, @PathVariable("axis") String axisName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "\tGET");
        }
        return olapQueryService.getAxisSelection(queryName, axisName);
    }

    /**
     * Remove all dimensions and selections on an axis
     * @param queryName the name of the query.
     * @param axisName the name of the axis.
     */
    @DeleteMapping(path = "/{queryname}/axis/{axis}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> clearAxis(
            @PathVariable("queryname") String queryName, @PathVariable("axis") String axisName) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "\tDELETE");
            }
            axisName = StringUtils.isNotBlank(axisName) ? axisName.toUpperCase() : null;
            if (axisName != null) {
                IQuery query = olapQueryService.clearAxis(queryName, axisName);
                return ResponseEntity.ok(ObjectUtil.convert(query));
            }
            throw new Exception("Clear Axis: Axis name cannot be null");
        } catch (Exception e) {
            log.error("Cannot clear axis for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @DeleteMapping(path = "/{queryname}/axis/", produces = MediaType.APPLICATION_JSON_VALUE)
    public void clearAllAxisSelections(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis\tDELETE");
        }
        olapQueryService.resetQuery(queryName);
    }

    @PutMapping(path = "/{queryname}/swapaxes", produces = MediaType.APPLICATION_JSON_VALUE)
    public SaikuQuery swapAxes(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/swapaxes\tPUT");
        }
        IQuery query = olapQueryService.swapAxes(queryName);
        return ObjectUtil.convert(query);
    }

    @PostMapping(path = "/{queryname}/cell/{position}/{value}", produces = MediaType.APPLICATION_JSON_VALUE)
    public HttpStatus setCell(
            @PathVariable("queryname") String queryName,
            @PathVariable("position") String position,
            @PathVariable("value") String value) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/cell/" + position + "/" + value + "\tGET");
        }
        String[] positions = position.split(":");
        List<Integer> cellPosition = new ArrayList<>();

        for (String p : positions) {
            Integer pInt = Integer.parseInt(p);
            cellPosition.add(pInt);
        }

        olapQueryService.setCellValue(queryName, cellPosition, value);
        return HttpStatus.OK;
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
    @GetMapping(path = "/{queryname}/axis/{axis}/dimension/{dimension}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SaikuDimensionSelection getAxisDimensionInfo(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axis,
            @PathVariable("dimension") String dimension) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axis + "/dimension/" + dimension + "\tGET");
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
    @PostMapping("/{queryname}/axis/{axis}/dimension/{dimension}")
    public ResponseEntity<?> moveDimension(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @RequestParam(name = "position", defaultValue = "-1") int position) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "\tPOST");
            }
            olapQueryService.moveDimension(queryName, axisName, dimensionName, position);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Cannot move dimension " + dimensionName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Delete a dimension.
     * @return
     */
    @DeleteMapping("/{queryname}/axis/{axis}/dimension/{dimension}")
    public ResponseEntity<?> deleteDimension(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "\tDELETE");
            }
            olapQueryService.removeDimension(queryName, axisName, dimensionName);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Cannot remove dimension " + dimensionName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PutMapping(path = "/{queryname}/zoomin", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public SaikuQuery zoomIn(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "selections", required = false) String positionListString) {
        try {

            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/zoomIn\tPUT");
            }
            List<List<Integer>> realPositions = new ArrayList<>();
            if (StringUtils.isNotBlank(positionListString)) {
                ObjectMapper mapper = new ObjectMapper();
                String[] positions = mapper.readValue(
                        positionListString, mapper.getTypeFactory().constructArrayType(String.class));
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
            IQuery query = olapQueryService.zoomIn(queryName, realPositions);
            return ObjectUtil.convert(query);

        } catch (Exception e) {
            log.error("Cannot updates selections for query (" + queryName + ")", e);
            throw new RuntimeException(e);
        }
    }

    @PutMapping(
            path = "/{queryname}/axis/{axis}/dimension/{dimension}/",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> updateSelections(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @RequestParam(name = "selections", required = false) String selectionJSON) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "\tPUT\t");
            }

            if (selectionJSON != null) {
                ObjectMapper mapper = new ObjectMapper();
                List<SelectionRestObject> selections = mapper.readValue(
                        selectionJSON,
                        mapper.getTypeFactory().constructCollectionType(ArrayList.class, SelectionRestObject.class));

                // remove stuff first, then add, removing removes all selections for that level first
                for (SelectionRestObject selection : selections) {
                    if (selection.getType() != null
                            && "member".equals(selection.getType().toLowerCase())) {
                        if (selection.getAction() != null
                                && "delete".equals(selection.getAction().toLowerCase())) {
                            olapQueryService.removeMember(
                                    queryName, dimensionName, selection.getUniquename(), "MEMBER");
                        }
                    }
                    if (selection.getType() != null
                            && "level".equals(selection.getType().toLowerCase())) {
                        if (selection.getAction() != null
                                && "delete".equals(selection.getAction().toLowerCase())) {
                            olapQueryService.removeLevel(
                                    queryName, dimensionName, selection.getHierarchy(), selection.getUniquename());
                        }
                    }
                }
                for (SelectionRestObject selection : selections) {
                    if (selection.getType() != null
                            && "member".equals(selection.getType().toLowerCase())) {
                        if (selection.getAction() != null
                                && "add".equals(selection.getAction().toLowerCase())) {
                            olapQueryService.includeMember(
                                    queryName,
                                    dimensionName,
                                    selection.getUniquename(),
                                    "MEMBER",
                                    selection.getTotalsFunction(),
                                    -1);
                        }
                    }
                    if (selection.getType() != null
                            && "level".equals(selection.getType().toLowerCase())) {
                        if (selection.getAction() != null
                                && "add".equals(selection.getAction().toLowerCase())) {
                            olapQueryService.includeLevel(
                                    queryName,
                                    dimensionName,
                                    selection.getHierarchy(),
                                    selection.getUniquename(),
                                    selection.getTotalsFunction());
                        }
                    }
                }
                SaikuDimensionSelection dimsels = getAxisDimensionInfo(queryName, axisName, dimensionName);
                if (dimsels != null && dimsels.getSelections().size() == 0) {
                    moveDimension(queryName, "UNUSED", dimensionName, -1);
                }
                return ResponseEntity.ok().build();
            }
            throw new Exception("Form did not contain 'selections' parameter");
        } catch (Exception e) {
            log.error("Cannot updates selections for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @DeleteMapping(
            path = "/{queryname}/axis/{axis}/dimension/{dimension}/member/",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> removeMembers(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            Map<String, String> formParams) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "\tPUT");
            }
            if (formParams.containsKey("selections")) {
                String selectionJSON = formParams.get("selections");
                ObjectMapper mapper = new ObjectMapper(); // can reuse, share globally
                List<SelectionRestObject> selections = mapper.readValue(
                        selectionJSON,
                        mapper.getTypeFactory().constructCollectionType(ArrayList.class, SelectionRestObject.class));
                for (SelectionRestObject member : selections) {
                    removeMember("MEMBER", queryName, axisName, dimensionName, member.getUniquename());
                }
                return ResponseEntity.ok().build();
            }
            throw new Exception("Form did not contain 'selections' parameter");
        } catch (Exception e) {
            log.error("Cannot updates selections for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
    /**
     * Move a member.
     * @return
     */
    @PostMapping("/{queryname}/axis/{axis}/dimension/{dimension}/member/{member}")
    public ResponseEntity<?> includeMember(
            @RequestParam(name = "selection", defaultValue = "MEMBER") String selectionType,
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @PathVariable("member") String uniqueMemberName,
            @RequestParam(name = "position", defaultValue = "-1") int position,
            @RequestParam(name = "memberposition", defaultValue = "-1") int memberposition) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "/member/" + uniqueMemberName + "\tPOST");
            }
            olapQueryService.moveDimension(queryName, axisName, dimensionName, position);

            boolean ret = olapQueryService.includeMember(
                    queryName, dimensionName, uniqueMemberName, selectionType, memberposition);
            if (ret) {
                return ResponseEntity.status(HttpStatus.CREATED).build();
            } else {
                throw new Exception("Couldn't include member " + dimensionName);
            }
        } catch (Exception e) {
            log.error("Cannot include member " + dimensionName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @DeleteMapping("/{queryname}/axis/{axis}/dimension/{dimension}/member/{member}")
    public ResponseEntity<?> removeMember(
            @RequestParam(name = "selection", defaultValue = "MEMBER") String selectionType,
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @PathVariable("member") String uniqueMemberName) {

        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "/member/" + uniqueMemberName + "\tDELETE");
            }
            boolean ret = olapQueryService.removeMember(queryName, dimensionName, uniqueMemberName, selectionType);
            if (ret) {
                SaikuDimensionSelection dimsels =
                        olapQueryService.getAxisDimensionSelections(queryName, axisName, dimensionName);
                if (dimsels != null && dimsels.getSelections().size() == 0) {
                    olapQueryService.moveDimension(queryName, "UNUSED", dimensionName, -1);
                }
                return ResponseEntity.ok().build();
            } else {
                throw new Exception("Cannot remove member " + dimensionName + " for query (" + queryName + ")");
            }
        } catch (Exception e) {
            log.error("Cannot remove member " + dimensionName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PutMapping("/{queryname}/axis/{axis}/dimension/{dimension}/children")
    public ResponseEntity<?> includeChildren(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @RequestParam(name = "member", required = false) String uniqueMemberName) {

        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "/children/" + uniqueMemberName + "\tPOST");
            }

            boolean ret = olapQueryService.includeChildren(queryName, dimensionName, uniqueMemberName);
            if (ret) {
                return ResponseEntity.status(HttpStatus.CREATED).build();
            } else {
                throw new Exception("Couldn't include children for " + uniqueMemberName);
            }
        } catch (Exception e) {
            log.error("Cannot include children for " + dimensionName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @DeleteMapping("/{queryname}/axis/{axis}/dimension/{dimension}/children")
    public ResponseEntity<?> removeChildren(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @RequestParam(name = "member", required = false) String uniqueMemberName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                    + "/children/" + uniqueMemberName + "\tDELETE");
        }
        try {
            boolean ret = olapQueryService.removeChildren(queryName, dimensionName, uniqueMemberName);
            if (ret) {
                return ResponseEntity.status(HttpStatus.GONE).build();
            } else {
                throw new Exception("Couldn't remove children for " + uniqueMemberName);
            }
        } catch (Exception e) {
            log.error("Cannot remove children for " + dimensionName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/{queryname}/axis/{axis}/dimension/{dimension}/hierarchy/{hierarchy}/{level}")
    public ResponseEntity<?> includeLevel(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @PathVariable("hierarchy") String uniqueHierarchyName,
            @PathVariable("level") String uniqueLevelName,
            @RequestParam(name = "position", defaultValue = "-1") int position) {

        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "/hierarchy/" + uniqueHierarchyName + "/" + uniqueLevelName + "\tPOST");
            }
            olapQueryService.moveDimension(queryName, axisName, dimensionName, position);
            boolean ret = olapQueryService.includeLevel(queryName, dimensionName, uniqueHierarchyName, uniqueLevelName);
            if (ret) {
                return ResponseEntity.status(HttpStatus.CREATED).build();
            } else {
                throw new Exception("Something went wrong including level: " + uniqueLevelName);
            }
        } catch (Exception e) {
            log.error("Cannot include level of hierarchy " + uniqueHierarchyName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @DeleteMapping("/{queryname}/axis/{axis}/dimension/{dimension}/hierarchy/{hierarchy}/{level}")
    public ResponseEntity<?> removeLevel(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("dimension") String dimensionName,
            @PathVariable("hierarchy") String uniqueHierarchyName,
            @PathVariable("level") String uniqueLevelName) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/dimension/" + dimensionName
                        + "/hierarchy/" + uniqueHierarchyName + "/" + uniqueLevelName + "\tDELETE");
            }
            boolean ret = olapQueryService.removeLevel(queryName, dimensionName, uniqueHierarchyName, uniqueLevelName);

            if (ret) {
                SaikuDimensionSelection dimsels =
                        olapQueryService.getAxisDimensionSelections(queryName, axisName, dimensionName);
                if (dimsels != null && dimsels.getSelections().size() == 0) {
                    olapQueryService.moveDimension(queryName, "UNUSED", dimensionName, -1);
                }
                return ResponseEntity.ok().build();
            } else {
                log.error("Cannot remove level of hierarchy " + uniqueHierarchyName + " for query (" + queryName + ")");
            }
            throw new Exception("Something went wrong removing level: " + uniqueLevelName + " from "
                    + uniqueHierarchyName + " for query (" + queryName + ")");
        } catch (Exception e) {
            log.error("Cannot include level of hierarchy " + uniqueHierarchyName + " for query (" + queryName + ")", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PutMapping(path = "/{queryname}/tag", produces = MediaType.APPLICATION_JSON_VALUE)
    public HttpStatus activateTag(
            @PathVariable("queryname") String queryName, @RequestParam(name = "tag", required = false) String tagJSON) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/tags\tPUT");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setVisibilityChecker(mapper.getVisibilityChecker().withFieldVisibility(Visibility.ANY));
            SaikuTag tag = mapper.readValue(tagJSON, SaikuTag.class);

            olapQueryService.setTag(queryName, tag);
            return HttpStatus.OK;
        } catch (Exception e) {
            log.error("Cannot add tag " + tagJSON + " for query (" + queryName + ")", e);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @DeleteMapping(path = "/{queryname}/tag", produces = MediaType.APPLICATION_JSON_VALUE)
    public HttpStatus deactivateTag(
            @PathVariable("queryname") String queryName, @PathVariable("tagname") String tagName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/tags\tPUT");
        }
        try {
            olapQueryService.disableTag(queryName);
            return HttpStatus.OK;
        } catch (Exception e) {
            log.error("Cannot remove tag " + tagName + " for query (" + queryName + ")", e);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @GetMapping(path = "/{queryname}/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getFilter(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "dimension", required = false) String dimension,
            @RequestParam(name = "hierarchy", required = false) String hierarchy,
            @RequestParam(name = "level", required = false) String level) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/filter\tGET");
        }
        try {
            SaikuFilter t = olapQueryService.getFilter(queryName, "new", dimension, hierarchy, level);
            return ResponseEntity.ok(t);
        } catch (Exception e) {
            log.error("Cannot get filter for query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PutMapping(path = "/{queryname}/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> activateFilter(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "filter", required = false) String filterJSON) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/tags\tPUT");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setVisibilityChecker(mapper.getVisibilityChecker().withFieldVisibility(Visibility.ANY));
            SaikuFilter filter = mapper.readValue(filterJSON, SaikuFilter.class);
            SaikuQuery sq = olapQueryService.applyFilter(queryName, filter);
            return ResponseEntity.ok(sq);
        } catch (Exception e) {
            log.error("Cannot activate filter for query (" + queryName + "), json:" + filterJSON, e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @DeleteMapping(path = "/{queryname}/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deactivateFilter(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/tags\tPUT");
        }
        try {
            SaikuQuery sq = olapQueryService.removeFilter(queryName);
            return ResponseEntity.ok(sq);
        } catch (Exception e) {
            log.error("Cannot remove filter for query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping(
            path = "/{queryname}/axis/{axis}/sort/{sortorder}/{sortliteral}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public void sortAxis(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("sortorder") String sortOrder,
            @PathVariable("sortliteral") String sortLiteral) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/sort/" + sortOrder + "/"
                    + sortLiteral + "\tPOST");
        }
        olapQueryService.sortAxis(queryName, axisName, sortLiteral, sortOrder);
    }

    @PutMapping(path = "/{queryname}/axis/{axis}/show_totals/{function}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SaikuQuery showGrandTotals(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("function") String functionName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/show_totals/" + functionName
                    + "\tPUT");
        }
        IQuery query = olapQueryService.showGrandTotals(queryName, axisName, functionName);
        return ObjectUtil.convert(query);
    }

    @DeleteMapping(path = "/{queryname}/axis/{axis}/sort", produces = MediaType.APPLICATION_JSON_VALUE)
    public void clearSortAxis(@PathVariable("queryname") String queryName, @PathVariable("axis") String axisName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/sort/\tDELETE");
        }
        olapQueryService.clearSort(queryName, axisName);
    }

    @PostMapping(path = "/{queryname}/axis/{axis}/limit/{limitfunction}", produces = MediaType.APPLICATION_JSON_VALUE)
    public void limitAxis(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @PathVariable("limitfunction") String limitfunction,
            @RequestParam(name = "n", required = false) String n,
            @RequestParam(name = "sortliteral", required = false) String sortLiteral) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/limit/" + limitfunction + "(" + n
                    + ", sort:" + sortLiteral + "\tPOST");
        }
        olapQueryService.limitAxis(queryName, axisName, limitfunction, n, sortLiteral);
    }

    @DeleteMapping(path = "/{queryname}/axis/{axis}/limit", produces = MediaType.APPLICATION_JSON_VALUE)
    public void clearLimitAxis(@PathVariable("queryname") String queryName, @PathVariable("axis") String axisName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/limit/\tDELETE");
        }
        olapQueryService.clearLimit(queryName, axisName);
    }

    @PostMapping(path = "/{queryname}/axis/{axis}/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    public void filterAxis(
            @PathVariable("queryname") String queryName,
            @PathVariable("axis") String axisName,
            @RequestParam(name = "filterCondition", required = false) String filterCondition) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/filter/ (" + filterCondition
                    + " )\tPOST");
        }
        olapQueryService.filterAxis(queryName, axisName, filterCondition);
    }

    @DeleteMapping(path = "/{queryname}/axis/{axis}/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    public void clearFilter(@PathVariable("queryname") String queryName, @PathVariable("axis") String axisName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/axis/" + axisName + "/filter/\tDELETE");
        }
        olapQueryService.clearFilter(queryName, axisName);
    }
}
