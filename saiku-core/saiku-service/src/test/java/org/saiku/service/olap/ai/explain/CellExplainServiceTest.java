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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.olap4j.CellSet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.olap4j.OlapStatement;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.OlapDiscoverService;
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

    /* ---- saiku#2443: the borrowed connection is not explain's to close ---- */

    /**
     * The SQL capture borrows the connection manager's cached connection, which every other query
     * on the datasource shares. It must close the statement and cellset it created and leave the
     * connection open, or other users' running queries fail with "Connection is closed".
     */
    @Test
    public void sqlCaptureClosesItsStatementAndCellsetButNotTheBorrowedConnection() {
        List<String> closed = new ArrayList<>();
        CellExplainService service = service(context(stubCellSet()));
        service.setOlapDiscoverService(discoverServiceLending(borrowedConnection(closed, false)));

        service.explain(request("q1", 0, 0));

        assertTrue("the statement explain created must be closed: " + closed, closed.contains("statement"));
        assertTrue("the cellset explain created must be closed: " + closed, closed.contains("cellset"));
        assertFalse("the borrowed, shared connection must stay open: " + closed, closed.contains("connection"));
    }

    /** Same contract when the cell query fails: the failure path must not close the connection either. */
    @Test
    public void aFailedSqlCaptureStillLeavesTheBorrowedConnectionOpen() {
        List<String> closed = new ArrayList<>();
        CellExplainService service = service(context(stubCellSet()));
        service.setOlapDiscoverService(discoverServiceLending(borrowedConnection(closed, true)));

        CellExplainResult result = service.explain(request("q1", 0, 0));

        assertNull(result.getSql());
        assertTrue(result.getNotes().toString(), result.getNotes().toString().contains("could not be re-run"));
        assertTrue("the statement explain created must be closed: " + closed, closed.contains("statement"));
        assertFalse("the borrowed, shared connection must stay open: " + closed, closed.contains("connection"));
    }

    /* ---- saiku#2328 / saiku#2423: another user's concurrent SQL is never the caller's ---- */

    /** A bare Mondrian statement: only its id matters to the Locus scope. */
    private static final class TestMondrianStatement extends mondrian.server.StatementImpl {
        @Override
        public void close() {}

        @Override
        public mondrian.rolap.RolapConnection getMondrianConnection() {
            return null;
        }
    }

    private static final String CALLERS_SQL = "select 'callers_cell' from SALES";
    private static final org.slf4j.Logger MONDRIAN_SQL = org.slf4j.LoggerFactory.getLogger("mondrian.sql");

    /** Run {@code body} on a new thread with {@code statement}'s Mondrian Locus pushed, and wait. */
    private static Thread underLocus(mondrian.server.StatementImpl statement, Runnable body) {
        return new Thread(() -> {
            mondrian.server.Locus locus =
                    new mondrian.server.Locus(new mondrian.server.Execution(statement, 0), "test", "explain");
            mondrian.server.Locus.push(locus);
            try {
                body.run();
            } finally {
                mondrian.server.Locus.pop(locus);
            }
        });
    }

    /**
     * While the caller's cell query runs, other users' queries log much longer SQL on Mondrian's
     * loggers from their own worker threads, and one thread logs with no Mondrian execution at
     * all. The caller's statement logs on a worker too (as Mondrian's shepherd and segment-load
     * pools do). Only the caller's statement may come back, and it is the longest one in the
     * window only if the strangers' were dropped, so a leak shows as the wrong text.
     */
    @Test
    public void explain_underConcurrentForeignQueries_returnsOnlyCallersSql() throws Exception {
        TestMondrianStatement callers = new TestMondrianStatement();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger foreignLogged = new java.util.concurrent.atomic.AtomicInteger();
        String foreignSql = "select 'another_users_filter_value', 'role_predicate' from SECRET_TABLE"
                + " where tenant in ('a','b','c') and region = 'Italy'";
        Runnable logForeign = () -> {
            while (!stop.get()) {
                MONDRIAN_SQL.debug("99: executing sql [" + foreignSql + "]");
                foreignLogged.incrementAndGet();
                Thread.onSpinWait();
            }
        };
        List<Thread> strangers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            strangers.add(underLocus(new TestMondrianStatement(), logForeign));
        }
        strangers.add(new Thread(logForeign));

        OlapStatement statement = (OlapStatement) Proxy.newProxyInstance(
                OlapStatement.class.getClassLoader(), new Class<?>[] {OlapStatement.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "unwrap":
                            return callers;
                        case "executeOlapQuery":
                            // The strangers must be logging INSIDE the window, not before it.
                            int seen = foreignLogged.get();
                            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                            while (foreignLogged.get() < seen + 200 && System.nanoTime() < deadline) {
                                Thread.onSpinWait();
                            }
                            assertTrue(
                                    "foreign queries never overlapped the window", foreignLogged.get() >= seen + 200);
                            Thread worker = underLocus(
                                    callers, () -> MONDRIAN_SQL.debug("1: executing sql [" + CALLERS_SQL + "]"));
                            worker.start();
                            worker.join();
                            return stubCellSet();
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        OlapConnection connection = (OlapConnection) Proxy.newProxyInstance(
                OlapConnection.class.getClassLoader(),
                new Class<?>[] {OlapConnection.class},
                (proxy, method, args) ->
                        "createStatement".equals(method.getName()) ? statement : defaultValue(method.getReturnType()));
        CellExplainService service = service(context(stubCellSet()));
        service.setOlapDiscoverService(discoverServiceLending(connection));

        strangers.forEach(Thread::start);
        try {
            CellExplainResult result = service.explain(request("q1", 0, 0));
            assertEquals(CALLERS_SQL, result.getSql());
        } finally {
            stop.set(true);
            for (Thread t : strangers) {
                t.join();
            }
        }
    }

    /* ---- saiku#2192: the explained cell names its own measure ---- */

    /**
     * With Unit Sales and Store Sales on the columns, the Store Sales cell must be explained
     * under Store Sales — the first column's measure is the wrong name for every other column.
     */
    @Test
    public void explainsTheCellUnderTheMeasureThatWasClicked() {
        CellsetView view = new FakeCellsetView(new double[] {24597d, 409035.59d})
                .column(0, CellMember.measure("[Measures].[Unit Sales]", "Unit Sales"))
                .column(1, SALES)
                .row(0, CellMember.of("[Product].[Food]", "Food"));

        CellExplainResult result = service(context(stubCellSet()), view).explain(request("q1", 0, 1));

        assertEquals("Store Sales", result.getMeasure());
        assertTrue(result.getNarrative(), result.getNarrative().startsWith("Store Sales for Food"));
        assertFalse(result.getNarrative(), result.getNarrative().contains("Unit Sales"));
        // a row holding cells of two measures has no row total to share and no row peak to name
        assertTrue(result.getDrivers().stream().noneMatch(d -> d.kind() == ExplainDriver.Kind.SHARE_OF_ROW));
        assertTrue(result.getDrivers().stream().noneMatch(d -> d.kind() == ExplainDriver.Kind.ROW_PEAK));
    }

    /** The first column of the same cellset names its own measure, Unit Sales. */
    @Test
    public void theFirstMeasureColumnIsNoLongerTheNameForEveryCell() {
        CellsetView view = new FakeCellsetView(new double[] {24597d, 409035.59d})
                .column(0, CellMember.measure("[Measures].[Unit Sales]", "Unit Sales"))
                .column(1, SALES)
                .row(0, CellMember.of("[Product].[Food]", "Food"));

        assertEquals(
                "Unit Sales",
                service(context(stubCellSet()), view)
                        .explain(request("q1", 0, 0))
                        .getMeasure());
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

    /** saiku#2329: the question itself carries the value and captions, so schema-only means no call. */
    @Test
    public void schemaOnlyEgressKeepsTheProviderUncalledAndTheComputedNarrative() {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        AiAskService ask = askService(request -> {
            seen.set(request);
            return NlAskResponse.okInsight("{\"markdown\":\"x\"}", "m", 3, 4);
        });
        ask.setEgressGuard(new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));

        CellExplainService service = service(context(stubCellSet()));
        service.setAskService(ask);

        CellExplainResult result = service.explain(request("q1", 0, 0));

        assertNull("no request may reach the vendor: " + seen.get(), seen.get());
        assertEquals(CellExplainResult.NarrativeSource.COMPUTED, result.getNarrativeSource());
        assertTrue(result.getNarrative(), result.getNarrative().contains("1,234.50"));
        assertTrue(
                String.join(" ", result.getNotes()),
                String.join(" ", result.getNotes()).contains("egress"));
    }

    @Test
    public void unwiredEgressGuardFailsClosed() {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        AiAskService ask = askService(request -> {
            seen.set(request);
            return NlAskResponse.okInsight("{\"markdown\":\"x\"}", "m", 3, 4);
        });
        ask.setEgressGuard(null);

        CellExplainService service = service(context(stubCellSet()));
        service.setAskService(ask);

        CellExplainResult result = service.explain(request("q1", 0, 0));

        assertNull(seen.get());
        assertEquals(CellExplainResult.NarrativeSource.COMPUTED, result.getNarrativeSource());
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
        return service(context, defaultView());
    }

    private static CellExplainService service(QueryContext context, CellsetView view) {
        CellExplainService service = new CellExplainService() {
            @Override
            protected CellsetView viewFor(CellSet cellSet) {
                return view;
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

    private static CellsetView defaultView() {
        return new FakeCellsetView(new double[] {1234.5d, 2000d})
                .column(0, Q1, SALES)
                .row(0, CellMember.of("[Customers].[USA]", "USA"));
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

    /** A discover service that hands out {@code connection}, as the connection manager's cache would. */
    private static OlapDiscoverService discoverServiceLending(OlapConnection connection) {
        return new OlapDiscoverService() {
            @Override
            public OlapConnection getNativeConnection(String name) {
                return connection;
            }
        };
    }

    /**
     * A connection whose statements run (or, with {@code failExecute}, fail) the cell query, and
     * which records into {@code closed} each of connection, statement and cellset that is closed.
     */
    private static OlapConnection borrowedConnection(List<String> closed, boolean failExecute) {
        CellSet cellSet = (CellSet) Proxy.newProxyInstance(
                CellSet.class.getClassLoader(), new Class<?>[] {CellSet.class}, (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closed.add("cellset");
                    }
                    return defaultValue(method.getReturnType());
                });
        OlapStatement statement = (OlapStatement) Proxy.newProxyInstance(
                OlapStatement.class.getClassLoader(), new Class<?>[] {OlapStatement.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close":
                            closed.add("statement");
                            return null;
                        case "executeOlapQuery":
                            if (failExecute) {
                                throw new OlapException("cell query failed");
                            }
                            return cellSet;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        return (OlapConnection) Proxy.newProxyInstance(
                OlapConnection.class.getClassLoader(), new Class<?>[] {OlapConnection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close":
                            closed.add("connection");
                            return null;
                        case "createStatement":
                            return statement;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
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
