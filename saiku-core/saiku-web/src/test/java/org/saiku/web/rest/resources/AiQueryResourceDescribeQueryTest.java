/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiPolicyViolation;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.describe.DescribeQueryApiRequest;
import org.saiku.service.olap.ai.describe.DescribeQueryApiResponse;
import org.saiku.service.olap.ai.describe.DescribeQueryResult;
import org.saiku.service.olap.ai.describe.QueryStructureSummarizer;
import org.saiku.service.olap.ai.describe.TileDescriber;

/**
 * saiku#909 — {@code POST /ai/describe-query} endpoint tests. Covers the five
 * behaviours called out on the issue: a valid query returns a non-empty
 * title/description, a PII-flagged dimension is stripped before the prompt is
 * built, schema-only policy allows the call, an unconfigured upstream 503s
 * with a clear message, and bad input 400s.
 *
 * <p>Audit logging (also called out on the issue) is structural — {@code
 * AiAuditFilter} audits every {@code /ai/*} call automatically (see {@code
 * AiAuditFilterTest}) — so it isn't re-tested per-endpoint here.
 */
public class AiQueryResourceDescribeQueryTest {

    private AiQueryResource resource;
    private AiSchema schema;

    /** Fake {@link TileDescriber} — no network, records the last prompt it was asked to describe. */
    private static final class FakeDescriber implements TileDescriber {
        private final boolean configured;
        private final DescribeQueryResult result;
        String lastPrompt;

        FakeDescriber(boolean configured, DescribeQueryResult result) {
            this.configured = configured;
            this.result = result;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public DescribeQueryResult describe(String querySummaryJson) {
            this.lastPrompt = querySummaryJson;
            return result;
        }
    }

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeH = new AiSchema.Hierarchy("Time", "[Time].[Time]");
        timeH.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time].[Year]"));
        time.hierarchies.put(AiSchema.key("Time"), timeH);
        schema.dimensions.put(AiSchema.key("Time"), time);

        AiSchema.Dimension customer = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy customerH = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        AiSchema.Level nameLevel = new AiSchema.Level("Name", "[Customer].[Customer].[Name]");
        nameLevel.pii = true;
        customerH.levels.put(AiSchema.key("Name"), nameLevel);
        customer.hierarchies.put(AiSchema.key("Customer"), customerH);
        schema.dimensions.put(AiSchema.key("Customer"), customer);

        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
    }

    private AiQueryRequest baseQuery() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time", "Year")));
        return req;
    }

    @Test
    public void validQuery_returnsNonEmptyTitleAndDescription() {
        FakeDescriber fake = new FakeDescriber(
                true, DescribeQueryResult.ok("Sales by year", "Store sales trended over time.", "claude-x", 10, 5));
        resource.setDescribeService(fake);

        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(baseQuery());
        Response resp = resource.describeQuery(body);

        assertEquals(200, resp.getStatus());
        DescribeQueryApiResponse out = (DescribeQueryApiResponse) resp.getEntity();
        assertFalse(out.getSuggestedTitle().isBlank());
        assertFalse(out.getSuggestedDescription().isBlank());
        assertEquals("Sales by year", out.getSuggestedTitle());
    }

    @Test
    public void piiFlaggedFilterIsStrippedFromThePromptButGenerationStillCompletes() {
        FakeDescriber fake =
                new FakeDescriber(true, DescribeQueryResult.ok("Customer breakdown", "By named customer.", "m", 1, 1));
        resource.setDescribeService(fake);

        AiQueryRequest req = baseQuery();
        req.setFilters(List.of(
                new AiFilterSelection("Customer", "Customer", "Name", List.of("[Customer].[Customer].[Jane Doe]"))));
        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(req);

        Response resp = resource.describeQuery(body);

        assertEquals(200, resp.getStatus());
        // The redacted sentinel reached the provider — the raw caption never did.
        assertTrue(fake.lastPrompt.contains(QueryStructureSummarizer.REDACTED));
        assertFalse(fake.lastPrompt.contains("Jane Doe"));
        DescribeQueryApiResponse out = (DescribeQueryApiResponse) resp.getEntity();
        assertFalse(out.getSuggestedTitle().isBlank());
    }

    @Test
    public void schemaOnlyPolicyAllowsTheCall() {
        resource.setAiPolicyGuard(new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));
        resource.setDescribeService(new FakeDescriber(true, DescribeQueryResult.ok("T", "D", "m", 1, 1)));

        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(baseQuery());
        try {
            Response resp = resource.describeQuery(body);
            assertEquals(200, resp.getStatus());
        } catch (AiPolicyViolation v) {
            fail("SCHEMA_ONLY must permit /ai/describe-query (SCHEMA_METADATA tier): " + v);
        }
    }

    @Test
    public void unconfiguredUpstreamReturns503WithClearMessage() {
        resource.setDescribeService(new FakeDescriber(false, null));

        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(baseQuery());
        Response resp = resource.describeQuery(body);

        assertEquals(503, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
        assertTrue(entity.get("error").toString().toLowerCase().contains("not configured"));
    }

    @Test
    public void nullDescribeServiceAlsoReturns503() {
        // Default (unwired) state — no setDescribeService call at all.
        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(baseQuery());
        Response resp = resource.describeQuery(body);
        assertEquals(503, resp.getStatus());
    }

    @Test
    public void missingQueryBodyReturns400() {
        resource.setDescribeService(new FakeDescriber(true, DescribeQueryResult.ok("T", "D", "m", 1, 1)));
        Response resp = resource.describeQuery(new DescribeQueryApiRequest());
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void unknownMeasureNameReturns400() {
        resource.setDescribeService(new FakeDescriber(true, DescribeQueryResult.ok("T", "D", "m", 1, 1)));
        AiQueryRequest req = baseQuery();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Nonexistent Measure")));
        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(req);

        Response resp = resource.describeQuery(body);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void degradedUpstreamReturns502() {
        resource.setDescribeService(new FakeDescriber(true, DescribeQueryResult.degraded("HTTP 500: boom")));
        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(baseQuery());

        Response resp = resource.describeQuery(body);
        assertEquals(502, resp.getStatus());
    }

    @Test
    public void queryIsNeverExecuted_noThinQueryServiceWiredYetCallStillSucceeds() {
        // No setThinQueryService() call at all — proves this endpoint never touches
        // query execution (schema-only: no cell values, no aggregated results).
        resource.setDescribeService(new FakeDescriber(true, DescribeQueryResult.ok("T", "D", "m", 1, 1)));
        DescribeQueryApiRequest body = new DescribeQueryApiRequest();
        body.setQuery(baseQuery());

        Response resp = resource.describeQuery(body);
        assertEquals(200, resp.getStatus());
    }

    @Test
    public void aiDataKindIsSchemaMetadata_theLowestTier() {
        // Documents the gate this endpoint uses — a regression here would silently
        // loosen or tighten the policy tier required to call it.
        assertEquals(AiPolicy.SCHEMA_ONLY, AiDataKind.SCHEMA_METADATA.minPolicy());
    }
}
