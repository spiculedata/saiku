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

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.qmino.miredot.annotations.ReturnType;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saiku Query Endpoints
 */
@Component
@RestController
@RequestMapping("/saiku/api/query")
@XmlAccessorType(XmlAccessType.NONE)
public class Query2Resource {

    private static final Logger log = LoggerFactory.getLogger(Query2Resource.class);

    private ThinQueryService thinQueryService;

    public void setThinQueryService(ThinQueryService tqs) {
        thinQueryService = tqs;
    }

    private ISaikuRepository repository;

    public void setRepository(ISaikuRepository repository) {
        this.repository = repository;
    }

    /**
     * Delete query from the query pool.
     */
    @DeleteMapping("/{queryname}")
    public HttpStatus deleteQuery(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "\tDELETE");
        }
        try {
            thinQueryService.deleteQuery(queryName);
            return HttpStatus.GONE;
        } catch (Exception e) {
            log.error("Cannot delete query (" + queryName + ")", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a new Saiku Query.
     *
     * <p>The fourth argument preserves the original Jersey MultivaluedMap
     * override capability as a plain {@link Map} so programmatic callers
     * (e.g. {@link ExporterResource}) can still pass parameters.
     */
    @PostMapping(path = "/{queryname}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ThinQuery createQuery(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "json", required = false) String jsonFormParam,
            @RequestParam(name = "file", required = false) String fileFormParam,
            Map<String, String> formParams)
            throws ServletException {
        try {
            ThinQuery tq;
            String file = fileFormParam, json = jsonFormParam;
            if (formParams != null) {
                json = formParams.containsKey("json") ? formParams.get("json") : jsonFormParam;
                file = formParams.containsKey("file") ? formParams.get("file") : fileFormParam;
            }
            String filecontent = null;
            if (StringUtils.isNotBlank(json)) {
                filecontent = json;
            } else if (StringUtils.isNotBlank(file)) {
                ResponseEntity<?> f = repository.getResource(file);
                filecontent = new String((byte[]) f.getBody());
            }
            if (StringUtils.isBlank(filecontent)) {
                throw new SaikuServiceException("Cannot create new query. Empty file content "
                        + StringUtils.isNotBlank(json) + " or read from file:" + file);
            }
            if (thinQueryService.isOldQuery(filecontent)) {
                tq = thinQueryService.convertQuery(filecontent);
            } else {
                ObjectMapper om = new ObjectMapper();
                tq = om.readValue(filecontent, ThinQuery.class);
            }

            if (log.isDebugEnabled()) {
                log.debug("TRACK\t" + "\t/query/" + queryName + "\tPOST\t tq:" + (tq == null) + " file:" + (file));
            }

            if (tq == null) {
                throw new SaikuServiceException("Cannot create blank query (ThinQuery object = null)");
            }
            tq.setName(queryName);

            return thinQueryService.createQuery(tq);
        } catch (Exception e) {
            log.error("Error creating new query", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Execute a Saiku Query
     */
    @PostMapping(path = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QueryResult execute(@RequestBody ThinQuery tq) {
        try {
            if (thinQueryService.isMdxDrillthrough(tq)) {
                Long start = (new Date()).getTime();
                ResultSet rs = thinQueryService.drillthrough(tq);
                QueryResult rsc = RestUtil.convert(rs);
                rsc.setQuery(tq);
                Long runtime = (new Date()).getTime() - start;
                rsc.setRuntime(runtime.intValue());
                return rsc;
            }

            QueryResult qr = RestUtil.convert(thinQueryService.execute(tq));
            ThinQuery tqAfter = thinQueryService.getContext(tq.getName()).getOlapQuery();
            qr.setQuery(tqAfter);
            return qr;
        } catch (Exception e) {
            log.error("Cannot execute query (" + tq + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            return new QueryResult(error);
        }
    }

    /**
     * Cancel a running query.
     */
    @DeleteMapping("/{queryname}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "\tDELETE");
        }
        try {
            thinQueryService.cancel(queryName);
            return ResponseEntity.ok(HttpStatus.GONE);
        } catch (Exception e) {
            log.error("Cannot cancel query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(error, e);
        }
    }

    /**
     * Enrich a thin query model
     */
    @PostMapping(path = "/enrich", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ThinQuery enrich(@RequestBody ThinQuery tq) {
        try {
            return thinQueryService.updateQuery(tq);
        } catch (Exception e) {
            log.error("Cannot enrich query (" + tq + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(error, e);
        }
    }

    /**
     * Get level members from a query.
     */
    @GetMapping(
            path = "/{queryname}/result/metadata/hierarchies/{hierarchy}/levels/{level}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SimpleCubeElement> getLevelMembers(
            @PathVariable("queryname") String queryName,
            @PathVariable("hierarchy") String hierarchyName,
            @PathVariable("level") String levelName,
            @RequestParam(name = "result", defaultValue = "true") boolean result,
            @RequestParam(name = "search", required = false) String searchString,
            @RequestParam(name = "searchlimit", defaultValue = "-1") int searchLimit) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t"
                    + "\t/query/" + queryName + "/result/metadata"
                    + "/hierarchies/" + hierarchyName + "/levels/" + levelName + "\tGET");
        }
        try {
            return thinQueryService.getResultMetadataMembers(
                    queryName, result, hierarchyName, levelName, searchString, searchLimit);
        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(error, e);
        }
    }

    /**
     * Query export to excel.
     */
    @GetMapping(path = "/{queryname}/export/xls", produces = "application/vnd.ms-excel")
    public ResponseEntity<?> getQueryExcelExport(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/xls/\tGET");
        }
        return getQueryExcelExport(queryName, "flattened", null);
    }

    /**
     * Query export to excel
     */
    @GetMapping(path = "/{queryname}/export/xls/{format}", produces = "application/vnd.ms-excel")
    public ResponseEntity<?> getQueryExcelExport(
            @PathVariable("queryname") String queryName,
            @PathVariable(name = "format") String format,
            @RequestParam(name = "exportname", defaultValue = "") String name) {
        if (format == null || format.isEmpty()) {
            format = "flattened";
        }
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/xls/" + format + "\tGET");
        }
        try {
            byte[] doc = thinQueryService.getExport(queryName, "xls", format);
            if (name == null || name.equals("")) {
                name = SaikuProperties.webExportExcelName + "." + SaikuProperties.webExportExcelFormat;
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name)
                    .header("content-length", String.valueOf(doc.length))
                    .body(doc);
        } catch (Exception e) {
            log.error("Cannot get excel for query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(error, e);
        }
    }

    /**
     * Get CSV export of a query.
     */
    @GetMapping(path = "/{queryname}/export/csv", produces = "text/csv")
    public ResponseEntity<?> getQueryCsvExport(@PathVariable("queryname") String queryName) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/csv\tGET");
        }
        return getQueryCsvExport(queryName, "flattened", null);
    }

    /**
     * Get CSV export of a query.
     */
    @GetMapping(path = "/{queryname}/export/csv/{format}", produces = "text/csv")
    public ResponseEntity<?> getQueryCsvExport(
            @PathVariable("queryname") String queryName,
            @PathVariable("format") String format,
            @RequestParam(name = "exportname", defaultValue = "") String name) {
        if (log.isDebugEnabled()) {
            log.debug("TRACK\t" + "\t/query/" + queryName + "/export/csv/" + format + "\tGET");
        }
        try {
            byte[] doc = thinQueryService.getExport(queryName, "csv", format);
            if (name == null || name.equals("")) {
                name = SaikuProperties.webExportCsvName;
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("content-disposition", "attachment; filename = " + name + ".csv")
                    .header("content-length", String.valueOf(doc.length))
                    .body(doc);
        } catch (Exception e) {
            log.error("Cannot get csv for query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(error, e);
        }
    }

    /**
     * Zoom into a query result table.
     */
    @PostMapping(
            path = "/{queryname}/zoomin",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ThinQuery zoomIn(
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
            return thinQueryService.zoomIn(queryName, realPositions);

        } catch (Exception e) {
            log.error("Cannot zoom in on query (" + queryName + ")", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Drill through on the query result set.
     */
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
                rs = thinQueryService.drillthrough(queryName, maxrows, returns);
                rsc = RestUtil.convert(rs);
            } else {
                String[] positions = position.split(":");
                List<Integer> cellPosition = new ArrayList<>();

                for (String p : positions) {
                    Integer pInt = Integer.parseInt(p);
                    cellPosition.add(pInt);
                }
                DrillThroughResult drillthrough =
                        thinQueryService.drillthroughWithCaptions(queryName, cellPosition, maxrows, returns);
                rsc = RestUtil.convert(drillthrough);
            }
            Long runtime = (new Date()).getTime() - start;
            rsc.setRuntime(runtime.intValue());

        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            rsc = new QueryResult(error);

        } finally {
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
                    } catch (Exception ee) {
                    }
                }
            }
        }
        return rsc;
    }

    /**
     * Export the drill through to a CSV file for further analysis
     */
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
                }
            }
        }
    }

    /**
     * Export PDF with chart
     */
    @PostMapping(path = "/{queryname}/export/pdf", produces = "application/pdf")
    public ResponseEntity<?> exportPdfWithChart(
            @PathVariable("queryname") String queryName,
            @RequestParam(name = "svg", defaultValue = "") String svg) {
        return exportPdfWithChartAndFormat(queryName, null, svg, null);
    }

    /**
     * Export table to PDF.
     */
    @GetMapping(path = "/{queryname}/export/pdf", produces = "application/pdf")
    public ResponseEntity<?> exportPdf(@PathVariable("queryname") String queryName) {
        return exportPdfWithChartAndFormat(queryName, null, null, null);
    }

    /**
     * Export to PDF with cellset format.
     */
    @GetMapping(path = "/{queryname}/export/pdf/{format}", produces = "application/pdf")
    public ResponseEntity<?> exportPdfWithFormat(
            @PathVariable("queryname") String queryName,
            @PathVariable("format") String format,
            @RequestParam(name = "exportname", required = false) String name) {
        return exportPdfWithChartAndFormat(queryName, format, null, name);
    }

    /**
     * Export PDF with chart and cellset format.
     */
    @PostMapping(path = "/{queryname}/export/pdf/{format}", produces = "application/pdf")
    public ResponseEntity<?> exportPdfWithChartAndFormat(
            @PathVariable("queryname") String queryName,
            @PathVariable("format") String format,
            @RequestParam(name = "svg", defaultValue = "") String svg,
            @RequestParam(name = "name", required = false) String name) {

        try {
            CellDataSet cellData = thinQueryService.getFormattedResult(queryName, format);
            QueryResult queryResult = RestUtil.convert(cellData);
            PdfReport pdf = new PdfReport();
            byte[] doc = pdf.createPdf(queryResult, svg);
            if (name == null || name.equals("")) {
                name = "export";
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("content-disposition", "attachment; filename = " + name + ".pdf")
                    .header("content-length", String.valueOf(doc.length))
                    .body(doc);
        } catch (Exception e) {
            log.error("Error exporting query to  PDF", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Get HTML export
     */
    @GetMapping(path = "/{queryname}/export/html", produces = MediaType.TEXT_HTML_VALUE)
    @ReturnType("java.lang.String")
    public ResponseEntity<?> exportHtml(
            @PathVariable("queryname") String queryname,
            @RequestParam(name = "format", required = false) String format,
            @RequestParam(name = "css", defaultValue = "false") Boolean css,
            @RequestParam(name = "tableonly", defaultValue = "false") Boolean tableonly,
            @RequestParam(name = "wrapcontent", defaultValue = "true") Boolean wrapcontent) {
        ThinQuery tq = thinQueryService.getContext(queryname).getOlapQuery();
        return exportHtml(tq, format, css, tableonly, wrapcontent);
    }

    /**
     * Get HTML export
     */
    @PostMapping(path = "/export/html", produces = MediaType.TEXT_HTML_VALUE)
    @ReturnType("java.lang.String")
    public ResponseEntity<?> exportHtml(
            @RequestBody ThinQuery tq,
            @RequestParam(name = "format", required = false) String format,
            @RequestParam(name = "css", defaultValue = "false") Boolean css,
            @RequestParam(name = "tableonly", defaultValue = "false") Boolean tableonly,
            @RequestParam(name = "wrapcontent", defaultValue = "true") Boolean wrapcontent) {

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
            log.error("Error exporting query to  HTML", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Drill across on a result set
     */
    @PostMapping(path = "/{queryname}/drillacross", produces = MediaType.APPLICATION_JSON_VALUE)
    public ThinQuery drillacross(
            @PathVariable("queryname") String queryName,
            @RequestParam("position") String position,
            @RequestParam("drill") String returns) {
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

            CollectionType ct = mapper.getTypeFactory().constructCollectionType(ArrayList.class, String.class);

            JavaType st = mapper.getTypeFactory().uncheckedSimpleType(String.class);

            Map<String, List<String>> levels =
                    mapper.readValue(returns, mapper.getTypeFactory().constructMapType(Map.class, st, ct));
            return thinQueryService.drillacross(queryName, cellPosition, levels);

        } catch (Exception e) {
            log.error("Cannot execute query (" + queryName + ")", e);
            String error = ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(error, e);
        }
    }

    public ThinQueryService getThinQueryService() {
        return thinQueryService;
    }
}
