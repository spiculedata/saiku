/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
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
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskMessage;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskResponse;
import org.saiku.web.security.ratelimit.AiAskGuard;
import org.saiku.web.security.ratelimit.AiRateLimiter;

/**
 * saiku#1151 — resource-level tests that {@code POST /saiku/api/ai/ask} caps
 * request size (413 / 400) and call rate (429) before reaching the paid LLM
 * provider.
 */
public class AiAskRateLimitAndSizeTest {

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
        // Wire a fake ask service so the happy path reaches 200 (the guard runs
        // before this is consulted).
        resource.setAskService(new AiAskService(ref -> schema, stubProvider()) {
            @Override
            public AskOutcome ask(AiCubeRef ref, String question, List<NlAskMessage> history) {
                return AskOutcome.ok(baseEmittedRequest(), "claude-x");
            }
        });
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

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    public void happyPathWithinLimitsReturns200() {
        Response resp = resource.ask(validBody());
        assertEquals(200, resp.getStatus());
    }

    @Test
    public void oversizeQuestionReturns413() {
        AiAskApi.AskRequest b = validBody();
        b.setQuestion(repeat('x', AiAskGuard.MAX_QUESTION_CHARS + 1));
        Response resp = resource.ask(b);
        assertEquals(413, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertTrue(body.isDegraded());
        assertTrue(body.getReason().contains("question exceeds"));
    }

    @Test
    public void tooManyHistoryMessagesReturns400() {
        AiAskApi.AskRequest b = validBody();
        List<AiAskApi.NlAskMessageDto> history = new ArrayList<>();
        for (int i = 0; i < AiAskGuard.MAX_HISTORY_MESSAGES + 1; i++) {
            history.add(new AiAskApi.NlAskMessageDto("user", "hi"));
        }
        b.setHistory(history);
        Response resp = resource.ask(b);
        assertEquals(400, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertTrue(body.getReason().contains("history exceeds"));
    }

    @Test
    public void oversizeHistoryMessageReturns413() {
        AiAskApi.AskRequest b = validBody();
        List<AiAskApi.NlAskMessageDto> history = new ArrayList<>();
        history.add(new AiAskApi.NlAskMessageDto("user", repeat('y', AiAskGuard.MAX_MESSAGE_CHARS + 1)));
        b.setHistory(history);
        Response resp = resource.ask(b);
        assertEquals(413, resp.getStatus());
    }

    @Test
    public void combinedHistoryOverTotalReturns413() {
        AiAskApi.AskRequest b = validBody();
        // Each message under the per-message cap, count under the message cap,
        // but the sum blows past the combined-total cap.
        int per = AiAskGuard.MAX_MESSAGE_CHARS - 1;
        int count = (AiAskGuard.MAX_TOTAL_CHARS / per) + 2;
        // Keep the message count itself legal so the total-cap is what trips.
        assertTrue("test setup keeps history count legal", count <= AiAskGuard.MAX_HISTORY_MESSAGES);
        List<AiAskApi.NlAskMessageDto> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(new AiAskApi.NlAskMessageDto("user", repeat('z', per)));
        }
        b.setHistory(history);
        Response resp = resource.ask(b);
        assertEquals(413, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertTrue(body.getReason().contains("combined"));
    }

    @Test
    public void rateLimitReturns429AfterBudget() {
        resource.setAskRateLimiter(new AiRateLimiter(2, 60_000L));
        // No request is bound to the thread in this unit test, so all calls
        // share the "anon:unknown" bucket.
        assertEquals(200, resource.ask(validBody()).getStatus());
        assertEquals(200, resource.ask(validBody()).getStatus());
        Response third = resource.ask(validBody());
        assertEquals(429, third.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) third.getEntity();
        assertTrue(body.isDegraded());
        assertTrue(body.getReason().contains("Too many AI ask requests"));
    }

    // ---------- stubs ----------

    private static NlAskProvider stubProvider() {
        return req -> NlAskResponse.ok("", "claude-x", 0, 0);
    }

    private static class StubThinQuerySvc extends ThinQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            return AiQueryResourceTest.buildStubCellDataSet();
        }
    }
}
