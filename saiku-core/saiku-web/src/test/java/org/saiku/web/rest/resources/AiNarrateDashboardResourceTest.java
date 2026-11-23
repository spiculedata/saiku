/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.AiDashboardNarrativeApi;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskResponse;
import org.saiku.web.security.ratelimit.AiRateLimiter;

/**
 * Resource-level tests for {@code POST /saiku/api/ai/narrate-dashboard} (saiku#910, Tier-2
 * aggregated). Policy-gate wiring coverage lives in {@link AiQueryResourcePolicyTest}; this class
 * covers the endpoint's own behaviour — empty-dashboard short-circuit, per-tile execution, rate
 * limiting and the not-configured path.
 */
public class AiNarrateDashboardResourceTest {

    private static final String INSIGHT_JSON = "{\"markdown\":\"Sales are up 15% year over year.\"}";

    private AiQueryResource resource;

    @Before
    public void setUp() {
        AiSchema schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
        resource.setThinQueryService(new StubThinQueryService());
        NlAskProvider provider = req -> NlAskResponse.okInsight(INSIGHT_JSON, "claude-x", 10, 5);
        resource.setAskService(new AiAskService(ref -> schema, provider));
    }

    private static AiQueryRequest tileQuery() {
        AiQueryRequest q = new AiQueryRequest();
        q.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        q.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        return q;
    }

    private static AiDashboardNarrativeApi.Request bodyWithOneTile() {
        AiDashboardNarrativeApi.Request body = new AiDashboardNarrativeApi.Request();
        body.setDashboardTitle("Sales Overview");
        body.setTiles(List.of(new AiDashboardNarrativeApi.Tile("Sales by Year", tileQuery())));
        return body;
    }

    @Test
    public void happyPathReturnsNarrative() {
        Response resp = resource.narrateDashboard(bodyWithOneTile());
        assertEquals(200, resp.getStatus());
        AiDashboardNarrativeApi.Response out = (AiDashboardNarrativeApi.Response) resp.getEntity();
        assertFalse(out.isDegraded());
        assertEquals("Sales are up 15% year over year.", out.getNarrative());
        assertEquals("claude-x", out.getModel());
    }

    @Test
    public void noTilesReturnsNoDataMessageWithoutCallingProvider() {
        AiDashboardNarrativeApi.Request body = new AiDashboardNarrativeApi.Request();
        body.setTiles(List.of());
        Response resp = resource.narrateDashboard(body);
        assertEquals(200, resp.getStatus());
        AiDashboardNarrativeApi.Response out = (AiDashboardNarrativeApi.Response) resp.getEntity();
        assertFalse(out.isDegraded());
        assertEquals("No data to summarise.", out.getNarrative());
    }

    @Test
    public void allTilesEmptyReturnsNoDataMessage() {
        resource.setThinQueryService(new EmptyThinQueryService());
        Response resp = resource.narrateDashboard(bodyWithOneTile());
        assertEquals(200, resp.getStatus());
        AiDashboardNarrativeApi.Response out = (AiDashboardNarrativeApi.Response) resp.getEntity();
        assertEquals("No data to summarise.", out.getNarrative());
    }

    @Test
    public void malformedTileIsSkippedNotFatal() {
        AiDashboardNarrativeApi.Request body = new AiDashboardNarrativeApi.Request();
        body.setTiles(List.of(new AiDashboardNarrativeApi.Tile("Broken tile", null)));
        Response resp = resource.narrateDashboard(body);
        assertEquals(200, resp.getStatus());
        AiDashboardNarrativeApi.Response out = (AiDashboardNarrativeApi.Response) resp.getEntity();
        assertEquals("No data to summarise.", out.getNarrative());
    }

    @Test
    public void rateLimitReturns429AfterBudget() {
        resource.setAskRateLimiter(new AiRateLimiter(1, 60_000L));
        assertEquals(200, resource.narrateDashboard(bodyWithOneTile()).getStatus());
        Response second = resource.narrateDashboard(bodyWithOneTile());
        assertEquals(429, second.getStatus());
        AiDashboardNarrativeApi.Response out = (AiDashboardNarrativeApi.Response) second.getEntity();
        assertTrue(out.isDegraded());
        assertTrue(out.getReason().contains("Too many AI narrative requests"));
    }

