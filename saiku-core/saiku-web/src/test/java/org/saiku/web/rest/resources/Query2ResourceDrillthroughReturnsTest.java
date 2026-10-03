/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.util.QueryContext;

/**
 * saiku#837 — Query2 parity for saiku#782: {@code Query2Resource.drillthrough} and {@code
 * .getDrillthroughExport} must resolve bare-caption {@code returns=} tokens to fully-qualified
 * MDX (via the same {@link org.saiku.service.olap.ai.AiReturnsResolver} the AI query surface
 * uses) before handing the value to {@link ThinQueryService}, and must answer a typed 400 for an
 * unknown token instead of letting Mondrian's parse error surface as an opaque 500.
 */
public class Query2ResourceDrillthroughReturnsTest {

    private static final String QUERY_NAME = "returns-test";

    /** Mini FoodMart-shape schema mirroring AiReturnsResolverTest's fixture. */
    private static AiSchema schema() {
        AiSchema schema = new AiSchema("cube-1", "Sales", "[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);
        return schema;
    }

    /** A ThinQueryService stub that serves a fixed QueryContext (so the resolver can find the
     *  query's cube) and records the {@code returns} value it was ultimately handed. */
    private static final class RecordingThinQueryService extends ThinQueryService {
        private final QueryContext context;
        String capturedReturns = "not-called";

        RecordingThinQueryService(SaikuCube cube) {
            ThinQuery tq = new ThinQuery();
            tq.setName(QUERY_NAME);
            tq.setCube(cube);
            this.context = new QueryContext(QueryContext.Type.OLAP, tq);
        }

        @Override
        public QueryContext getContext(String name) {
            return context;
        }

        @Override
        public ResultSet drillthrough(String queryName, int maxrows, String returns) {
            this.capturedReturns = returns;
            throw new IllegalStateException("stop-after-capture");
        }
    }

    private static AiCubeMetadataService fixedSchemaService() {
        return new AiCubeMetadataService() {
            @Override
            public AiSchema getSchema(AiCubeRef ref) {
                return schema();
            }
        };
    }

    private SaikuCube cube;

    @Before
    public void setUp() {
        cube = new SaikuCube("connection", "[Sales]", "Sales", "Sales", "FoodMart", "FoodMart");
    }

    private Query2Resource resourceWithRecordingService(RecordingThinQueryService svc) {
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(svc);
        resource.setCubeMetadataService(fixedSchemaService());
        return resource;
    }

    @Test
    public void bareCaptionReturnsIsResolvedToQualifiedMdxBeforeDrillthrough() {
        RecordingThinQueryService svc = new RecordingThinQueryService(cube);
        Query2Resource resource = resourceWithRecordingService(svc);

        resource.drillthrough(QUERY_NAME, 100, null, "Year,Store Sales", null);

        assertEquals("[Time].[Time By].[Year],[Measures].[Store Sales]", svc.capturedReturns);
    }

    @Test
    public void alreadyQualifiedMdxPassesThroughUnchanged() {
        RecordingThinQueryService svc = new RecordingThinQueryService(cube);
        Query2Resource resource = resourceWithRecordingService(svc);

        resource.drillthrough(QUERY_NAME, 100, null, "[Time].[Time By].[Year]", null);

        assertEquals("[Time].[Time By].[Year]", svc.capturedReturns);
    }

    @Test
    public void unknownTokenIsRejectedWithATypedBadRequestAndNeverReachesTheEngine() {
        RecordingThinQueryService svc = new RecordingThinQueryService(cube);
        Query2Resource resource = resourceWithRecordingService(svc);

        Response resp = resource.drillthrough(QUERY_NAME, 100, null, "Year,No Such Column", null);

        assertEquals(400, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());
        assertEquals("not-called", svc.capturedReturns);

        assertNotNull("failure must carry a body", resp.getEntity());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("VALIDATION_ERROR", body.get("status"));
        assertEquals("returns", body.get("field"));
        assertTrue(
                "message names the offending token: " + body.get("error"),
                String.valueOf(body.get("error")).contains("No Such Column"));
        @SuppressWarnings("unchecked")
        List<String> available = (List<String>) body.get("available");
        assertTrue("candidates include 'Store Sales'", available.contains("Store Sales"));
    }

    @Test
    public void exportCsvAlsoResolvesBareCaptionsBeforeDrillthrough() {
        RecordingThinQueryService svc = new RecordingThinQueryService(cube);
        Query2Resource resource = resourceWithRecordingService(svc);

        // The recording stub throws after capturing, so the export path's outer catch turns that
        // into a 500 — irrelevant here; what matters is what returns= value reached the engine.
        resource.getDrillthroughExport(QUERY_NAME, 100, null, "Year");

        assertEquals("[Time].[Time By].[Year]", svc.capturedReturns);
    }

    @Test
    public void exportCsvRejectsAnUnknownTokenBeforeTouchingTheEngine() {
        RecordingThinQueryService svc = new RecordingThinQueryService(cube);
        Query2Resource resource = resourceWithRecordingService(svc);

        Response resp = resource.getDrillthroughExport(QUERY_NAME, 100, null, "No Such Column");

        assertEquals(400, resp.getStatus());
        assertEquals("not-called", svc.capturedReturns);
    }

    @Test
    public void nullReturnsIsUntouchedAndNeverConsultsTheSchema() {
        RecordingThinQueryService svc = new RecordingThinQueryService(cube);
        Query2Resource resource = resourceWithRecordingService(svc);

        resource.drillthrough(QUERY_NAME, 100, null, null, null);

        assertEquals(null, svc.capturedReturns);
    }

    @Test
    public void noCubeMetadataServiceWiredLeavesReturnsUnresolved() {
        // A deployment that hasn't wired the AI metadata bean must keep working exactly as
        // before saiku#837 — returns= passes through untouched rather than 500ing.
        RecordingThinQueryService svc = new RecordingThinQueryService(cube);
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(svc);

        resource.drillthrough(QUERY_NAME, 100, null, "Year,Store Sales", null);

        assertEquals("Year,Store Sales", svc.capturedReturns);
    }
}
