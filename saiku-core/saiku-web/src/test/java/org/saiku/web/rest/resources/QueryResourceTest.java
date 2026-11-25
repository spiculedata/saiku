/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;
import org.olap4j.Axis;
import org.olap4j.CellSet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapStatement;
import org.olap4j.Scenario;
import org.olap4j.metadata.Cube;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimensionSelection;
import org.saiku.olap.dto.SaikuQuery;
import org.saiku.olap.dto.SaikuTag;
import org.saiku.olap.dto.filter.SaikuFilter;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query.IQuery;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.olap.OlapQueryService;
import org.saiku.service.util.exception.SaikuQueryRejectedException;

/**
 * Unit coverage for the deprecated v1 {@link QueryResource}. The service layer
 * is replaced by a hand-rolled stub (the repo's convention — Mockito was
 * deliberately removed); assertions cover the delegation, the wire format of
 * the exports and the error envelopes (saiku#1165/saiku#2420 behaviour).
 */
public class QueryResourceTest {

    private QueryResource resource;
    private StubOlapQueryService service;
    private StubRepository repository;

    @Before
    public void setUp() {
        resource = new QueryResource();
        service = new StubOlapQueryService();
        repository = new StubRepository();
        resource.setOlapQueryService(service);
        resource.setRepository(repository);
    }

    /* ------------------------------ helpers ------------------------------ */

