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

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.web.rest.objects.resultset.QueryResult;

/**
 * saiku#822 — Query2 parity with the AI Query API's drillthrough column
 * discovery + {@code firstRowset} bound. Both were already shipped in
 * {@link ThinQueryService} by saiku#819; this covers the {@link Query2Resource}
 * REST wiring added on top of it.
 *
 * <p>Mirrors {@link Query2ResourceArrowTest}'s approach of stubbing
 * {@link ThinQueryService} directly rather than standing up Jersey + a real
 * OLAP connection.
 */
public class Query2ResourceDrillthroughParityTest {

    @Test
    public void drillthroughForwardsFirstRowsetToTheServiceLayer() {
        RecordingThinQueryService stub = new RecordingThinQueryService();
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(stub);

        HttpHeaders headers = fakeHeaders(MediaType.APPLICATION_JSON_TYPE);
        resource.drillthrough("some-query", 100, 25, null, null, headers);

        assertEquals("some-query", stub.lastQueryName);
        assertEquals(Integer.valueOf(100), stub.lastMaxrows);
        assertEquals(Integer.valueOf(25), stub.lastFirstRowset);
    }

    @Test
    public void drillthroughOmitsFirstRowsetWhenNotSupplied() {
        RecordingThinQueryService stub = new RecordingThinQueryService();
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(stub);

        HttpHeaders headers = fakeHeaders(MediaType.APPLICATION_JSON_TYPE);
        resource.drillthrough("some-query", 100, null, null, null, headers);

        assertEquals(Integer.valueOf(100), stub.lastMaxrows);
        assertEquals(null, stub.lastFirstRowset);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void drillthroughColumnsReturnsTheDiscoveredEnvelope() {
        List<Map<String, String>> columns = new ArrayList<>();
        Map<String, String> yearCol = new LinkedHashMap<>();
        yearCol.put("name", "[Time].[Time].[Year]");
        yearCol.put("type", "VARCHAR");
        columns.add(yearCol);
        Map<String, String> salesCol = new LinkedHashMap<>();
        salesCol.put("name", "[Measures].[Store Sales]");
        salesCol.put("type", "DECIMAL");
        columns.add(salesCol);

        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new ColumnsStubThinQueryService(columns));

        Response resp = resource.drillthroughColumns("some-query");

        assertEquals(200, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());
        Object entity = resp.getEntity();
        assertTrue("entity should be a Map", entity instanceof Map);
        Map<String, Object> body = (Map<String, Object>) entity;
        assertEquals("some-query", body.get("queryId"));
        assertEquals(columns, body.get("columns"));
    }

    @Test
    public void drillthroughColumnsSurfacesServiceFailureAsQueryFailure() {
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new FailingColumnsThinQueryService());

        Response resp = resource.drillthroughColumns("unknown-query");

        assertNotNull(resp.getEntity());
        assertTrue("entity should be a QueryResult error envelope", resp.getEntity() instanceof QueryResult);
        assertTrue("error response must not be 2xx", resp.getStatus() >= 400);
    }

    // ---- Test doubles -----------------------------------------------------

    /** Captures the args {@link Query2Resource#drillthrough} forwards, then throws so the
     *  test doesn't need to fake a JDBC {@link ResultSet}. */
    static class RecordingThinQueryService extends ThinQueryService {
        String lastQueryName;
        Integer lastMaxrows;
        Integer lastFirstRowset;
        String lastReturns;

        @Override
        public ResultSet drillthrough(String queryName, int maxrows, Integer firstRowset, String returns) {
            lastQueryName = queryName;
            lastMaxrows = maxrows;
            lastFirstRowset = firstRowset;
            lastReturns = returns;
            throw new RuntimeException("recording stub — no real connection");
        }
    }

    static class ColumnsStubThinQueryService extends ThinQueryService {
        private final List<Map<String, String>> columns;

        ColumnsStubThinQueryService(List<Map<String, String>> columns) {
            this.columns = columns;
        }

        @Override
        public List<Map<String, String>> drillthroughColumns(String queryName) {
            return columns;
        }
    }

    static class FailingColumnsThinQueryService extends ThinQueryService {
        @Override
        public List<Map<String, String>> drillthroughColumns(String queryName) {
            throw new org.saiku.service.util.exception.SaikuServiceException(
                    "Error discovering drillthrough columns: " + queryName);
        }
    }

    private static HttpHeaders fakeHeaders(final MediaType... accept) {
        final List<MediaType> list = Arrays.asList(accept);
        return (HttpHeaders) Proxy.newProxyInstance(
                Query2ResourceDrillthroughParityTest.class.getClassLoader(),
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
}
