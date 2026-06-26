/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskMessage;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.olap.ai.ask.NlAskResponse;

/** Resource-level tests for {@code POST /saiku/api/ai/ask}. No network. */
public class AiQueryResourceAskTest {

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
        resource.setThinQueryService(new StubThinQuerySvc());
    }

    private AiAskApi.AskRequest validBody() {
        AiAskApi.AskRequest b = new AiAskApi.AskRequest();
        b.setQuestion("show sales by year");
        b.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        return b;
    }

    private AiQueryRequest baseEmittedRequest() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        return req;
    }

    /** Wire a fake askService that returns a fixed outcome. */
    private void wireAskService(AiAskService.AskOutcome outcome) {
        resource.setAskService(new AiAskService(ref -> schema, stubProvider("")) {
            // The resource calls the six-arg overload (cellset digest + tool-choice + current
            // query); the narrower overloads all delegate to it, so overriding this one catches
            // every code path the resource can take.
            @Override
            public AskOutcome ask(
                    AiCubeRef ref,
                    String question,
                    List<NlAskMessage> history,
                    String cellsetDigest,
                    NlAskRequest.ForceTool forceTool,
                    AiQueryRequest currentQuery) {
                return outcome;
            }
        });
    }

    @Test
    public void returns503WhenAskServiceUnwired() {
        // Default state — no askService set.
        Response resp = resource.ask(validBody());
        assertEquals(503, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertTrue(body.isDegraded());
        assertTrue(body.getReason().contains("not configured"));
    }

    @Test
    public void returns400WhenBodyNull() {
        wireAskService(AiAskService.AskOutcome.ok(baseEmittedRequest(), "claude-x"));
        Response resp = resource.ask(null);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void returns400WhenQuestionBlank() {
        wireAskService(AiAskService.AskOutcome.ok(baseEmittedRequest(), "claude-x"));
        AiAskApi.AskRequest b = validBody();
        b.setQuestion("   ");
        Response resp = resource.ask(b);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void returns400WhenCubeMissing() {
        wireAskService(AiAskService.AskOutcome.ok(baseEmittedRequest(), "claude-x"));
        AiAskApi.AskRequest b = validBody();
        b.setCube(null);
        Response resp = resource.ask(b);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void returns200WithExecutedResponseOnHappyPath() {
        wireAskService(AiAskService.AskOutcome.ok(baseEmittedRequest(), "claude-x"));

        Response resp = resource.ask(validBody());
        assertEquals(200, resp.getStatus());

        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertFalse(body.isDegraded());
        assertEquals("claude-x", body.getModel());
        assertNotNull("emitted request echoed", body.getRequest());
        assertEquals("Store Sales", body.getRequest().getMeasures().get(0).getName());
        assertNotNull("executed response present", body.getResponse());
        assertEquals(AiQueryResponse.Status.SUCCESS, body.getResponse().getStatus());
        assertNotNull(body.getGeneratedMdx());
        assertTrue(body.getGeneratedMdx().contains("FROM [Sales]"));
    }

    @Test
    public void returns503WhenServiceReportsNotConfigured() {
        wireAskService(
                AiAskService.AskOutcome.degraded("AI ask is not configured. Set saiku.ai.ask.provider...", null));

        Response resp = resource.ask(validBody());
        assertEquals(503, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertTrue(body.isDegraded());
        assertNull(body.getRequest());
        assertNull(body.getResponse());
    }

    @Test
    public void returns200WithDegradedBodyOnProviderFailure() {
        wireAskService(AiAskService.AskOutcome.degraded("HTTP 503: upstream offline", "claude-x"));

        Response resp = resource.ask(validBody());
        assertEquals(200, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertTrue(body.isDegraded());
        assertEquals("HTTP 503: upstream offline", body.getReason());
        assertEquals("claude-x", body.getModel());
    }

    @Test
    public void returns200WithValidationErrorWhenModelEmittedBadRequest() {
        // Model emits a request that references a measure not in the schema.
        AiQueryRequest bad = baseEmittedRequest();
        bad.setMeasures(Collections.singletonList(new AiMeasureSelection("BogusMeasure")));
        wireAskService(AiAskService.AskOutcome.ok(bad, "claude-x"));

        Response resp = resource.ask(validBody());
        assertEquals(200, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertFalse("translation succeeded; execution layered the error", body.isDegraded());
        assertNotNull(body.getResponse());
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getResponse().getStatus());
        assertTrue(
                "field points at the offending measure path",
                body.getResponse().getField().startsWith("measures"));
    }

    // ---------- stubs ----------

    private static NlAskProvider stubProvider(String emittedJson) {
        return req -> NlAskResponse.ok(emittedJson, "claude-x", 0, 0);
    }

    /** Stub query service that returns a small synthetic CellDataSet via the AiSchemaConverter path. */
    private static class StubThinQuerySvc extends ThinQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            return AiQueryResourceTest.buildStubCellDataSet();
        }
    }
}