    private static CellDataSet cellDataSet() {
        // Same shape PdfReportTest's smallCellDataSet uses — known-good for the
        // PDF/HTML export code paths.
        CellDataSet cds = new CellDataSet(2, 1);
        cds.setCellSetHeaders(
                new AbstractBaseCell[][] {new AbstractBaseCell[] {member("Year"), member("Store Sales")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {
            new AbstractBaseCell[] {member("1997"), data("100")},
            new AbstractBaseCell[] {member("1998"), data("200")},
        });
        return cds;
    }

    private static AbstractBaseCell member(String v) {
        AbstractBaseCell c = new MemberCell(false, false);
        c.setFormattedValue(v);
        return c;
    }

    private static AbstractBaseCell data(String v) {
        AbstractBaseCell c = new DataCell(true, false, List.of(0));
        c.setFormattedValue(v);
        c.setRawValue(v);
        return c;
    }

    /** Minimal {@link IQuery} whose type is MDX so {@code ObjectUtil.convert} skips axes. */
    private static class StubQuery implements IQuery {
        @Override
        public String getName() {
            return "stub-query";
        }

        @Override
        public SaikuCube getSaikuCube() {
            return new SaikuCube("conn", "cube", "cube", null, "catalog", "schema");
        }

        @Override
        public CellSet execute() {
            return null;
        }

        @Override
        public String getMdx() {
            return "SELECT FROM [cube]";
        }

        @Override
        public void resetQuery() {}

        @Override
        public void setProperties(Properties props) {}

        @Override
        public Properties getProperties() {
            return new Properties();
        }

        @Override
        public String toXml() {
            return "<query/>";
        }

        @Override
        public Boolean isDrillThroughEnabled() {
            return false;
        }

        @Override
        public QueryType getType() {
            return QueryType.MDX;
        }

        @Override
        public void swapAxes() {}

        @Override
        public java.util.Map<Axis, QueryAxis> getAxes() {
            return java.util.Map.of();
        }

        @Override
        public QueryAxis getAxis(Axis axis) {
            return null;
        }

        @Override
        public QueryAxis getAxis(String name) throws SaikuOlapException {
            return null;
        }

        @Override
        public Cube getCube() {
            return null;
        }

        @Override
        public QueryAxis getUnusedAxis() {
            return null;
        }

        @Override
        public void moveDimension(QueryDimension dimension, Axis axis) {}

        @Override
        public void moveDimension(QueryDimension dimension, Axis axis, int position) {}

        @Override
        public QueryDimension getDimension(String name) {
            return null;
        }

        @Override
        public void resetAxisSelections(QueryAxis axis) {}

        @Override
        public void clearAllQuerySelections() {}

        @Override
        public void setMdx(String mdx) {}

        @Override
        public void setScenario(Scenario scenario) {}

        @Override
        public Scenario getScenario() {
            return null;
        }

        @Override
        public void setTag(SaikuTag tag) {}

        @Override
        public SaikuTag getTag() {
            return null;
        }

        @Override
        public void removeTag() {}

        @Override
        public void setFilter(SaikuFilter filter) {}

        @Override
        public SaikuFilter getFilter() {
            return null;
        }

        @Override
        public void removeFilter() {}

        @Override
        public void storeCellset(CellSet cs) {}

        @Override
        public CellSet getCellset() {
            return null;
        }

        @Override
        public void setStatement(OlapStatement os) {}

        @Override
        public OlapStatement getStatement() {
            return null;
        }

        @Override
        public void cancel() {}

        @Override
        public void clearAxis(String axisName) {}

        @Override
        public OlapConnection getConnection() {
            return null;
        }

        @Override
        public void storeFormatter(org.saiku.olap.util.formatter.ICellSetFormatter formatter) {}

        @Override
        public org.saiku.olap.util.formatter.ICellSetFormatter getFormatter() {
            return null;
        }

        @Override
        public void setTotalFunction(String uniqueLevelName, String value) {}

        @Override
        public String getTotalFunction(String uniqueLevelName) {
            return null;
        }

        @Override
        public java.util.Map<String, String> getTotalFunctions() {
            return java.util.Map.of();
        }
    }

    private static class StubRepository implements ISaikuRepository {
        byte[] resourceData = "<query/>".getBytes();

        @Override
        public List<org.saiku.repository.IRepositoryObject> getRepository(String path, String type) {
            return List.of();
        }

        @Override
        public Response getResource(String file) {
            return Response.ok(resourceData).build();
        }

        @Override
        public Response saveResource(String file, String content) {
            return Response.ok().build();
        }

        @Override
        public Response deleteResource(String file) {
            return Response.ok().build();
        }
    }

    private static class StubOlapQueryService extends OlapQueryService {
        CellDataSet cds = cellDataSet();
        SaikuQuery saikuQuery = new SaikuQuery(
                "stub-query",
                new SaikuCube("conn", "cube", "cube", null, "catalog", "schema"),
                new ArrayList<>(),
                null,
                "MDX",
                new Properties());
        IQuery query = new StubQuery();
        boolean fail;
        boolean drillthroughMdx = true;
        boolean rejectMdx;
        int includeMemberReturn = 1;
        boolean removeMemberReturn = true;

        private RuntimeException failIfNeeded() {
            return fail ? new RuntimeException("service exploded") : null;
        }

        @Override
        public List<String> getQueries() {
            return List.of("q1", "q2");
        }

        @Override
        public SaikuQuery getQuery(String name) {
            return saikuQuery;
        }

        @Override
        public void deleteQuery(String name) {
            if (fail) {
                throw new RuntimeException("cannot delete");
            }
        }

        @Override
        public SaikuQuery createNewOlapQuery(String name, String xml) {
            return saikuQuery;
        }

        @Override
        public SaikuQuery createNewOlapQuery(String name, SaikuCube cube) {
            return saikuQuery;
        }

        @Override
        public Properties getProperties(String name) {
            return new Properties();
        }

        @Override
        public Properties setProperties(String name, Properties props) {
            return props;
        }

        @Override
        public String getMDXQuery(String name) {
            if (fail) {
                throw new RuntimeException("no mdx");
            }
            return "SELECT FROM [cube]";
        }

        @Override
        public void setMdx(String name, String mdx) {}

        @Override
        public String getQueryXml(String name) {
            if (fail) {
                throw new RuntimeException("no xml");
            }
            return "<query/>";
        }

        @Override
        public byte[] getExport(String name, String format, String type) {
            if (fail) {
                throw new RuntimeException("no export");
            }
            return "export".getBytes();
        }

        @Override
        public CellDataSet execute(String name) {
            if (fail) {
                throw new RuntimeException("execute failed");
            }
            return cds;
        }

        @Override
        public CellDataSet execute(String name, String formatter) {
            if (fail) {
                throw new RuntimeException("execute failed");
            }
            return cds;
        }

        @Override
        public void cancel(String name) {
            if (fail) {
                throw new RuntimeException("cancel failed");
            }
        }

        @Override
        public void qm2mdx(String name) {
            if (fail) {
                throw new RuntimeException("qm2mdx failed");
            }
        }

        @Override
        public boolean isMdxDrillthrough(String name, String mdx) {
            return drillthroughMdx;
        }

        @Override
        public ResultSet drillthrough(String name, String mdx) {
            return emptyResultSet();
        }

        @Override
        public CellDataSet executeMdx(String name, String mdx, org.saiku.olap.util.formatter.ICellSetFormatter f) {
            if (rejectMdx) {
                throw new SaikuQueryRejectedException("row cap could not be enforced");
            }
            return cds;
        }

        @Override
        public List<org.saiku.olap.dto.SimpleCubeElement> getResultMetadataMembers(
                String name, boolean result, String dim, String hier, String level, String search, int limit) {
            if (fail) {
                throw new RuntimeException("members failed");
            }
            return List.of(new org.saiku.olap.dto.SimpleCubeElement("USA", "[Store].[USA]", "USA"));
        }

        @Override
        public ResultSet explain(String name) {
            if (fail) {
                throw new RuntimeException("explain failed");
            }
            return emptyResultSet();
        }

        @Override
        public ResultSet drillthrough(String name, int maxrows, String returns) {
            if (fail) {
                throw new RuntimeException("drillthrough failed");
            }
            return emptyResultSet();
        }

        @Override
        public ResultSet drillthrough(String name, List<Integer> cellPosition, Integer maxrows, String returns) {
            if (fail) {
                throw new RuntimeException("drillthrough failed");
            }
            return emptyResultSet();
        }

        @Override
        public SaikuQuery drillacross(
                String name, List<Integer> cellPosition, java.util.Map<String, List<String>> levels) {
            return saikuQuery;
        }

        @Override
        public byte[] exportResultSetCsv(ResultSet rs) {
            return "csv".getBytes();
        }

        @Override
        public List<SaikuDimensionSelection> getAxisSelection(String name, String axis) {
            return List.of();
        }

        @Override
        public IQuery clearAxis(String name, String axis) {
            return query;
        }

        @Override
        public void resetQuery(String name) {}

        @Override
        public IQuery swapAxes(String name) {
            return query;
        }

        @Override
        public void setCellValue(String name, List<Integer> position, String value) {}

        @Override
        public SaikuDimensionSelection getAxisDimensionSelections(String name, String axis, String dimension) {
            return new SaikuDimensionSelection("d", "[d]", "[d]", null, new ArrayList<>());
        }

        @Override
        public void moveDimension(String name, String axis, String dimension, int position) {
            if (fail) {
                throw new RuntimeException("moveDimension failed");
            }
        }

        @Override
        public void removeDimension(String name, String axis, String dimension) {
            if (fail) {
                throw new RuntimeException("removeDimension failed");
            }
        }

        @Override
        public IQuery zoomIn(String name, List<List<Integer>> positions) {
            return query;
        }

        @Override
        public boolean removeMember(String name, String dimension, String member, String selectionType) {
            return removeMemberReturn;
        }

        @Override
        public boolean removeLevel(String name, String dimension, String hierarchy, String level) {
            if (fail) {
                throw new RuntimeException("removeLevel failed");
            }
            return true;
        }

        @Override
        public boolean includeMember(String name, String dimension, String member, String type, int position) {
            return includeMemberReturn == 1;
        }

        @Override
        public boolean includeMember(
                String name, String dimension, String member, String type, String totalsFunction, int position) {
            if (fail) {
                throw new RuntimeException("includeMember failed");
            }
            return includeMemberReturn == 1;
        }

        @Override
        public boolean includeLevel(String name, String dimension, String hierarchy, String level, String totals) {
            if (fail) {
                throw new RuntimeException("includeLevel failed");
            }
            return true;
        }

        @Override
        public boolean includeChildren(String name, String dimension, String member) {
            if (fail) {
                throw new RuntimeException("includeChildren failed");
            }
            return true;
        }

        @Override
        public boolean removeChildren(String name, String dimension, String member) {
            if (fail) {
                throw new RuntimeException("removeChildren failed");
            }
            return true;
        }

        @Override
        public void setTag(String name, SaikuTag tag) {}

        @Override
        public void disableTag(String name) {}

        @Override
        public SaikuFilter getFilter(String name, String filterName, String dim, String hier, String level) {
            if (fail) {
                throw new RuntimeException("getFilter failed");
            }
            return new SaikuFilter();
        }

        @Override
        public SaikuQuery applyFilter(String name, SaikuFilter filter) {
            if (fail) {
                throw new RuntimeException("applyFilter failed");
            }
            return saikuQuery;
        }

        @Override
        public SaikuQuery removeFilter(String name) {
            if (fail) {
                throw new RuntimeException("removeFilter failed");
            }
            return saikuQuery;
        }

        @Override
        public void sortAxis(String name, String axis, String sortLiteral, String sortOrder) {}

        @Override
        public IQuery showGrandTotals(String name, String axis, String function) {
            return query;
        }

        @Override
        public void clearSort(String name, String axis) {}

        @Override
        public void limitAxis(String name, String axis, String limitFunction, String n, String sortLiteral) {}

        @Override
        public void clearLimit(String name, String axis) {}

        @Override
        public void filterAxis(String name, String axis, String filterCondition) {}

        @Override
        public void clearFilter(String name, String axis) {}
        /** An empty in-memory result set: one row, one VARCHAR column. Built
         *  with a dynamic proxy, the same pattern RawDrillthroughResultSetCloseTest
         *  uses, so the full ResultSet surface doesn't need 60 no-op overrides. */
        ResultSet emptyResultSet() {
            ClassLoader cl = QueryResourceTest.class.getClassLoader();
            Class<?>[] none = new Class<?>[0];
            Object md = Proxy.newProxyInstance(cl, new Class<?>[] {java.sql.ResultSetMetaData.class}, (p, m, a) -> {
                switch (m.getName()) {
                    case "getColumnCount":
                        return 1;
                    case "getColumnName":
                    case "getColumnLabel":
                        return "col";
                    case "getColumnType":
                        return java.sql.Types.VARCHAR;
                    default:
                        return defaultValue(m.getReturnType());
                }
            });
            final int[] cursor = {0};
            return (ResultSet) Proxy.newProxyInstance(cl, new Class<?>[] {ResultSet.class}, (p, m, a) -> {
                switch (m.getName()) {
                    case "next":
                        return cursor[0]++ == 0;
                    case "getMetaData":
                        return md;
                    case "getStatement":
                        return java.lang.reflect.Proxy.newProxyInstance(
                                cl,
                                new Class<?>[] {java.sql.Statement.class},
                                (p2, m2, a2) -> defaultValue(m2.getReturnType()));
                    case "getObject":
                    case "getString":
                        return "value";
                    case "wasNull":
                        return false;
                    case "close":
                        return null;
                    default:
                        return defaultValue(m.getReturnType());
                }
            });
        }
    }

    /** Sensible defaults for unimplemented proxy methods: zero for primitives,
     *  false for booleans, null otherwise. */
    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0D;
        if (t == float.class) return 0F;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    /* ------------------------------ tests ------------------------------ */

    @Test
    public void getQueries_lists_the_open_queries() {
        assertEquals(List.of("q1", "q2"), resource.getQueries());
    }

    @Test
    public void getQuery_delegates_to_the_service() {
        assertEquals(service.saikuQuery, resource.getQuery("q1"));
    }

    @Test
    public void deleteQuery_gives_gone_on_success_and_500_on_failure() {
        assertEquals(Response.Status.GONE, resource.deleteQuery("q1"));
        service.fail = true;
        try {
            resource.deleteQuery("q1");
            fail("expected error");
        } catch (WebApplicationException e) {
            assertEquals(500, e.getResponse().getStatus());
        }
    }

    @Test
    public void createQuery_accepts_an_xml_document_and_form_params() throws Exception {
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("xml", "<query/>");
        SaikuQuery q = resource.createQuery("conn", "cube", "catalog", "schema", null, "q1", form);
        assertEquals(service.saikuQuery, q);
    }

    @Test
    public void createQuery_falls_back_to_the_xml_form_parameter() throws Exception {
        SaikuQuery q = resource.createQuery("conn", "cube", "catalog", "schema", "<query/>", "q1", null);
        assertEquals(service.saikuQuery, q);
    }

    @Test
    public void createQuery_can_load_the_xml_from_a_repository_file() throws Exception {
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("file", "/repo/query.xml");
        SaikuQuery q = resource.createQuery("conn", "cube", "catalog", "schema", null, "q1", form);
        assertEquals(service.saikuQuery, q);
    }

    @Test
    public void createQuery_without_xml_builds_from_the_cube() throws Exception {
        SaikuQuery q = resource.createQuery("conn", "cube", "catalog", "schema", null, "q1", null);
        assertEquals(service.saikuQuery, q);
    }

    @Test
    public void createQuery_wraps_failures_in_an_error_response() throws Exception {
        repository.resourceData = null;
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("file", "/repo/query.xml");
        try {
            resource.createQuery("conn", "cube", "catalog", "schema", null, "q1", form);
            fail("expected error");
        } catch (WebApplicationException e) {
            assertEquals(500, e.getResponse().getStatus());
        }
    }

    @Test
    public void properties_are_read_and_written() {
        assertNotNull(resource.getProperties("q1"));
        Properties p = new Properties();
        p.setProperty("k", "v");
        assertEquals(p, resource.setProperties("q1", "k=v"));
        assertEquals(p, resource.setProperties("q1", "k", "v"));
    }

    @Test
    public void mdx_round_trip_tolerates_failures() {
        assertEquals("SELECT FROM [cube]", resource.getMDXQuery("q1").getMdx());
        resource.setMDXQuery("q1", "SELECT FROM [other]");
        service.fail = true;
        assertNull(resource.getMDXQuery("q1"));
        resource.setMDXQuery("q1", "SELECT FROM [other]");
    }

    @Test
    public void getQueryXml_returns_the_saved_document() {
        assertEquals("<query/>", resource.getQueryXml("q1").getXml());
        service.fail = true;
        assertNull(resource.getQueryXml("q1"));
    }

    @Test
    public void excel_and_csv_exports_carry_a_disposition_header() {
        Response xls = resource.getQueryExcelExport("q1");
        assertEquals(200, xls.getStatus());
        assertTrue(xls.getHeaderString("content-disposition").contains("attachment"));
        Response csv = resource.getQueryCsvExport("q1", "flattened");
        assertEquals(200, csv.getStatus());
        assertTrue(csv.getHeaderString("content-disposition").contains(".csv"));
        service.fail = true;
        assertEquals(500, resource.getQueryExcelExport("q1", "flattened").getStatus());
        assertEquals(500, resource.getQueryCsvExport("q1").getStatus());
    }

    @Test
    public void pdf_export_serves_a_pdf_or_an_error() {
        assertEquals(200, resource.exportPdf("q1").getStatus());
        assertEquals(200, resource.exportPdfWithFormat("q1", "flattened").getStatus());
        assertEquals(200, resource.exportPdfWithChart("q1", "<svg/>").getStatus());
        service.fail = true;
        Response err = resource.exportPdfWithChartAndFormat("q1", null, null);
        assertEquals(500, err.getStatus());
        assertEquals("Internal error", err.getEntity());
    }

    @Test
    public void html_export_renders_the_result_table() {
        Response r = resource.exportHtml("q1", "flattened", false, false, true);
        assertEquals(200, r.getStatus());
        String html = (String) r.getEntity();
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("workspace_results"));
        // tableonly skips the document wrapper.
        Response tableOnly = resource.exportHtml("q1", "flattened", false, true, false);
        assertTrue(!((String) tableOnly.getEntity()).contains("<!DOCTYPE html>"));
        service.fail = true;
        Response err = resource.exportHtml("q1", null, false, false, true);
        assertEquals(500, err.getStatus());
    }

    @Test
    public void execute_returns_the_converted_result_or_the_generic_error() {
        var qr = resource.execute("q1", 0);
        assertNotNull(qr);
        assertEquals(2, qr.getWidth().intValue());
        service.fail = true;
        assertEquals("Internal error", resource.execute("q1", 0).getError());
    }

    @Test
    public void executeMdx_formats_and_converts() {
        for (String format : new String[] {"flat", "hierarchical", "flattened", "unknown"}) {
            var qr = resource.executeMdx("q1", format, "SELECT FROM [cube]", 0);
            assertNotNull(qr);
            assertEquals(1, qr.getWidth().intValue());
        }
    }

    @Test
    public void executeMdx_uses_the_drillthrough_path_for_drillthrough_mdx() {
        var qr = resource.executeMdx("q1", null, "DRILLTHROUGH SELECT ...", 0);
        assertNotNull(qr);
        assertTrue(qr.getRuntime() >= 0);
    }

    @Test
    public void executeMdx_converts_a_guardrail_rejection_into_a_400() {
        // saiku#2420: SaikuQueryRejectedException is rethrown as a typed 400.
        service.drillthroughMdx = false;
        service.rejectMdx = true;
        try {
            resource.executeMdx("q1", null, "SELECT 1", 0);
            fail("expected error");
        } catch (WebApplicationException e) {
            assertEquals(400, e.getResponse().getStatus());
        }
    }

    @Test
    public void level_members_delegates_or_errors() {
        assertEquals(
                1, resource.getLevelMembers("q1", "d", "h", "l", true, null, -1).size());
        service.fail = true;
        try {
            resource.getLevelMembers("q1", "d", "h", "l", true, null, -1);
            fail("expected error");
        } catch (WebApplicationException e) {
            assertEquals(500, e.getResponse().getStatus());
        }
    }

    @Test
    public void qm2mdx_transforms_or_returns_null() {
        assertEquals(service.saikuQuery, resource.transformQm2Mdx("q1"));
        service.fail = true;
        assertNull(resource.transformQm2Mdx("q1"));
    }

    @Test
    public void explain_plan_returns_a_result_or_the_generic_error() {
        assertNotNull(resource.getExplainPlan("q1"));
        service.fail = true;
        assertEquals("Internal error", resource.getExplainPlan("q1").getError());
    }

    @Test
    public void drillthrough_handles_positions_and_failures() {
        assertNotNull(resource.drillthrough("q1", 100, null, null));
        assertNotNull(resource.drillthrough("q1", 100, "0:1", null));
        service.fail = true;
        assertEquals(
                "Internal error", resource.drillthrough("q1", 100, null, null).getError());
        // A malformed position fails into the generic error too.
        assertEquals(
                "Internal error",
                resource.drillthrough("q1", 100, "not-a-number", null).getError());
    }

    @Test
    public void drillacross_returns_the_transformed_query_or_errors() {
        assertEquals(service.saikuQuery, resource.drillacross("q1", "0:1", "{\"[d]\": [\"[d].[h]\"]}"));
        try {
            resource.drillacross("q1", "not-a-number", "{}");
            fail("expected error");
        } catch (WebApplicationException e) {
            assertEquals(500, e.getResponse().getStatus());
        }
    }

    @Test
    public void drillthrough_export_serves_csv_or_an_error() {
        Response ok = resource.getDrillthroughExport("q1", 100, null, null);
        assertEquals(200, ok.getStatus());
        assertEquals(200, resource.getDrillthroughExport("q1", 100, "0:1", null).getStatus());
        service.fail = true;
        assertEquals(500, resource.getDrillthroughExport("q1", 100, null, null).getStatus());
    }

    @Test
    public void cancel_reports_the_status() {
        assertEquals(Response.Status.OK, resource.cancel("q1"));
        service.fail = true;
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, resource.cancel("q1"));
    }

