/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.sql.ResultSet;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.service.olap.OlapQueryService;
import org.saiku.web.rest.objects.resultset.QueryResult;

/**
 * saiku#1165 regression: the legacy v1 {@link QueryResource} must NOT serialise raw
 * exception / root-cause text (Mondrian or SQL internals, driver messages, paths) back to the
 * client. On any failure it returns a generic {@code "Internal error"} in {@link
 * QueryResult#getError()}; the detail is logged server-side only.
 *
 * <p>This locks a fix that silently regressed once: six catch sites used to do {@code new
 * QueryResult(ExceptionUtils.getRootCauseMessage(e))}, and a squash-merge dropped the
 * remediation so the leak shipped to {@code development}. Without this test a future edit could
 * reopen it with the suite still green.
 *
 * <p>QA #1261 follow-up: the original test pinned only ONE of the six hardened catch sites
 * ({@code execute(queryName, limit)}). A partial revert on any other endpoint would have
 * reopened the leak with the suite still green, so this now exercises ALL six reachable
 * {@code new QueryResult(GENERIC_ERROR)} sites — {@code execute}×2, {@code executeMdx}×2,
 * {@code getExplainPlan}, {@code drillthrough} — each via the first {@link OlapQueryService}
 * call inside its {@code try} block.
 */
public class QueryResourceErrorLeakTest {

    /** A realistic sensitive root-cause: a JDBC URL with embedded credentials. */
    private static final String SECRET =
            "jdbc:postgresql://10.0.0.5:5432/warehouse?user=svc&password=hunter2 [SQLState=28000]";

    private static final String MDX = "SELECT {[Measures].[X]} ON COLUMNS FROM [Sales]";

    /**
     * A {@link QueryResource} whose {@link OlapQueryService} throws {@code boom} from every entry
     * point reached first inside the six hardened catch sites — so each endpoint must convert the
     * failure into the generic error rather than echoing the cause.
     */
    private static QueryResource resourceFailingWith(final RuntimeException boom) {
        QueryResource r = new QueryResource();
        r.setOlapQueryService(new OlapQueryService() {
            @Override
            public CellDataSet execute(String queryName) {
                throw boom;
            }

            @Override
            public CellDataSet execute(String queryName, String formatter) {
                throw boom;
            }

            @Override
            public void qm2mdx(String queryName) {
                throw boom;
            }

            @Override
            public ResultSet explain(String queryName) {
                throw boom;
            }

            @Override
            public ResultSet drillthrough(String queryName, int maxrows, String returns) {
                throw boom;
            }
        });
        return r;
    }

    /** Every hardened path must return the generic message and never echo the root cause. */
    private static void assertGenericNoLeak(QueryResult res) {
        assertNotNull(res);
        assertEquals("Internal error", res.getError());
        assertFalse(
                "raw exception detail must never reach the client: " + res.getError(),
                res.getError().contains("password=hunter2"));
    }

    /** Site 1: GET /{queryname}/result — execute(queryName, limit) → olapQueryService.execute(name). */
    @Test
    public void execute_returnsGenericError_andDoesNotLeakRootCause() {
        assertGenericNoLeak(resourceFailingWith(new RuntimeException(SECRET)).execute("q", 0));
    }

    /** Site 6: GET /{queryname}/result/{format} — execute(name, format, limit) → execute(name, format). */
    @Test
    public void executeWithFormat_returnsGenericError_andDoesNotLeak() {
        assertGenericNoLeak(resourceFailingWith(new RuntimeException(SECRET)).execute("q", "flat", 0));
    }

    /** Site 2: POST /{queryname}/result/{format} — executeMdx(4-arg) → olapQueryService.qm2mdx(name). */
    @Test
    public void executeMdxWithFormat_returnsGenericError_andDoesNotLeak() {
        assertGenericNoLeak(resourceFailingWith(new RuntimeException(SECRET)).executeMdx("q", "flat", MDX, 0));
    }

    /** Site 3: POST /{queryname}/result — executeMdx(3-arg) delegates to the 4-arg handler. */
    @Test
    public void executeMdx_returnsGenericError_andDoesNotLeak() {
        assertGenericNoLeak(resourceFailingWith(new RuntimeException(SECRET)).executeMdx("q", MDX, 0));
    }

    /** Site 4: GET /{queryname}/explain — getExplainPlan(name) → olapQueryService.explain(name). */
    @Test
    public void getExplainPlan_returnsGenericError_andDoesNotLeak() {
        assertGenericNoLeak(resourceFailingWith(new RuntimeException(SECRET)).getExplainPlan("q"));
    }

    /** Site 5: GET /{queryname}/drillthrough — drillthrough(...) → olapQueryService.drillthrough(name, maxrows, returns). */
    @Test
    public void drillthrough_returnsGenericError_andDoesNotLeak() {
        assertGenericNoLeak(resourceFailingWith(new RuntimeException(SECRET)).drillthrough("q", 100, null, null));
    }
}
