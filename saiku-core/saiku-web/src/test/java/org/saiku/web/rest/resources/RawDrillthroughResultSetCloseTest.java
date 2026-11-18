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

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.OlapQueryService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.web.rest.objects.resultset.QueryResult;

/**
 * The drillthrough REST paths hand the engine's {@link ResultSet} to the resource, which copies the
 * rows into the response. The engine keeps one SQL statement slot per open result set
 * ({@code mondrian.query.limit}) and only gives it back on {@code ResultSet.close()}, so every path
 * must close the result set after reading it, on success and on failure alike.
 */
public class RawDrillthroughResultSetCloseTest {

    private static final String RAW_MDX = "DRILLTHROUGH MAXROWS 1 SELECT FROM [Sales]";

    // ---- Query2Resource.execute (also reached by /api/notebooks/run) --------------------------

    @Test
    public void query2Execute_rawDrillthrough_closesResultSetAndStatement_afterReadingRows() {
        TrackingResultSet t = new TrackingResultSet(false);
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(rawDrillthroughService(t.rs));

        Response resp = resource.execute(rawQuery(), null);

        assertEquals(200, resp.getStatus());
        QueryResult body = (QueryResult) resp.getEntity();
        assertEquals("the row must be read before the ResultSet is closed", 1, (int) body.getHeight());
        assertTrue("ResultSet must be closed", t.rsClosed.get());
        assertTrue("Statement must be closed", t.stmtClosed.get());
    }

    @Test
    public void query2Execute_rawDrillthrough_closesResultSet_whenReadingFails() {
        TrackingResultSet t = new TrackingResultSet(true);
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(rawDrillthroughService(t.rs));

        Response resp = resource.execute(rawQuery(), null);

        assertEquals(500, resp.getStatus());
        assertTrue("ResultSet must be closed on the failure path", t.rsClosed.get());
        assertTrue("Statement must be closed on the failure path", t.stmtClosed.get());
    }

    // ---- legacy QueryResource raw MDX branch -------------------------------------------------

    @Test
    public void legacyExecuteMdx_rawDrillthrough_closesResultSetAndStatement_afterReadingRows() {
        TrackingResultSet t = new TrackingResultSet(false);
        QueryResource resource = new QueryResource();
        resource.setOlapQueryService(rawDrillthroughOlapService(t.rs));

        QueryResult result = resource.executeMdx("q", RAW_MDX, 0);

        assertNotNull(result);
        assertEquals("the row must be read before the ResultSet is closed", 1, (int) result.getHeight());
        assertTrue("ResultSet must be closed", t.rsClosed.get());
        assertTrue("Statement must be closed", t.stmtClosed.get());
    }

    @Test
    public void legacyExecuteMdx_rawDrillthrough_closesResultSet_whenReadingFails() {
        TrackingResultSet t = new TrackingResultSet(true);
        QueryResource resource = new QueryResource();
        resource.setOlapQueryService(rawDrillthroughOlapService(t.rs));

        QueryResult result = resource.executeMdx("q", RAW_MDX, 0);

        assertNotNull(result.getError());
        assertTrue("ResultSet must be closed on the failure path", t.rsClosed.get());
        assertTrue("Statement must be closed on the failure path", t.stmtClosed.get());
    }

    // ---- AI Query API drillthrough -----------------------------------------------------------

    @Test
    public void aiDrillthrough_closesResultSet_whenReadingFails() {
        TrackingResultSet t = new TrackingResultSet(true);
        AiQueryResource resource = new AiQueryResource();
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public ResultSet drillthrough(String queryName, int maxrows, Integer firstRowset, String returns) {
                return t.rs;
            }
        });

        Response resp = resource.drillthrough("q", 100, null, null, null);

        assertEquals(500, resp.getStatus());
        assertTrue("ResultSet must be closed on the failure path", t.rsClosed.get());
    }

    @Test
    public void aiDrillthrough_closesResultSet_afterReadingRows() {
        TrackingResultSet t = new TrackingResultSet(false);
        AiQueryResource resource = new AiQueryResource();
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public ResultSet drillthrough(String queryName, int maxrows, Integer firstRowset, String returns) {
                return t.rs;
            }
        });

        Response resp = resource.drillthrough("q", 100, null, null, null);

        assertEquals(200, resp.getStatus());
        assertTrue("ResultSet must be closed", t.rsClosed.get());
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static ThinQuery rawQuery() {
        ThinQuery tq = new ThinQuery("raw-dt", new SaikuCube("c", "Sales", "Sales", "Sales", "FoodMart", "FoodMart"));
        tq.setMdx(RAW_MDX);
        tq.setType(ThinQuery.Type.MDX);
        return tq;
    }

    private static ThinQueryService rawDrillthroughService(final ResultSet rs) {
        return new ThinQueryService() {
            @Override
            public boolean isMdxDrillthrough(ThinQuery query) {
                return true;
            }

            @Override
            public ResultSet drillthrough(ThinQuery query) {
                return rs;
            }
        };
    }

    private static OlapQueryService rawDrillthroughOlapService(final ResultSet rs) {
        return new OlapQueryService() {
            @Override
            public void qm2mdx(String queryName) {}

            @Override
            public boolean isMdxDrillthrough(String queryName, String drillthroughMdx) {
                return true;
            }

            @Override
            public ResultSet drillthrough(String queryName, String drillthroughMdx) {
                return rs;
            }
        };
    }

    /**
     * A one-row, one-column result set whose parent statement and own {@code close()} are
     * recorded. With {@code failOnNext} the first {@code next()} throws an unchecked exception,
     * standing in for a failure while the rows are being read.
     */
    private static final class TrackingResultSet {
        final AtomicBoolean rsClosed = new AtomicBoolean();
        final AtomicBoolean stmtClosed = new AtomicBoolean();
        final ResultSet rs;

        TrackingResultSet(final boolean failOnNext) {
            final ClassLoader cl = RawDrillthroughResultSetCloseTest.class.getClassLoader();
            final Statement stmt =
                    (Statement) Proxy.newProxyInstance(cl, new Class<?>[] {Statement.class}, (p, m, a) -> {
                        if ("close".equals(m.getName())) {
                            stmtClosed.set(true);
                            return null;
                        }
                        return defaultValue(m.getReturnType());
                    });
            final ResultSetMetaData md = (ResultSetMetaData)
                    Proxy.newProxyInstance(cl, new Class<?>[] {ResultSetMetaData.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "getColumnCount":
                                return 1;
                            case "getColumnName":
                            case "getColumnLabel":
                                return "col";
                            case "getColumnType":
                                return Types.VARCHAR;
                            case "getColumnTypeName":
                                return "VARCHAR";
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });
            final int[] cursor = {0};
            this.rs = (ResultSet) Proxy.newProxyInstance(cl, new Class<?>[] {ResultSet.class}, (p, m, a) -> {
                switch (m.getName()) {
                    case "next":
                        if (failOnNext) {
                            throw new IllegalStateException("read failed");
                        }
                        return cursor[0]++ == 0;
                    case "getMetaData":
                        return md;
                    case "getStatement":
                        return stmt;
                    case "getString":
                    case "getObject":
                        return "v";
                    case "close":
                        rsClosed.set(true);
                        return null;
                    case "isClosed":
                        return rsClosed.get();
                    default:
                        return defaultValue(m.getReturnType());
                }
            });
        }

        private static Object defaultValue(Class<?> rt) {
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == double.class) return 0d;
            if (rt == float.class) return 0f;
            if (rt == short.class) return (short) 0;
            if (rt == byte.class) return (byte) 0;
            return null;
        }
    }
}