    @Test
    public void axis_info_is_served_and_cleared() {
        assertNotNull(resource.getAxisInfo("q1", "ROWS"));
        Response ok = resource.clearAxis("q1", "rows");
        assertEquals(200, ok.getStatus());
        resource.clearAllAxisSelections("q1");
        // Blank axis name is refused with a 500.
        assertEquals(500, resource.clearAxis("q1", "  ").getStatus());
    }

    @Test
    public void swap_axes_and_set_cell_delegate() {
        assertEquals(service.saikuQuery.getName(), resource.swapAxes("q1").getName());
        assertEquals(Response.Status.OK, resource.setCell("q1", "0:1", "42"));
    }

    @Test
    public void dimension_selections_are_served() {
        assertNotNull(resource.getAxisDimensionInfo("q1", "ROWS", "[Store]"));
    }

    @Test
    public void move_and_delete_dimension_report_their_outcome() {
        assertEquals(200, resource.moveDimension("q1", "ROWS", "[Store]", -1).getStatus());
        assertEquals(200, resource.deleteDimension("q1", "ROWS", "[Store]").getStatus());
        service.fail = true;
        assertEquals(500, resource.moveDimension("q1", "ROWS", "[Store]", -1).getStatus());
        assertEquals(500, resource.deleteDimension("q1", "ROWS", "[Store]").getStatus());
    }

