/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Unit test for {@link AiQueryResource}. Mirrors the stub pattern used by
 * Query2ResourceArrowTest: we inject fake services that return a hand-rolled
 * CellDataSet, then assert on the JSON response.
 */
public class AiQueryResourceTest {

    private AiQueryResource resource;
    private AiSchema schema;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(AiSchema.key("Store Sales"),
                new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"),
                new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
        resource.setThinQueryService(new StubThinQueryService());
    }

    private AiQueryRequest baseRequest() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        return req;
    }

    @Test
    public void happyPathReturns200WithMatrix() {
        Response resp = resource.executeAi(baseRequest());
        assertEquals(200, resp.getStatus());

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.SUCCESS, body.getStatus());
        assertNotNull(body.getQueryId());
        assertNotNull(body.getMetadata());
        assertNotNull("generated MDX should be echoed", body.getMetadata().getGeneratedMdx());
        assertTrue("MDX should reference the schema cube",
                body.getMetadata().getGeneratedMdx().contains("FROM [Sales]"));

        List<Map<String, String>> matrix = body.getMatrix();
        assertEquals("2 rows in stubbed result", 2, matrix.size());
        assertEquals("100", matrix.get(0).get("0"));
        assertEquals("200", matrix.get(1).get("0"));

        assertEquals("row caption from MemberCell", "1997", body.getMetadata().getRows().get(0).getCaption());
        assertEquals("column caption from header", "Store Sales", body.getMetadata().getColumns().get(0).getCaption());
    }

    @Test
    public void unknownMeasureReturns400WithStructuredError() {
        AiQueryRequest req = baseRequest();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Bogus")));

        Response resp = resource.executeAi(req);
        assertEquals(400, resp.getStatus());

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getStatus());
        assertEquals("measures[].name", body.getField());
        assertTrue("error should list valid measures", body.getAvailable().contains("Store Sales"));
    }

    @Test
    public void missingCubeReturns400() {
        AiQueryRequest req = new AiQueryRequest();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));

        Response resp = resource.executeAi(req);
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("cube", body.getField());
    }

    @Test
    public void cubeMetadataServiceErrorPropagatesAs400() {
        resource.setCubeMetadataService(ref -> {
            throw new AiValidationException("cube", "Unknown cube " + ref, Collections.singletonList("Sales"));
        });

        Response resp = resource.executeAi(baseRequest());
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("cube", body.getField());
        assertTrue(body.getAvailable().contains("Sales"));
    }

    /* -------------------- Phase 2: cubes + schema ---------------------------- */

    @Test
    public void parseCubeIdRejectsMalformedInputs() {
        org.junit.Assert.assertNull(AiQueryResource.parseCubeId(null));
        org.junit.Assert.assertNull(AiQueryResource.parseCubeId(""));
        org.junit.Assert.assertNull(AiQueryResource.parseCubeId("only/three/parts"));
        org.junit.Assert.assertNull(AiQueryResource.parseCubeId("/leading/empty/segment"));
        org.junit.Assert.assertNotNull(AiQueryResource.parseCubeId("foodmart/FoodMart/FoodMart/Sales"));
    }

    @Test
    public void getSchemaReturns200WithTypedSchema() {
        Response resp = resource.getSchema("foodmart/FoodMart/FoodMart/Sales");
        assertEquals(200, resp.getStatus());
        AiSchema body = (AiSchema) resp.getEntity();
        assertEquals("Sales", body.getCubeName());
        assertTrue(body.measures.containsKey(AiSchema.key("Store Sales")));
        assertTrue(body.dimensions.containsKey(AiSchema.key("Time")));
    }

    @Test
    public void getSchemaWithMalformedCubeIdReturns400() {
        Response resp = resource.getSchema("not-four-segments");
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("cubeId", body.getField());
    }

    @Test
    public void getSchemaWithUnknownCubeReturns400WithCandidates() {
        resource.setCubeMetadataService(ref -> {
            throw new AiValidationException("cube",
                    "Unknown cube",
                    java.util.Arrays.asList("Sales", "HR"));
        });
        Response resp = resource.getSchema("foodmart/FoodMart/FoodMart/Nonsense");
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertTrue(body.getAvailable().contains("Sales"));
    }

    @Test
    public void listCubesReturns500WhenInterfaceOnly() {
        // Default test setUp uses a lambda impl, not an OlapAiCubeMetadataService.
        Response resp = resource.listCubes();
        assertEquals(500, resp.getStatus());
    }

    @Test
    public void listCubesReturns200WhenOlapBacked() {
        org.saiku.service.olap.ai.OlapAiCubeMetadataService svc =
                new org.saiku.service.olap.ai.OlapAiCubeMetadataService() {
                    @Override
                    public java.util.List<org.saiku.service.olap.ai.AiCubeSummary> listCubes() {
                        org.saiku.service.olap.ai.AiCubeSummary s = new org.saiku.service.olap.ai.AiCubeSummary();
                        s.setConnectionName("foodmart");
                        s.setCubeName("Sales");
                        s.setMeasureCount(2);
                        return java.util.Collections.singletonList(s);
                    }
                    @Override
                    public AiSchema getSchema(org.saiku.service.olap.ai.AiCubeRef ref) { return schema; }
                };
        resource.setCubeMetadataService(svc);
        Response resp = resource.listCubes();
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        java.util.List<org.saiku.service.olap.ai.AiCubeSummary> body =
                (java.util.List<org.saiku.service.olap.ai.AiCubeSummary>) resp.getEntity();
        assertEquals(1, body.size());
        assertEquals("Sales", body.get(0).getCubeName());
    }

    /* ----------------------- Phase 4: async ---------------------------------- */

    @Test
    public void executeAiAsyncReturns202WithQueryId() throws Exception {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);

            Response resp = resource.executeAiAsync(baseRequest());
            assertEquals(202, resp.getStatus());
            AiQueryResponse body = (AiQueryResponse) resp.getEntity();
            assertNotNull("async submit returns a queryId", body.getQueryId());
            assertNotNull("generated MDX echoed for diagnostics", body.getMetadata().getGeneratedMdx());
        } finally {
            async.shutdown();
        }
    }

    @Test
    public void executeAiAsyncReturns400OnUnknownMeasure() throws Exception {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);
            AiQueryRequest bad = baseRequest();
            bad.setMeasures(java.util.Collections.singletonList(new AiMeasureSelection("BogusMeasure")));
            Response resp = resource.executeAiAsync(bad);
            assertEquals(400, resp.getStatus());
        } finally {
            async.shutdown();
        }
    }

    @Test
    public void asyncStatusReturns404OnUnknownId() {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);
            Response resp = resource.asyncStatus("does-not-exist");
            assertEquals(404, resp.getStatus());
        } finally {
            async.shutdown();
        }
    }

    @Test
    public void asyncResultReturns404OnUnknownId() {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);
            Response resp = resource.asyncResult("does-not-exist");
            assertEquals(404, resp.getStatus());
        } finally {
            async.shutdown();
        }
    }

    @Test
    public void asyncCancelReturns404OnUnknownId() {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);
            Response resp = resource.asyncCancel("does-not-exist");
            assertEquals(404, resp.getStatus());
        } finally {
            async.shutdown();
        }
    }

    @Test
    public void executeAiAsyncReturns500WithoutAsyncService() {
        resource.setAsyncQueryService(null);
        Response resp = resource.executeAiAsync(baseRequest());
        assertEquals(500, resp.getStatus());
    }

    /* ----------------------- Phase 4: drillthrough --------------------------- */

    @Test
    public void drillthroughReturns200WithRows() {
        // Override the ThinQueryService with one that has a stub drillthrough.
        resource.setThinQueryService(new StubThinQueryServiceWithDrill());
        Response resp = resource.drillthrough("sync-query-id", 100, null);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("sync-query-id", body.get("queryId"));
        assertEquals(2, body.get("rowCount"));
    }

    @Test
    public void drillthroughErrorReturns500() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public java.sql.ResultSet drillthrough(String n, int m, String r) {
                throw new RuntimeException("boom");
            }
        });
        Response resp = resource.drillthrough("any-id", 100, null);
        assertEquals(500, resp.getStatus());
    }

    /* ------------------------ stub impls --------------------------------- */

    /** Returns a hand-rolled 2x1 CellDataSet (rows = [1997, 1998], measures = [Store Sales]). */
    private static class StubThinQueryService extends ThinQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            return buildStubCellDataSet();
        }
    }

    /** Stub that returns a 2-row ResultSet for drillthrough probes. */
    private static class StubThinQueryServiceWithDrill extends ThinQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            return buildStubCellDataSet();
        }
        @Override
        public java.sql.ResultSet drillthrough(String name, int maxrows, String returns) {
            return buildStubResultSet();
        }
    }

    /** Build a tiny 2-row ResultSet via a JDK Proxy so we don't need a real DB. */
    static java.sql.ResultSet buildStubResultSet() {
        final java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        java.util.Map<String, Object> r1 = new java.util.LinkedHashMap<>();
        r1.put("year", "1997");
        r1.put("sales", 100);
        rows.add(r1);
        java.util.Map<String, Object> r2 = new java.util.LinkedHashMap<>();
        r2.put("year", "1998");
        r2.put("sales", 200);
        rows.add(r2);
        final String[] columnNames = rows.get(0).keySet().toArray(new String[0]);

        final java.sql.ResultSetMetaData md = (java.sql.ResultSetMetaData) java.lang.reflect.Proxy.newProxyInstance(
                AiQueryResourceTest.class.getClassLoader(),
                new Class<?>[] { java.sql.ResultSetMetaData.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnCount": return columnNames.length;
                        case "getColumnLabel":
                        case "getColumnName": return columnNames[((Integer) args[0]) - 1];
                        case "toString": return "StubMetaData";
                        default: return defaultForReturn(method.getReturnType());
                    }
                });

        final int[] cursor = { -1 };
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                AiQueryResourceTest.class.getClassLoader(),
                new Class<?>[] { java.sql.ResultSet.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next": return ++cursor[0] < rows.size();
                        case "close": return null;
                        case "getMetaData": return md;
                        case "getObject":
                            if (args != null && args.length >= 1 && args[0] instanceof Integer) {
                                int col1 = (Integer) args[0];
                                return rows.get(cursor[0]).get(columnNames[col1 - 1]);
                            }
                            return null;
                        case "toString": return "StubResultSet";
                        default: return defaultForReturn(method.getReturnType());
                    }
                });
    }

    private static Object defaultForReturn(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class || rt == short.class || rt == byte.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0.0f;
        if (rt == double.class) return 0.0;
        if (rt == void.class) return null;
        return null;
    }


    static CellDataSet buildStubCellDataSet() {
        CellDataSet cds = new CellDataSet(2, 1);
        // Header rows: column header containing the measure caption.
        // Shape: [emptyRowHeader][measureCaption]
        AbstractBaseCell hdrA = new MemberCell(false, false);
        hdrA.setFormattedValue("");
        AbstractBaseCell hdrB = new MemberCell(false, false);
        hdrB.setFormattedValue("Store Sales");
        cds.setCellSetHeaders(new AbstractBaseCell[][] { new AbstractBaseCell[] { hdrA, hdrB } });

        // Body: [yearMemberCell][dataCell]
        AbstractBaseCell r1m = new MemberCell(false, false);
        r1m.setFormattedValue("1997");
        AbstractBaseCell r1d = new DataCell(true, false, null);
        r1d.setFormattedValue("100");
        r1d.setRawValue("100");

        AbstractBaseCell r2m = new MemberCell(false, false);
        r2m.setFormattedValue("1998");
        AbstractBaseCell r2d = new DataCell(true, false, null);
        r2d.setFormattedValue("200");
        r2d.setRawValue("200");

        cds.setCellSetBody(new AbstractBaseCell[][] {
                new AbstractBaseCell[] { r1m, r1d },
                new AbstractBaseCell[] { r2m, r2d },
        });
        return cds;
    }
}
