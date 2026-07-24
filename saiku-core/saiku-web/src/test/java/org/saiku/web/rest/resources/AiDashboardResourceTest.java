/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.DashboardSpec;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskResponse;
import org.saiku.web.security.ratelimit.AiRateLimiter;

/**
 * Resource-level tests for {@code POST /saiku/api/ai/ask/dashboard} (D1 dashboard-builder).
 *
 * <p>Auth is enforced by Spring Security at the servlet layer ({@code /rest/**} requires
 * {@code isFullyAuthenticated()}), not inside the resource — so an anonymous 401 can't be exercised
 * as a resource unit test, exactly like every other AI endpoint. What IS resource-level is the
 * shared AI rate limit ({@link AiRateLimiter}): a dashboard build counts as one AI unit and 429s
 * over budget, mirroring {@link AiAskRateLimitAndSizeTest}.
 */
public class AiDashboardResourceTest {

    private static final String PAYLOAD = "{\"title\":\"Sales\",\"tiles\":["
            + "{\"title\":\"By Year\",\"type\":\"table\",\"query\":{\"measures\":[{\"name\":\"Store Sales\"}]}}]}";

    private AiQueryResource resource;

    @Before
    public void setUp() {
        AiSchema schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        resource = new AiQueryResource();
        // AiAskService uses its own metadata service (schema-only path — no execution needed for a
        // dashboard build, since tiles are only validated for convertibility, never run).
        NlAskProvider provider = req -> NlAskResponse.okDashboard(PAYLOAD, "claude-x", 0, 0);
        resource.setAskService(new AiAskService(ref -> schema, provider));
    }

    private AiAskApi.AskRequest validBody() {
        AiAskApi.AskRequest b = new AiAskApi.AskRequest();
        b.setQuestion("build a sales dashboard");
        b.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        return b;
    }

    @Test
    public void happyPathReturns200WithDashboardSpec() {
        Response resp = resource.askDashboard(validBody());
        assertEquals(200, resp.getStatus());
        DashboardSpec spec = (DashboardSpec) resp.getEntity();
        assertFalse(spec.degraded());
        assertEquals(1, spec.tiles().size());
        assertEquals("Sales", spec.tiles().get(0).query().getCube().getCubeName());
    }

    @Test
    public void rateLimitReturns429AfterBudget() {
        resource.setAskRateLimiter(new AiRateLimiter(1, 60_000L));
        assertEquals(200, resource.askDashboard(validBody()).getStatus());
        Response second = resource.askDashboard(validBody());
        assertEquals(429, second.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) second.getEntity();
        assertTrue(body.isDegraded());
        assertTrue(body.getReason().contains("Too many AI ask requests"));
    }

    @Test
    public void missingCubeReturns400() {
        AiAskApi.AskRequest b = new AiAskApi.AskRequest();
        b.setQuestion("no cube");
        Response resp = resource.askDashboard(b);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void notConfiguredReturns503() {
        AiQueryResource bare = new AiQueryResource(); // no ask service wired
        Response resp = bare.askDashboard(validBody());
        assertEquals(503, resp.getStatus());
    }
}