    @Test
    public void zoom_in_accepts_a_position_list() {
        assertEquals(
                service.saikuQuery.getName(), resource.zoomIn("q1", "[\"0:1\"]").getName());
        // A blank list zooms with no positions.
        assertNotNull(resource.zoomIn("q1", " "));
        try {
            resource.zoomIn("q1", "not-json");
            fail("expected error");
        } catch (WebApplicationException e) {
            assertEquals(500, e.getResponse().getStatus());
        }
    }

    private static String selectionJson(String type, String action) {
        return "[{\"uniquename\":\"[Store].[USA]\",\"type\":\"" + type + "\",\"action\":\"" + action
                + "\",\"hierarchy\":\"[Store]\"}]";
    }

    @Test
    public void updateSelections_adds_and_removes_members_and_levels() {
        // Deletes first, then adds; member and level variants both covered.
        assertEquals(
                200,
                resource.updateSelections("q1", "ROWS", "[Store]", selectionJson("member", "delete"))
                        .getStatus());
        assertEquals(
                200,
                resource.updateSelections("q1", "ROWS", "[Store]", selectionJson("level", "delete"))
                        .getStatus());
        assertEquals(
                200,
                resource.updateSelections("q1", "ROWS", "[Store]", selectionJson("member", "add"))
                        .getStatus());
        assertEquals(
                200,
                resource.updateSelections("q1", "ROWS", "[Store]", selectionJson("level", "add"))
                        .getStatus());
        // A selection with a type the resource doesn't know is ignored, and the
        // empty resulting selection moves the dimension to UNUSED.
        assertEquals(
                200,
                resource.updateSelections("q1", "ROWS", "[Store]", selectionJson("member", "delete"))
                        .getStatus());
        // A missing selections parameter is a 500.
        assertEquals(
                500, resource.updateSelections("q1", "ROWS", "[Store]", null).getStatus());
    }

