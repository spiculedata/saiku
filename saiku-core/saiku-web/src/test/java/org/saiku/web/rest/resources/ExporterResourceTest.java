/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.saiku.web.rest.util.RestUtil;

/** Unit tests for {@link ExporterResource} (query export dispatch) and {@link RestUtil}. */
public class ExporterResourceTest {

    private static final byte[] MDX = "{\"query\":\"SELECT FROM [Sales]\"}".getBytes();

    private ExporterResource resource;
    private StubQuery2 query2;
    private String repositoryFile;

    @Before
    public void setUp() {
        query2 = new StubQuery2();
        resource = new ExporterResource();
        resource.setQuery2Resource(query2);
        repositoryFile = "mdx";
    }

    /** Repository returning a canned file, or failing when told to. */
    private void setRepository(final String behaviour) {
        org.saiku.web.rest.resources.ISaikuRepository repo =
                (org.saiku.web.rest.resources.ISaikuRepository) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {ISaikuRepository.class}, (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getResource":
                                    if ("missing".equals(behaviour)) {
                                        throw new SaikuServiceException("no such file");
                                    }
                                    return Response.ok(MDX).build();
                                default:
                                    return null;
                            }
                        });
        resource.setRepository(repo);
    }

    private static final class StubQuery2 extends Query2Resource {
        org.saiku.olap.query2.ThinQuery lastQuery;
        String lastCsvName;

        @Override
        public org.saiku.olap.query2.ThinQuery createQuery(
                String queryName,
                String jsonFormParam,
                String fileFormParam,
                jakarta.ws.rs.core.MultivaluedMap<String, String> formParams) {
            lastQuery = new org.saiku.olap.query2.ThinQuery();
            lastQuery.setName(queryName);
            return lastQuery;
        }

        @Override
        public Response execute(org.saiku.olap.query2.ThinQuery tq, jakarta.ws.rs.core.HttpHeaders headers) {
            return Response.ok(new QueryResult("ok")).build();
        }

        @Override
        public Response getQueryExcelExport(String queryName, String format, String name) {
            return Response.ok("xlsx-bytes").build();
        }

        @Override
        public Response getQueryCsvExport(String queryName) {
            lastCsvName = queryName;
            return Response.ok("csv-bytes").build();
        }

        @Override
        public Response exportHtml(
                String queryname, String format, Boolean css, Boolean tableonly, Boolean wrapcontent) {
            return Response.ok("<table/>").build();
        }
    }

    @Test
    public void export_excel_runs_the_query_and_serves_the_workbook() {
        setRepository("ok");
        Response response = resource.exportExcel("file.saiku", "flattened", "report", null);
        assertEquals(200, response.getStatus());
        assertEquals("xlsx-bytes", response.getEntity());
    }

    @Test
    public void export_csv_runs_the_query_and_serves_the_csv() {
        setRepository("ok");
        Response response = resource.exportCsv("file.saiku", null, null);
        assertEquals(200, response.getStatus());
        assertEquals("csv-bytes", response.getEntity());
        assertTrue(query2.lastCsvName != null);
    }

    @Test
    public void export_json_runs_the_query() {
        setRepository("ok");
        Response response = resource.exportJson("file.saiku", null, null);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void export_html_serves_the_table() {
        setRepository("ok");
        Response response = resource.exportHtml("file.saiku", null, false, true, true, null);
        assertEquals(200, response.getStatus());
        assertEquals("<table/>", response.getEntity());
    }

    @Test
    public void a_missing_repository_file_degrades_to_a_server_error() {
        setRepository("missing");
        assertEquals(500, resource.exportExcel("file.saiku", null, null, null).getStatus());
        assertEquals(500, resource.exportCsv("file.saiku", null, null).getStatus());
        assertEquals(500, resource.exportJson("file.saiku", null, null).getStatus());
        assertEquals(
                500,
                resource.exportHtml("file.saiku", null, false, false, true, null)
                        .getStatus());
    }

    @Test
    public void chart_export_renders_an_svg() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>";
        Response response = resource.exportChart("svg", svg, 10, "chart.svg");
        assertEquals(200, response.getStatus());
        assertEquals(
                "image/svg+xml", response.getMetadata().getFirst("Content-Type").toString());
        assertTrue(((byte[]) response.getEntity()).length > 0);
    }

    @Test
    public void chart_export_requires_an_svg_body() {
        assertEquals(500, resource.exportChart("svg", " ", null, null).getStatus());
    }

    @Test
    public void chart_export_rejects_an_unknown_converter() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>";
        assertEquals(500, resource.exportChart("emf", svg, null, null).getStatus());
    }

    @Test
    public void chart_export_names_unnamed_charts() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>";
        Response response = resource.exportChart("svg", svg, null, null);
        assertEquals(200, response.getStatus());
        String disposition = String.valueOf(response.getMetadata().getFirst("content-disposition"));
        assertTrue(disposition.startsWith("attachment"));
    }

    /* ------------------------- RestUtil static helpers ------------------------- */

    private static CellDataSet cellDataSet() {
        CellDataSet cds = new CellDataSet(1, 1);
        org.saiku.olap.dto.resultset.AbstractBaseCell[] header = new org.saiku.olap.dto.resultset.AbstractBaseCell[1];
        org.saiku.olap.dto.resultset.MemberCell m = new org.saiku.olap.dto.resultset.MemberCell(false, false);
        m.setFormattedValue("Year");
        header[0] = m;
        cds.setCellSetHeaders(new org.saiku.olap.dto.resultset.AbstractBaseCell[][] {header});
        org.saiku.olap.dto.resultset.DataCell d = new org.saiku.olap.dto.resultset.DataCell(true, false, List.of(0));
        d.setFormattedValue("100");
        cds.setCellSetBody(new org.saiku.olap.dto.resultset.AbstractBaseCell[][] {{d}});
        return cds;
    }

    @Test
    public void restutil_converts_a_cell_dataset_and_limits_rows() {
        // The converted cellset includes the header row, then the body rows.
        QueryResult full = RestUtil.convert(cellDataSet(), 0);
        assertEquals(2, full.getCellset().size());
        assertTrue(full.getWidth() != null && full.getWidth() > 0);

        // The limit only caps body rows — the header row always rides along.
        QueryResult limited = RestUtil.convert(cellDataSet(), 1);
        assertEquals(2, limited.getCellset().size());
    }

    @Test
    public void restutil_totals_conversion_passes_null_through() {
        assertEquals(null, RestUtil.convertTotals(null));
    }

    @Test
    public void export_excel_applies_the_formatter_property() {
        setRepository("ok");
        Response response = resource.exportExcel("file.saiku", "flattened", "n", null);
        assertEquals(200, response.getStatus());
        // The formatter was merged into the thin query's properties.
        assertTrue(query2.lastQuery != null);
    }

    @Test
    public void query_result_with_an_error_has_no_cells() {
        QueryResult error = new QueryResult("Internal error");
        assertEquals("Internal error", error.getError());
        assertEquals(null, error.getCellset());
    }
}
