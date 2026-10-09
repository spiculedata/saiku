/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.web.rest.objects.resultset.QueryResult;

/**
 * saiku#822: Query2 (workspace) drillthrough parity with the AI Query API — the
 * {@code firstRowset} query param on {@code GET /{queryname}/drillthrough} and the new
 * {@code GET /{queryname}/drillthrough/columns} discovery endpoint. Both delegate to
 * {@link ThinQueryService} methods that already shipped in saiku#819; this only locks the
 * REST wiring added on {@link Query2Resource}.
 */
public class Query2ResourceDrillthroughTest {

    @Test
    public void firstRowsetQueryParamIsForwardedToTheService() throws Exception {
        CapturingStubService stub = new CapturingStubService(buildFakeResultSet());
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(stub);

        Response resp = resource.drillthrough("q", 100, 25, null, "[Measures].[Sales]", arrowHeaders());

        assertEquals(200, resp.getStatus());
        assertEquals("q", stub.lastQueryName);
        assertEquals(Integer.valueOf(100), stub.lastMaxrows);
        assertEquals(Integer.valueOf(25), stub.lastFirstRowset);
        assertEquals("[Measures].[Sales]", stub.lastReturns);
    }

    @Test
    public void omittedFirstRowsetIsForwardedAsNull() throws Exception {
        CapturingStubService stub = new CapturingStubService(buildFakeResultSet());
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(stub);

        Response resp = resource.drillthrough("q", 100, null, null, null, arrowHeaders());

        assertEquals(200, resp.getStatus());
        assertNull("no firstRowset query param means no bound", stub.lastFirstRowset);
    }

    @Test
    public void drillthroughColumnsReturnsQueryIdAndColumnsEnvelope() {
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new ColumnsStubService("q"));

        Response resp = resource.drillthroughColumns("q");

        assertEquals(200, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("q", body.get("queryId"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> columns = (List<Map<String, String>>) body.get("columns");
        assertEquals(2, columns.size());
        assertEquals("[Time].[Time].[Year]", columns.get(0).get("name"));
        assertEquals("VARCHAR", columns.get(0).get("type"));
        assertEquals("[Measures].[Sales]", columns.get(1).get("name"));
        assertEquals("DOUBLE", columns.get(1).get("type"));
    }

    @Test
    public void drillthroughColumnsFailure_returnsGenericErrorAndDoesNotLeak() {
        final String secret = "jdbc:postgresql://10.0.0.5:5432/warehouse?user=svc&password=hunter2";
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public List<Map<String, String>> drillthroughColumns(String queryName) {
                throw new RuntimeException(secret);
            }
        });

        Response resp = resource.drillthroughColumns("q");

        assertEquals(500, resp.getStatus());
        assertTrue("entity must be a QueryResult", resp.getEntity() instanceof QueryResult);
        String error = ((QueryResult) resp.getEntity()).getError();
        assertFalse("must not leak the root cause", error != null && error.contains(secret));
    }

    // ---- Test doubles -----------------------------------------------------

    static class CapturingStubService extends ThinQueryService {
        private final ResultSet rs;
        String lastQueryName;
        Integer lastMaxrows;
        Integer lastFirstRowset;
        String lastReturns;

        CapturingStubService(ResultSet rs) {
            this.rs = rs;
        }

        @Override
        public ResultSet drillthrough(String queryName, int maxrows, Integer firstRowset, String returns) {
            this.lastQueryName = queryName;
            this.lastMaxrows = maxrows;
            this.lastFirstRowset = firstRowset;
            this.lastReturns = returns;
            return rs;
        }
    }

    static class ColumnsStubService extends ThinQueryService {
        private final String expectedQueryName;

        ColumnsStubService(String expectedQueryName) {
            this.expectedQueryName = expectedQueryName;
        }

        @Override
        public List<Map<String, String>> drillthroughColumns(String queryName) {
            assertEquals(expectedQueryName, queryName);
            Map<String, String> col1 = new LinkedHashMap<>();
            col1.put("name", "[Time].[Time].[Year]");
            col1.put("type", "VARCHAR");
            Map<String, String> col2 = new LinkedHashMap<>();
            col2.put("name", "[Measures].[Sales]");
            col2.put("type", "DOUBLE");
            return Arrays.asList(col1, col2);
        }
    }

    private static final String ARROW = "application/vnd.apache.arrow.stream";

    private static HttpHeaders arrowHeaders() {
        final List<MediaType> list = Arrays.asList(MediaType.valueOf(ARROW), MediaType.APPLICATION_JSON_TYPE);
        return (HttpHeaders) Proxy.newProxyInstance(
                Query2ResourceDrillthroughTest.class.getClassLoader(),
                new Class<?>[] {HttpHeaders.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("getAcceptableMediaTypes".equals(m.getName())) return list;
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt.isPrimitive()) return 0;
                        return null;
                    }
                });
    }

    // Same 3-column (VARCHAR, INTEGER, DOUBLE) shape DrillthroughArrowTest uses — proven
    // compatible with ArrowDrillthroughWriter without having to stub every ResultSet method.
    private static ResultSet buildFakeResultSet() {
        final String[] colNames = {"name", "qty", "price"};
        final int[] colTypes = {Types.VARCHAR, Types.INTEGER, Types.DOUBLE};
        final Object[][] rows = {{"Widget", 3, 9.99}};
        ResultSetMetaData md = (ResultSetMetaData) Proxy.newProxyInstance(
                Query2ResourceDrillthroughTest.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a) {
                        switch (m.getName()) {
                            case "getColumnCount":
                                return 3;
                            case "getColumnName":
                            case "getColumnLabel":
                                return colNames[((Integer) a[0]) - 1];
                            case "getColumnType":
                                return colTypes[((Integer) a[0]) - 1];
                            default:
                                Class<?> rt = m.getReturnType();
                                if (rt == boolean.class) return false;
                                if (rt == int.class) return 0;
                                if (rt.isPrimitive()) return 0;
                                return null;
                        }
                    }
                });

        return (ResultSet) Proxy.newProxyInstance(
                Query2ResourceDrillthroughTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                new InvocationHandler() {
                    int cursor = -1;
                    boolean lastWasNull = false;

                    @Override
                    public Object invoke(Object p, Method m, Object[] a) {
                        switch (m.getName()) {
                            case "getMetaData":
                                return md;
                            case "next":
                                cursor++;
                                return cursor < rows.length;
                            case "wasNull":
                                return lastWasNull;
                            case "getObject": {
                                int idx = (Integer) a[0];
                                Object v = rows[cursor][idx - 1];
                                lastWasNull = v == null;
                                return v;
                            }
                            case "getDouble": {
                                int idx = (Integer) a[0];
                                Object v = rows[cursor][idx - 1];
                                lastWasNull = v == null;
                                if (v == null) return 0.0;
                                return ((Number) v).doubleValue();
                            }
                            case "close":
                                return null;
                            case "getStatement":
                                return null;
                            default:
                                Class<?> rt = m.getReturnType();
                                if (rt == boolean.class) return false;
                                if (rt == int.class) return 0;
                                if (rt == long.class) return 0L;
                                if (rt == double.class) return 0d;
                                if (rt.isPrimitive()) return 0;
                                return null;
                        }
                    }
                });
    }
}