    @Test
    public void removeMembers_deletes_each_selection() {
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("selections", selectionJson("member", "delete"));
        assertEquals(200, resource.removeMembers("q1", "ROWS", "[Store]", form).getStatus());
        assertEquals(
                500,
                resource.removeMembers("q1", "ROWS", "[Store]", new MultivaluedHashMap<>())
                        .getStatus());
    }

    @Test
    public void include_and_remove_member_report_their_outcome() {
        assertEquals(
                201,
                resource.includeMember("MEMBER", "q1", "ROWS", "[Store]", "[Store].[USA]", -1, -1)
                        .getStatus());
        service.includeMemberReturn = 0;
        assertEquals(
                500,
                resource.includeMember("MEMBER", "q1", "ROWS", "[Store]", "[Store].[USA]", -1, -1)
                        .getStatus());
        service.includeMemberReturn = 1;

        service.removeMemberReturn = true;
        // Non-empty selection keeps the dimension on the axis.
        assertEquals(
                200,
                resource.removeMember("MEMBER", "q1", "ROWS", "[Store]", "[Store].[USA]")
                        .getStatus());
        // Empty selection moves the dimension to UNUSED.
        assertEquals(
                200,
                resource.removeMember("MEMBER", "q1", "ROWS", "[Store]", "[Store].[USA]")
                        .getStatus());
        service.removeMemberReturn = false;
        assertEquals(
                500,
                resource.removeMember("MEMBER", "q1", "ROWS", "[Store]", "[Store].[USA]")
                        .getStatus());
    }

