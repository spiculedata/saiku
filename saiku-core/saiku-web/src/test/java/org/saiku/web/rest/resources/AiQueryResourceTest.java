/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.saiku.service.olap.ai.AiCell;
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
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
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
    public void happyPathReturns200WithRecords() {
        Response resp = resource.executeAi(baseRequest(), "records");
        assertEquals(200, resp.getStatus());

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.SUCCESS, body.getStatus());
        assertEquals("records", body.getFormat());
        assertNotNull(body.getQueryId());
        assertNotNull(body.getMetadata());
        assertNotNull("generated MDX should be echoed", body.getMetadata().getGeneratedMdx());
        assertTrue(
                "MDX should reference the schema cube",
                body.getMetadata().getGeneratedMdx().contains("FROM [Sales]"));
        assertNotNull("freshness metadata is populated", body.getMetadata().getFreshness());

        List<Map<String, Object>> data = body.getData();
        assertEquals("2 row records in stubbed result", 2, data.size());

        Map<String, Object> row0 = data.get(0);
        assertEquals("1997", row0.get("Year"));
        AiCell cell0 = (AiCell) row0.get("Store Sales");
        assertNotNull("Store Sales cell present", cell0);
        assertEquals(Double.valueOf(100.0), cell0.getValue());
        assertEquals("100", cell0.getFormatted());

        Map<String, Object> row1 = data.get(1);
        assertEquals("1998", row1.get("Year"));
        AiCell cell1 = (AiCell) row1.get("Store Sales");
        assertEquals(Double.valueOf(200.0), cell1.getValue());

        assertEquals("matrix is empty in records mode", 0, body.getMatrix().size());
        assertEquals(
                "row caption from MemberCell",
                "1997",
                body.getMetadata().getRows().get(0).getCaption());
        assertEquals(
                "column caption from header",
                "Store Sales",
                body.getMetadata().getColumns().get(0).getCaption());
    }

    @Test
    public void matrixFormatReturnsTypedCells() {
        Response resp = resource.executeAi(baseRequest(), "matrix");
        assertEquals(200, resp.getStatus());

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("matrix", body.getFormat());

        List<Map<String, AiCell>> matrix = body.getMatrix();
        assertEquals("2 rows in stubbed result", 2, matrix.size());
        AiCell c0 = matrix.get(0).get("0");
        assertNotNull(c0);
        assertEquals(Double.valueOf(100.0), c0.getValue());
        assertEquals("100", c0.getFormatted());
        assertEquals("data is empty in matrix mode", 0, body.getData().size());
    }

    @Test
    public void unknownMeasureReturns400WithStructuredError() {
        AiQueryRequest req = baseRequest();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Bogus")));

        Response resp = resource.executeAi(req, "records");
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

        Response resp = resource.executeAi(req, "records");
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("cube", body.getField());
    }

    @Test
    public void cubeStringFormAcceptedInRequest() throws Exception {
        // The Jackson @JsonAnySetter path: cube as "conn/cat/schema/cube" string.
        String json = "{\"cube\":\"foodmart/FoodMart/FoodMart/Sales\"," + "\"measures\":[{\"name\":\"Store Sales\"}]}";
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        AiQueryRequest parsed = om.readValue(json, AiQueryRequest.class);
        assertNotNull(parsed.getCube());
        assertEquals("foodmart", parsed.getCube().getConnectionName());
        assertEquals("Sales", parsed.getCube().getCubeName());
    }

    @Test
    public void cubeMetadataServiceErrorPropagatesAs400() {
        resource.setCubeMetadataService(ref -> {
            throw new AiValidationException("cube", "Unknown cube " + ref, Collections.singletonList("Sales"));
        });

        Response resp = resource.executeAi(baseRequest(), "records");
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
            throw new AiValidationException("cube", "Unknown cube", java.util.Arrays.asList("Sales", "HR"));
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
                    public AiSchema getSchema(org.saiku.service.olap.ai.AiCubeRef ref) {
                        return schema;
                    }
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

    /* --- saiku#915 / MCP drillthrough cross-session re-attach ---------- */

    @Test
    public void syncExecuteRegistersHandleForCrossSessionDrillthrough() {
        // MCP smoke 2026-05-23: a follow-up drillthrough call lands on a
        // different session than the sync run_query (JSESSIONID per
        // request). Without the cross-session re-attach, drillthrough's
        // resolver sees an empty per-session context and surfaces
        // "Unknown queryId" even though the result is hot.
        //
        // Fix: executeAi mirrors the async-submission path — register a
        // pre-completed AsyncQueryHandle in the cross-session
        // AsyncQueryService so drillthrough's existing handle-fallback
        // path can re-attach. Test pins that the handle ends up in the
        // expected DONE state with the executed ThinQuery.
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        resource.setAsyncQueryService(async);
        // Custom stub that populates the QueryContext getOlapResult so the
        // re-attach branch can read the CellSet. Default StubThinQueryService
        // returns null context; this one stores a no-op CellSet via the
        // registerExternalContext seam the production code uses too.
        resource.setThinQueryService(new StubThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                registerExternalContext(tq, null);
                return buildStubCellDataSet();
            }
        });

        Response resp = resource.executeAi(baseRequest(), "records");
        assertEquals(200, resp.getStatus());

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        String queryId = body.getQueryId();
        assertNotNull("sync executeAi returns a queryId", queryId);

        org.saiku.service.async.AsyncQueryHandle handle = async.get(queryId);
        assertNotNull("sync executeAi should register a pre-completed handle for cross-session drillthrough", handle);
        assertEquals(
                "registered handle reports DONE so resource-layer drillthrough resolves it",
                org.saiku.service.async.AsyncQueryHandle.Status.DONE,
                handle.getStatus());
        assertEquals(
                "handle carries the executed ThinQuery so registerExternalContext re-attaches the right query",
                queryId,
                handle.getQuery().getName());
    }

    @Test
    public void syncExecuteSucceedsEvenIfAsyncQueryServiceUnwired() {
        // Back-compat: callers that don't wire asyncQueryService (older
        // webapp configs, the JSP-only flow, tests) must still get a
        // clean 200. The re-attach is an opt-in cross-session
        // enhancement, not a required pre-condition.
        resource.setAsyncQueryService(null);
        Response resp = resource.executeAi(baseRequest(), "records");
        assertEquals(200, resp.getStatus());
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
            assertNotNull(
                    "generated MDX echoed for diagnostics", body.getMetadata().getGeneratedMdx());
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

    /** saiku#803: measures-only query (no rows) must flatten to a single
     *  record keyed by measure caption with typed AiCell values, not the
     *  awkward row0/row1 two-record header-+-data shape. */
    @Test
    public void measuresOnlyQueryFlattensToSingleRecordWithTypedCells() {
        // Extend the fixture schema with Unit Sales for this test only.
        schema.measures.put(AiSchema.key("Unit Sales"), new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]"));
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return buildMeasuresOnlyCellDataSet();
            }
        });
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(
                java.util.Arrays.asList(new AiMeasureSelection("Unit Sales"), new AiMeasureSelection("Store Sales")));
        // No rows — measures-only.

        Response resp = resource.executeAi(req, "records");
        // Surface the error message if anything ahead of the renderer bounced it.
        if (resp.getStatus() != 200) {
            AiQueryResponse bad = (AiQueryResponse) resp.getEntity();
            throw new AssertionError(
                    "expected 200, got " + resp.getStatus() + " field=" + bad.getField() + " error=" + bad.getError());
        }

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("single record (not 2)", 1, body.getData().size());
        Map<String, Object> rec = body.getData().get(0);
        assertTrue("Unit Sales keyed by caption", rec.containsKey("Unit Sales"));
        assertTrue("Store Sales keyed by caption", rec.containsKey("Store Sales"));
        AiCell us = (AiCell) rec.get("Unit Sales");
        assertEquals("typed AiCell with formatted", "266,773", us.getFormatted());
        AiCell ss = (AiCell) rec.get("Store Sales");
        assertEquals("typed AiCell with formatted", "565,238.13", ss.getFormatted());
        assertFalse("no row0/row1 keys leaked", rec.containsKey("row0"));
        assertFalse("no row0/row1 keys leaked", rec.containsKey("row1"));
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

    /** saiku#792: cancel on a DONE handle must report ALREADY_COMPLETED, not
     *  the misleading CANCELLED the legacy path used to return. */
    @Test
    public void asyncCancelReturnsAlreadyCompletedForFinishedQuery() {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);
            org.saiku.olap.query2.ThinQuery tq = new org.saiku.olap.query2.ThinQuery();
            tq.setName("done-query");
            org.saiku.service.async.AsyncQueryHandle h = new org.saiku.service.async.AsyncQueryHandle("done-id", tq);
            h.setStatus(org.saiku.service.async.AsyncQueryHandle.Status.DONE);
            async.register(h);
            Response resp = resource.asyncCancel("done-id");
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getEntity();
            assertEquals("ALREADY_COMPLETED", body.get("status"));
        } finally {
            async.shutdown();
        }
    }

    @Test
    public void asyncCancelReturnsAlreadyFailedForFailedQuery() {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);
            org.saiku.olap.query2.ThinQuery tq = new org.saiku.olap.query2.ThinQuery();
            tq.setName("failed-query");
            org.saiku.service.async.AsyncQueryHandle h = new org.saiku.service.async.AsyncQueryHandle("failed-id", tq);
            h.setStatus(org.saiku.service.async.AsyncQueryHandle.Status.FAILED);
            async.register(h);
            Response resp = resource.asyncCancel("failed-id");
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getEntity();
            assertEquals("ALREADY_FAILED", body.get("status"));
        } finally {
            async.shutdown();
        }
    }

    @Test
    public void asyncCancelIsIdempotentForCancelledQuery() {
        org.saiku.service.async.AsyncQueryService async = new org.saiku.service.async.AsyncQueryService();
        async.setThinQueryService(new StubThinQueryService());
        try {
            resource.setAsyncQueryService(async);
            org.saiku.olap.query2.ThinQuery tq = new org.saiku.olap.query2.ThinQuery();
            tq.setName("cancelled-query");
            org.saiku.service.async.AsyncQueryHandle h =
                    new org.saiku.service.async.AsyncQueryHandle("cancelled-id", tq);
            h.setStatus(org.saiku.service.async.AsyncQueryHandle.Status.CANCELLED);
            async.register(h);
            Response resp = resource.asyncCancel("cancelled-id");
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getEntity();
            assertEquals("CANCELLED", body.get("status"));
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
        Response resp = resource.drillthrough("sync-query-id", 100, null, null, null);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("sync-query-id", body.get("queryId"));
        assertEquals(2, body.get("rowCount"));
    }

    @Test
    public void drillthroughCellsAreTypedEnvelopes() {
        resource.setThinQueryService(new StubThinQueryServiceWithDrill());
        Response resp = resource.drillthrough("sync-query-id", 100, null, null, null);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        @SuppressWarnings("unchecked")
        List<Map<String, AiCell>> rows = (List<Map<String, AiCell>>) body.get("rows");
        assertNotNull(rows);
        assertEquals(2, rows.size());

        AiCell year0 = rows.get(0).get("year");
        assertNotNull(year0);
        assertEquals("1997", year0.getFormatted()); // String column → formatted only
        assertEquals(Double.valueOf(1997.0), year0.getValue()); // parsed from formatted

        AiCell sales0 = rows.get(0).get("sales");
        assertNotNull(sales0);
        assertEquals(Double.valueOf(100.0), sales0.getValue()); // numeric column → typed value
        assertEquals("100", sales0.getFormatted());
    }

    @Test
    public void drillthroughErrorReturns500() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public java.sql.ResultSet drillthrough(String n, int m, Integer f, String r) {
                throw new RuntimeException("boom");
            }
        });
        Response resp = resource.drillthrough("any-id", 100, null, null, null);
        assertEquals(500, resp.getStatus());
    }

    /* ----------------------- saiku#774: column discovery + FIRST_ROWSET ----------------------- */

    @Test
    public void drillthroughColumnsReturnsListWithNameAndType() {
        resource.setThinQueryService(new StubThinQueryServiceWithDrill());
        Response resp = resource.drillthroughColumns("sync-query-id");
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("sync-query-id", body.get("queryId"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> cols = (List<Map<String, String>>) body.get("columns");
        assertNotNull("columns list populated", cols);
        assertEquals("2 columns from the stubbed MAXROWS 1 result", 2, cols.size());
        assertEquals("year", cols.get(0).get("name"));
        assertEquals("VARCHAR", cols.get(0).get("type"));
        assertEquals("sales", cols.get(1).get("name"));
        assertEquals("INTEGER", cols.get(1).get("type"));
    }

    @Test
    public void drillthroughColumnsUnknownQueryIdReturns404() {
        // Default ThinQueryService throws NPE on unknown queryId — same
        // translation path as the drillthrough handler.
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public List<Map<String, String>> drillthroughColumns(String queryName) {
                throw new NullPointerException("unknown queryId");
            }
        });
        Response resp = resource.drillthroughColumns("missing");
        assertEquals(404, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getStatus());
        assertEquals("queryId", body.getField());
    }

    @Test
    public void drillthroughForwardsFirstRowsetToService() {
        // Verify the resource passes firstRowset through to the service
        // and the service-side wrapper picks FIRST_ROWSET over MAXROWS.
        final Integer[] firstRowsetSeen = {null};
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public java.sql.ResultSet drillthrough(String n, int m, Integer f, String r) {
                firstRowsetSeen[0] = f;
                return buildStubResultSet();
            }
        });
        Response resp = resource.drillthrough("sync-query-id", 100, 25, null, null);
        assertEquals(200, resp.getStatus());
        assertEquals(Integer.valueOf(25), firstRowsetSeen[0]);
    }

    @Test
    public void drillthroughWithPositionUsesCellPositionOverload() {
        // saiku#930: a position=col:row drills the single cell via the
        // List<Integer> cellPosition service overload (not the whole-result one).
        final java.util.concurrent.atomic.AtomicReference<java.util.List<Integer>> posSeen =
                new java.util.concurrent.atomic.AtomicReference<>();
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public java.sql.ResultSet drillthrough(String n, java.util.List<Integer> pos, Integer m, String r) {
                posSeen.set(pos);
                return buildStubResultSet();
            }
        });
        Response resp = resource.drillthrough("sync-query-id", 100, null, "0:1", null);
        assertEquals(200, resp.getStatus());
        assertEquals(java.util.Arrays.asList(0, 1), posSeen.get());
    }

    @Test
    public void drillthroughMalformedPositionReturns400() {
        // saiku#930: a non-numeric position is a clean 400 (field=position),
        // not a 500 from deep in the engine.
        resource.setThinQueryService(new StubThinQueryServiceWithDrill());
        Response resp = resource.drillthrough("sync-query-id", 100, null, "abc", null);
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("position", body.getField());
        // The message must state the real contract — column-axis index first
        // ("col:row"), matching the javadoc + AI-QUERY-API.md, NOT the inverted
        // "row:col" the string used to claim (saiku#930 doc/error fix).
        String err = body.getError();
        assertNotNull(err);
        assertTrue("error should state the col:row order", err.contains("col:row"));
        assertFalse("error must not state the inverted row:col order", err.contains("row:col"));
    }

    /* --- #1160 characterization: lock the error-translation branches ----- */

    /** saiku#783: an unknown queryId leaks an NPE from the internal
     *  QueryContext lookup; the resource must translate that to a clean 404
     *  with the typed VALIDATION_ERROR envelope and field=queryId. */
    @Test
    public void drillthroughUnknownQueryIdReturns404() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public java.sql.ResultSet drillthrough(String n, int m, Integer f, String r) {
                throw new NullPointerException("internal QueryContext was null");
            }
        });
        Response resp = resource.drillthrough("missing-id", 100, null, null, null);
        assertEquals(404, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getStatus());
        assertEquals("queryId", body.getField());
    }

    /** saiku#794: drilling an empty source cellset (Mondrian throws
     *  "Cell coordinates ... fall outside CellSet bounds ...") is surfaced as
     *  an empty drillthrough — 200 with rowCount=0 — not a 500. */
    @Test
    public void drillthroughEmptyCellsetReturns200WithZeroRows() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public java.sql.ResultSet drillthrough(String n, int m, Integer f, String r) {
                throw new RuntimeException("Cell coordinates (0, 0) fall outside CellSet bounds (0, 0)");
            }
        });
        Response resp = resource.drillthrough("sync-query-id", 100, null, null, null);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("sync-query-id", body.get("queryId"));
        assertEquals(0, body.get("rowCount"));
    }

    /** saiku#795: a bad {@code returns=} clause surfaces from Mondrian as
     *  "... in RETURN clause" and must lift to a 400 with field=returns. */
    @Test
    public void drillthroughBadReturnsClauseReturns400() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public java.sql.ResultSet drillthrough(String n, int m, Integer f, String r) {
                throw new RuntimeException("Mondrian Error: Can't perform drillthrough operation on unknown member"
                        + " '[Bogus]' in RETURN clause");
            }
        });
        // No cubeMetadataService context for this name, so the resolver is
        // skipped and the raw returns value reaches the engine — exercising
        // the 400 translation in the execute/catch branch.
        Response resp = resource.drillthrough("sync-query-id", 100, null, null, "[Bogus]");
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getStatus());
        assertEquals("returns", body.getField());
    }

    /** saiku#800/#808: explicit columns[] are echoed in the whole-result body
     *  alongside queryId/rowCount/rows — locks the exact JSON body key set. */
    @Test
    public void drillthroughBodyExposesColumnsKey() {
        resource.setThinQueryService(new StubThinQueryServiceWithDrill());
        Response resp = resource.drillthrough("sync-query-id", 100, null, null, null);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertTrue("queryId key present", body.containsKey("queryId"));
        assertTrue("rowCount key present", body.containsKey("rowCount"));
        assertTrue("columns key present", body.containsKey("columns"));
        assertTrue("rows key present", body.containsKey("rows"));
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) body.get("columns");
        assertEquals(java.util.Arrays.asList("year", "sales"), columns);
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
        public java.sql.ResultSet drillthrough(String name, int maxrows, Integer firstRowset, String returns) {
            return buildStubResultSet();
        }

        @Override
        public List<Map<String, String>> drillthroughColumns(String queryName) {
            // Mirror the stub ResultSet's two columns so the discovery
            // endpoint test can assert against the same shape the
            // drillthrough endpoint serves.
            List<Map<String, String>> out = new java.util.ArrayList<>();
            Map<String, String> c1 = new java.util.LinkedHashMap<>();
            c1.put("name", "year");
            c1.put("type", "VARCHAR");
            out.add(c1);
            Map<String, String> c2 = new java.util.LinkedHashMap<>();
            c2.put("name", "sales");
            c2.put("type", "INTEGER");
            out.add(c2);
            return out;
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
                new Class<?>[] {java.sql.ResultSetMetaData.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnCount":
                            return columnNames.length;
                        case "getColumnLabel":
                        case "getColumnName":
                            return columnNames[((Integer) args[0]) - 1];
                        case "toString":
                            return "StubMetaData";
                        default:
                            return defaultForReturn(method.getReturnType());
                    }
                });

        final int[] cursor = {-1};
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                AiQueryResourceTest.class.getClassLoader(),
                new Class<?>[] {java.sql.ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            return ++cursor[0] < rows.size();
                        case "close":
                            return null;
                        case "getMetaData":
                            return md;
                        case "getObject":
                            if (args != null && args.length >= 1 && args[0] instanceof Integer) {
                                int col1 = (Integer) args[0];
                                return rows.get(cursor[0]).get(columnNames[col1 - 1]);
                            }
                            return null;
                        case "toString":
                            return "StubResultSet";
                        default:
                            return defaultForReturn(method.getReturnType());
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

    /** saiku#803: measures-only body shape — body[0] is all MemberCells
     *  (measure captions), body[1] is all DataCells (the values). */
    static CellDataSet buildMeasuresOnlyCellDataSet() {
        CellDataSet cds = new CellDataSet(2, 2);
        cds.setCellSetHeaders(new AbstractBaseCell[][] {});
        AbstractBaseCell h0 = new MemberCell(false, false);
        h0.setFormattedValue("Unit Sales");
        AbstractBaseCell h1 = new MemberCell(false, false);
        h1.setFormattedValue("Store Sales");
        AbstractBaseCell d0 = new DataCell(true, false, null);
        d0.setFormattedValue("266,773");
        d0.setRawValue("266773");
        AbstractBaseCell d1 = new DataCell(true, false, null);
        d1.setFormattedValue("565,238.13");
        d1.setRawValue("565238.13");
        cds.setCellSetBody(new AbstractBaseCell[][] {
            new AbstractBaseCell[] {h0, h1}, new AbstractBaseCell[] {d0, d1},
        });
        return cds;
    }

    static CellDataSet buildStubCellDataSet() {
        CellDataSet cds = new CellDataSet(2, 1);
        // Header rows: row-header column caption + measure caption.
        // Shape: [rowHeaderCaption("Year")][measureCaption("Store Sales")]
        AbstractBaseCell hdrA = new MemberCell(false, false);
        hdrA.setFormattedValue("Year");
        AbstractBaseCell hdrB = new MemberCell(false, false);
        hdrB.setFormattedValue("Store Sales");
        cds.setCellSetHeaders(new AbstractBaseCell[][] {new AbstractBaseCell[] {hdrA, hdrB}});

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
            new AbstractBaseCell[] {r1m, r1d},
            new AbstractBaseCell[] {r2m, r2d},
        });
        return cds;
    }
}
