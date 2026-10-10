/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.olap4j.CellSet;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.olap.ai.ask.NlAskResponse;
import org.saiku.service.util.QueryContext;

/**
 * {@link CellExplainService} end to end, minus the live cube: the query context, the ThinQuery and
 * the cellset are stubs, everything from validation to the narrative is the real code.
 */
public class CellExplainServiceTest {

    private static final CellMember SALES = CellMember.measure("[Measures].[Store Sales]", "Store Sales");
    private static final CellMember Q1 = CellMember.of("[Time].[1997].[Q1]", "Q1");
    private static final String PARENT_MDX =
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON 0 FROM [Sales] WHERE ([Time].[1997])";

    /* ---- request validation ---- */

    @Test
    public void rejectsAMissingBody() {
        expect((CellExplainRequest) null, CellExplainException.Code.VALIDATION_ERROR);
    }

    @Test
    public void rejectsAMissingQueryName() {
        CellExplainRequest request = new CellExplainRequest();
        request.setPosition(new CellExplainRequest.CellCoordinate(0, 0));
        expect(request, CellExplainException.Code.VALIDATION_ERROR);
    }

    @Test
    public void rejectsAMissingPosition() {
        CellExplainRequest request = new CellExplainRequest();
        request.setQueryName("q1");
        expect(request, CellExplainException.Code.VALIDATION_ERROR);
    }

    @Test
    public void rejectsANegativePosition() {
        CellExplainRequest request = request("q1", -1, 0);
        expect(request, CellExplainException.Code.VALIDATION_ERROR);
    }

    @Test
    public void rejectsAQueryThisSessionDoesNotHave() {
        expect(request("nope", 0, 0), CellExplainException.Code.UNKNOWN_QUERY);
    }

    @Test
    public void rejectsAQueryThatHasNotBeenExecuted() {
        CellExplainService service = service(context(null));
        expect(service, request("q1", 0, 0), CellExplainException.Code.NOT_EXECUTED);
    }

    @Test
    public void rejectsACoordinateOutsideTheCellset() {
        CellExplainService service = service(context(stubCellSet()));
        expect(service, request("q1", 9, 0), CellExplainException.Code.VALIDATION_ERROR);
    }

    /* ---- the explained cell ---- */

    @Test
    public void explainsTheCellFromTheCachedCellset() {
        CellExplainService service = service(context(stubCellSet()));

        CellExplainResult result = service.explain(request("q1", 0, 0));

        assertEquals("Store Sales", result.getMeasure());
        assertEquals("USA", result.getRowPath());
        assertEquals("Q1", result.getColumnPath());
        assertEquals(1234.5d, result.getValue(), 1e-9);
        assertEquals("1,234.50", result.getFormatted());
        assertEquals(PARENT_MDX, result.getMdx());
        assertEquals(
                "SELECT {[Measures].[Store Sales]} ON 0 FROM [Sales] WHERE ([Customers].[USA], [Time].[1997].[Q1])",
                result.getCellMdx());
        assertEquals(CellExplainResult.NarrativeSource.COMPUTED, result.getNarrativeSource());
        assertTrue(result.getNarrative(), result.getNarrative().contains("1,234.50"));
    }

    @Test
    public void driversAreOnByDefaultAndSkippedOnRequest() {
        assertFalse(service(context(stubCellSet()))
                .explain(request("q1", 0, 0))
                .getDrivers()
                .isEmpty());

        CellExplainRequest without = request("q1", 0, 0);
        without.setIncludeDrivers(false);
        assertTrue(service(context(stubCellSet())).explain(without).getDrivers().isEmpty());
    }

    @Test
    public void narrativeIsSkippedOnRequest() {
        CellExplainRequest without = request("q1", 0, 0);
        without.setIncludeNarrative(false);

        CellExplainResult result = service(context(stubCellSet())).explain(without);

        assertNull(result.getNarrative());
        assertEquals(CellExplainResult.NarrativeSource.NONE, result.getNarrativeSource());
    }

    /**
     * No discover service is wired in this test, so the SQL capture must degrade to a note rather
     * than fail the explain — the panel shows MDX either way, and a missing SQL must be visible as
     * missing rather than invented.
     */
    @Test
    public void sqlIsAbsentWithANoteWhenTheBackendCannotBeAsked() {
        CellExplainResult result = service(context(stubCellSet())).explain(request("q1", 0, 0));

        assertNull(result.getSql());
        assertEquals(1, result.getNotes().size());
        assertTrue(result.getNotes().get(0), result.getNotes().get(0).contains("SQL"));
    }

    @Test
    public void sqlCaptureIsSkippedEntirelyWhenTheCallerDoesNotWantIt() {
        CellExplainRequest without = request("q1", 0, 0);
        without.setIncludeSql(false);

        CellExplainResult result = service(context(stubCellSet())).explain(without);

        assertNull(result.getSql());
        assertTrue(result.getNotes().isEmpty());
    }