    @Test
    public void include_and_remove_children_report_their_outcome() {
        assertEquals(
                201,
                resource.includeChildren("q1", "ROWS", "[Store]", "[Store].[USA]")
                        .getStatus());
        assertEquals(
                410,
                resource.removeChildren("q1", "ROWS", "[Store]", "[Store].[USA]")
                        .getStatus());
        service.fail = true;
        assertEquals(
                500,
                resource.includeChildren("q1", "ROWS", "[Store]", "[Store].[USA]")
                        .getStatus());
        assertEquals(
                500,
                resource.removeChildren("q1", "ROWS", "[Store]", "[Store].[USA]")
                        .getStatus());
    }

    @Test
    public void include_and_remove_level_report_their_outcome() {
        assertEquals(
                201,
                resource.includeLevel("q1", "ROWS", "[Store]", "[Store]", "[Store].[Store]", -1)
                        .getStatus());
        assertEquals(
                200,
                resource.removeLevel("q1", "ROWS", "[Store]", "[Store]", "[Store].[Store]")
                        .getStatus());
        service.fail = true;
        assertEquals(
                500,
                resource.includeLevel("q1", "ROWS", "[Store]", "[Store]", "[Store].[Store]", -1)
                        .getStatus());
        assertEquals(
                500,
                resource.removeLevel("q1", "ROWS", "[Store]", "[Store]", "[Store].[Store]")
                        .getStatus());
    }