    @Test
    public void notConfiguredReturns503() {
        AiQueryResource bare = new AiQueryResource(); // no ask service wired
        Response resp = bare.narrateDashboard(bodyWithOneTile());
        assertEquals(503, resp.getStatus());
    }

    @Test
    public void piiCaptionsAreRedactedBeforeReachingProvider() {
        // saiku#902/#910: a PII-tagged level is still a queryable row axis — the endpoint must
        // redact its member captions from the digest the LLM actually receives, while keeping the
        // measure value untouched.
        AiSchema piiSchema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        piiSchema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
        AiSchema.Dimension customer = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy customerH = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        AiSchema.Level customerLevel = new AiSchema.Level("Customer", "[Customer].[Customer].[Customer]");
        customerLevel.pii = true;
        customerH.levels.put(AiSchema.key("Customer"), customerLevel);
        customer.hierarchies.put(AiSchema.key("Customer"), customerH);
        piiSchema.dimensions.put(AiSchema.key("Customer"), customer);

        resource.setCubeMetadataService(ref -> piiSchema);
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                CellDataSet cds = new CellDataSet(2, 1);
                AbstractBaseCell hdrA = new MemberCell(false, false);
                hdrA.setFormattedValue("Customer");
                AbstractBaseCell hdrB = new MemberCell(false, false);
                hdrB.setFormattedValue("Store Sales");
                cds.setCellSetHeaders(new AbstractBaseCell[][] {new AbstractBaseCell[] {hdrA, hdrB}});

                MemberCell rowHeader = new MemberCell(false, false);
                rowHeader.setFormattedValue("John Smith");
                rowHeader.setLevel("[Customer].[Customer].[Customer]");
                AbstractBaseCell dataCell = new DataCell(true, false, null);
                dataCell.setFormattedValue("500");
                dataCell.setRawValue("500");
                cds.setCellSetBody(new AbstractBaseCell[][] {new AbstractBaseCell[] {rowHeader, dataCell}});
                return cds;
            }
        });

        java.util.concurrent.atomic.AtomicReference<String> capturedDigest =
                new java.util.concurrent.atomic.AtomicReference<>();
        NlAskProvider capturing = req -> {
            capturedDigest.set(req.cellsetDigest());
            return NlAskResponse.okInsight(INSIGHT_JSON, "claude-x", 10, 5);
        };
        AiAskService askService = new AiAskService(ref -> piiSchema, capturing);
        // Permissive egress guard — this test asserts on the digest CONTENT, so the digest must
        // actually reach the provider rather than being stripped by the (separate) LLM-egress gate.
        askService.setEgressGuard(new org.saiku.service.olap.ai.AiPolicyGuard(org.saiku.service.olap.ai.AiPolicy.FULL));
        resource.setAskService(askService);

        Response resp = resource.narrateDashboard(bodyWithOneTile());
        assertEquals(200, resp.getStatus());
        String digest = capturedDigest.get();
        assertTrue("digest should redact the PII caption: " + digest, digest.contains("[REDACTED]"));
        assertFalse("digest must not leak the PII caption: " + digest, digest.contains("John Smith"));
        assertTrue("digest should keep the measure value: " + digest, digest.contains("500"));
    }

    /* ----------------------------- fixtures ---------------------------- */

    private static class StubThinQueryService extends ThinQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            CellDataSet cds = new CellDataSet(2, 1);
            AbstractBaseCell hdrA = new MemberCell(false, false);
            hdrA.setFormattedValue("Year");
            AbstractBaseCell hdrB = new MemberCell(false, false);
            hdrB.setFormattedValue("Store Sales");
            cds.setCellSetHeaders(new AbstractBaseCell[][] {new AbstractBaseCell[] {hdrA, hdrB}});

            AbstractBaseCell rowHeader = new MemberCell(false, false);
            rowHeader.setFormattedValue("1997");
            AbstractBaseCell dataCell = new DataCell(true, false, null);
            dataCell.setFormattedValue("100");
            dataCell.setRawValue("100");

            cds.setCellSetBody(new AbstractBaseCell[][] {new AbstractBaseCell[] {rowHeader, dataCell}});
            return cds;
        }
    }

    private static class EmptyThinQueryService extends ThinQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            CellDataSet cds = new CellDataSet(0, 0);
            cds.setCellSetHeaders(new AbstractBaseCell[0][]);
            cds.setCellSetBody(new AbstractBaseCell[0][]);
            return cds;
        }
    }
}
