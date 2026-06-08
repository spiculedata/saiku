/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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
 */
public class QueryResourceErrorLeakTest {

    /** A realistic sensitive root-cause: a JDBC URL with embedded credentials. */
    private static final String SECRET =
            "jdbc:postgresql://10.0.0.5:5432/warehouse?user=svc&password=hunter2 [SQLState=28000]";

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
        });
        return r;
    }

    @Test
    public void execute_returnsGenericError_andDoesNotLeakRootCause() {
        QueryResult res = resourceFailingWith(new RuntimeException(SECRET)).execute("q", 0);

        assertNotNull(res);
        assertEquals("Internal error", res.getError());
        assertFalse(
                "raw exception detail must never reach the client",
                res.getError().contains("password=hunter2"));
    }
}