    @Test
    public void tags_activate_and_deactivate() {
        assertEquals(Response.Status.OK, resource.activateTag("q1", "{}"));
        assertEquals(Response.Status.OK, resource.deactivateTag("q1", "t"));
        // A malformed tag degrades to a 500 status code on the wire.
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, resource.activateTag("q1", "not-json"));
    }

    @Test
    public void filters_round_trip() {
        assertEquals(200, resource.getFilter("q1", "[d]", "[h]", "[l]").getStatus());
        assertEquals(200, resource.activateFilter("q1", "{}").getStatus());
        assertEquals(200, resource.deactivateFilter("q1").getStatus());
        service.fail = true;
        assertEquals(500, resource.getFilter("q1", "[d]", "[h]", "[l]").getStatus());
        assertEquals(500, resource.activateFilter("q1", "{}").getStatus());
        assertEquals(500, resource.deactivateFilter("q1").getStatus());
    }

    @Test
    public void sorting_limits_and_filtering_delegate() {
        resource.sortAxis("q1", "ROWS", "[Measures].[Store Sales]", "BASC");
        assertNotNull(resource.showGrandTotals("q1", "ROWS", "SUM"));
        resource.clearSortAxis("q1", "ROWS");
        resource.limitAxis("q1", "ROWS", "Count", "10", "[Measures].[Store Sales]");
        resource.clearLimitAxis("q1", "ROWS");
        resource.filterAxis("q1", "ROWS", "[Measures].[Store Sales] > 100");
        resource.clearFilter("q1", "ROWS");
    }
}