    /* ---- Phase 3: the LLM narrative and its fallback ---- */

    @Test
    public void usesTheProviderNarrativeWhenOneIsConfigured() {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        AiAskService ask = askService(request -> {
            seen.set(request);
            return NlAskResponse.okInsight(
                    "{\"markdown\":\"Altadena slipped because ACME stopped ordering.\"}", "m", 3, 4);
        });

        CellExplainService service = service(context(stubCellSet()));
        service.setAskService(ask);

        CellExplainResult result = service.explain(request("q1", 0, 0));

        assertEquals(CellExplainResult.NarrativeSource.LLM, result.getNarrativeSource());
        assertEquals("Altadena slipped because ACME stopped ordering.", result.getNarrative());
        assertEquals("m", result.getModel());
        // The provider is given the computed findings, not the raw cellset, and is pinned to the
        // insight tool so it cannot wander off building a query instead.
        assertEquals(NlAskRequest.ForceTool.INSIGHT, seen.get().forceTool());
        assertTrue(seen.get().cellsetDigest(), seen.get().cellsetDigest().contains("value: 1,234.50"));
    }

    @Test
    public void fallsBackToTheComputedNarrativeWhenTheProviderDegrades() {
        AiAskService ask = askService(request -> NlAskResponse.degraded("HTTP 503: upstream unavailable"));

        CellExplainService service = service(context(stubCellSet()));
        service.setAskService(ask);

        CellExplainResult result = service.explain(request("q1", 0, 0));

        assertEquals(CellExplainResult.NarrativeSource.COMPUTED, result.getNarrativeSource());
        assertTrue(result.getNarrative(), result.getNarrative().contains("1,234.50"));
        assertTrue(
                String.join(" ", result.getNotes()),
                String.join(" ", result.getNotes()).contains("503"));
    }

    @Test
    public void fallsBackToTheComputedNarrativeWhenTheProviderThrows() {
        AiAskService ask = askService(request -> {
            throw new IllegalStateException("boom");
        });

        CellExplainService service = service(context(stubCellSet()));
        service.setAskService(ask);

        CellExplainResult result = service.explain(request("q1", 0, 0));

        assertEquals(CellExplainResult.NarrativeSource.COMPUTED, result.getNarrativeSource());
        assertNotNull(result.getNarrative());
    }

    /* ---- helpers ---- */

    private void expect(CellExplainRequest request, CellExplainException.Code code) {
        expect(service(context(stubCellSet())), request, code);
    }

    private void expect(CellExplainService service, CellExplainRequest request, CellExplainException.Code code) {
        try {
            service.explain(request);
            fail("expected " + code);
        } catch (CellExplainException e) {
            assertEquals(code, e.code());
        }
    }

    private static CellExplainRequest request(String queryName, int row, int column) {
        CellExplainRequest request = new CellExplainRequest();
        request.setQueryName(queryName);
        request.setPosition(new CellExplainRequest.CellCoordinate(row, column));
        return request;
    }

    /** The service under test with a fake view and a thin-query stub that serves {@code context}. */
    private static CellExplainService service(QueryContext context) {
        CellExplainService service = new CellExplainService() {
            @Override
            protected CellsetView viewFor(CellSet cellSet) {
                return new FakeCellsetView(new double[] {1234.5d, 2000d})
                        .column(0, Q1, SALES)
                        .row(0, CellMember.of("[Customers].[USA]", "USA"));
            }
        };
        ThinQueryService queries = new ThinQueryService() {
            @Override
            public QueryContext getContext(String name) {
                return "q1".equals(name) ? context : null;
            }
        };
        service.setThinQueryService(queries);
        return service;
    }

    private static QueryContext context(CellSet result) {
        ThinQuery query = new ThinQuery();
        query.setName("q1");
        query.setMdx(PARENT_MDX);
        query.setCube(
                new SaikuCube("FoodMart", "[FoodMart].[FoodMart].[Sales]", "Sales", "Sales", "FoodMart", "FoodMart"));
        QueryContext context = new QueryContext(QueryContext.Type.OLAP, query);
        if (result != null) {
            context.store(QueryContext.ObjectKey.RESULT, result);
        }
        return context;
    }

    /** A CellSet stand-in: the service only hands it to the (overridden) view factory. */
    private static CellSet stubCellSet() {
        return (CellSet) Proxy.newProxyInstance(
                CellSet.class.getClassLoader(), new Class<?>[] {CellSet.class}, (proxy, method, args) -> null);
    }

    private static AiAskService askService(NlAskProvider provider) {
        AiCubeMetadataService schemas = ref -> new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
        AiAskService ask = new AiAskService(schemas, provider);
        // The egress guard only governs what may leave the box; AGGREGATED keeps the cell figures
        // in the prompt so the test can assert the digest reached the provider.
        ask.setEgressGuard(new AiPolicyGuard(AiPolicy.AGGREGATED));
        return ask;
    }
}
