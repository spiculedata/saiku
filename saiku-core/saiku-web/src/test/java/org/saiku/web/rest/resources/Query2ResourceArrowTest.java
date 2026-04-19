/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.Test;
import org.olap4j.Axis;
import org.olap4j.Cell;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.Position;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.util.QueryContext;
import org.saiku.service.util.QueryContext.ObjectKey;
import org.saiku.service.util.QueryContext.Type;

/**
 * Content-negotiation unit test for {@link Query2Resource#execute}.
 *
 * We avoid standing up Jersey/Grizzly (and therefore a real OLAP connection)
 * by invoking the resource method directly with a stubbed
 * {@link ThinQueryService} that returns a hand-rolled {@link CellSet} proxy,
 * then asserting:
 *   - Response Content-Type begins with application/vnd.apache.arrow.stream
 *   - The streamed body contains a parseable Arrow IPC stream with >= 1 batch
 *   - The JSON path is untouched when Accept omits the Arrow type.
 */
public class Query2ResourceArrowTest {

    private static final String ARROW = "application/vnd.apache.arrow.stream";

    @Test
    public void arrowAcceptHeaderProducesArrowStream() throws Exception {
        Query2Resource resource = new Query2Resource();
        final CellSet cellSet = buildFakeCellSet();
        final ThinQuery tq = new ThinQuery();
        tq.setName("arrow-test");
        tq.setMdx("SELECT FROM [stub]");

        resource.setThinQueryService(new StubThinQueryService(tq, cellSet));

        HttpHeaders headers = fakeHeaders(MediaType.valueOf(ARROW), MediaType.APPLICATION_JSON_TYPE);

        Response resp = resource.execute(tq, headers);

        assertEquals(200, resp.getStatus());
        String contentType = resp.getMediaType().toString();
        assertTrue("content-type was " + contentType, contentType.startsWith(ARROW));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object entity = resp.getEntity();
        assertTrue(
                "entity should be StreamingOutput, was "
                        + (entity == null ? "null" : entity.getClass().getName()),
                entity instanceof StreamingOutput);
        ((StreamingOutput) entity).write(out);
        byte[] bytes = out.toByteArray();
        assertTrue("arrow body must be non-empty", bytes.length > 0);

        try (BufferAllocator alloc = new RootAllocator();
                ArrowStreamReader reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), alloc)) {
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            Schema schema = root.getSchema();
            assertNotNull(schema.getCustomMetadata().get("saiku.cellset"));
            assertTrue("at least one record batch", reader.loadNextBatch());
            assertEquals(3, root.getRowCount());
            assertFalse("no extra batches", reader.loadNextBatch());
        }
    }

    @Test
    public void jsonAcceptHeaderStillReturnsJsonQueryResult() throws Exception {
        Query2Resource resource = new Query2Resource();
        ThinQuery tq = new ThinQuery();
        tq.setName("json-test");
        tq.setMdx("SELECT FROM [stub]");
        resource.setThinQueryService(new StubThinQueryService(tq, buildFakeCellSet()));

        HttpHeaders headers = fakeHeaders(MediaType.APPLICATION_JSON_TYPE);
        Response resp = resource.execute(tq, headers);

        assertEquals(200, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());
        // Body is a QueryResult (or an error QueryResult if stub didn't give us enough);
        // either way it must not be a StreamingOutput — that would mean we took the
        // Arrow branch by mistake.
        assertFalse("must not stream arrow for JSON accept", resp.getEntity() instanceof StreamingOutput);
    }

    // ---- Test doubles -----------------------------------------------------

    /**
     * ThinQueryService subclass whose {@code execute(ThinQuery)} pretends to
     * run a query and stashes the provided CellSet into a freshly minted
     * {@link QueryContext} so {@link Query2Resource#execute} can fetch it via
     * {@code getContext(name).getOlapResult()}.
     *
     * The {@code CellDataSet} returned is a bare empty shell — enough for
     * {@code RestUtil.convert(...)} on the JSON path, though the JSON test
     * only asserts we didn't go down the Arrow branch.
     */
    static class StubThinQueryService extends ThinQueryService {
        private final ThinQuery query;
        private final CellSet cellSet;

        StubThinQueryService(ThinQuery q, CellSet cs) {
            this.query = q;
            this.cellSet = cs;
        }

        @Override
        public boolean isMdxDrillthrough(ThinQuery tq) {
            return false;
        }

        @Override
        public CellDataSet execute(ThinQuery tq) {
            installContext(tq);
            return new CellDataSet();
        }

        @Override
        public org.saiku.service.cache.SaikuQueryCache.CachedQueryResult executeCached(ThinQuery tq) {
            installContext(tq);
            // Serialise the stashed CellSet to Arrow bytes directly — this is
            // what the production path does on a cache miss; we skip the
            // disk cache entirely by returning a miss result.
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try {
                new org.saiku.olap.result.ArrowCellsetWriter().write(cellSet, query, baos);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return new org.saiku.service.cache.SaikuQueryCache.CachedQueryResult(baos.toByteArray(), 0L, 0, false);
        }

        private void installContext(ThinQuery tq) {
            QueryContext ctx = new QueryContext(Type.OLAP, query);
            ctx.store(ObjectKey.QUERY, query);
            ctx.store(ObjectKey.RESULT, cellSet);
            // install into the parent map via reflection since 'context' is private.
            try {
                java.lang.reflect.Field f = ThinQueryService.class.getDeclaredField("context");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, QueryContext> map = (Map<String, QueryContext>) f.get(this);
                map.put(tq.getName(), ctx);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static HttpHeaders fakeHeaders(final MediaType... accept) {
        final List<MediaType> list = Arrays.asList(accept);
        return (HttpHeaders) Proxy.newProxyInstance(
                Query2ResourceArrowTest.class.getClassLoader(),
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

    // ---- CellSet stub (adapted from ArrowCellsetWriterTest) ---------------

    private static <T> T proxy(Class<T> iface, Map<String, Object> answers) {
        return proxy(new Class<?>[] {iface}, answers, iface);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?>[] ifaces, Map<String, Object> answers, Class<T> primary) {
        Object obj =
                Proxy.newProxyInstance(Query2ResourceArrowTest.class.getClassLoader(), ifaces, new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("toString".equals(m.getName())) return primary.getSimpleName() + "@fake";
                        if ("equals".equals(m.getName())) return p == a[0];
                        if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                        Object ans = answers.get(m.getName());
                        if (ans != null) return ans;
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == double.class) return 0d;
                        if (rt.isPrimitive()) return 0;
                        return null;
                    }
                });
        return (T) obj;
    }

    private CellSet buildFakeCellSet() {
        Dimension dim = proxy(Dimension.class, map("getName", "Store"));
        Hierarchy hier =
                proxy(Hierarchy.class, map("getName", "Store", "getUniqueName", "[Store]", "getDimension", dim));
        Level lvl = proxy(Level.class, map("getName", "State", "getUniqueName", "[Store].[State]"));

        Member mUSA = member("USA", "[Store].[USA]", dim, hier, lvl);
        Member mCA = member("CA", "[Store].[USA].[CA]", dim, hier, lvl);
        Member mOR = member("OR", "[Store].[USA].[OR]", dim, hier, lvl);

        List<Position> rowPositions = Arrays.asList(
                pos(Collections.singletonList(mUSA)),
                pos(Collections.singletonList(mCA)),
                pos(Collections.singletonList(mOR)));
        Map<String, Object> rowAxisAnswers = new HashMap<>();
        rowAxisAnswers.put("getAxisOrdinal", Axis.ROWS);
        rowAxisAnswers.put("getPositions", rowPositions);
        rowAxisAnswers.put("getPositionCount", 3);
        CellSetAxis rowAxis = proxy(CellSetAxis.class, rowAxisAnswers);

        Dimension mdim = proxy(Dimension.class, map("getName", "Measures"));
        Hierarchy mhier =
                proxy(Hierarchy.class, map("getName", "Measures", "getUniqueName", "[Measures]", "getDimension", mdim));
        Level mlvl = proxy(Level.class, map("getName", "MeasuresLevel", "getUniqueName", "[Measures].[MeasuresLevel]"));
        Member meas1 = member("Unit Sales", "[Measures].[Unit Sales]", mdim, mhier, mlvl);
        Member meas2 = member("Store Sales", "[Measures].[Store Sales]", mdim, mhier, mlvl);
        List<Position> colPositions =
                Arrays.asList(pos(Collections.singletonList(meas1)), pos(Collections.singletonList(meas2)));
        Map<String, Object> colAxisAnswers = new HashMap<>();
        colAxisAnswers.put("getAxisOrdinal", Axis.COLUMNS);
        colAxisAnswers.put("getPositions", colPositions);
        colAxisAnswers.put("getPositionCount", 2);
        CellSetAxis colAxis = proxy(CellSetAxis.class, colAxisAnswers);

        double[][] values = {{1.0, 10.0}, {2.0, 20.0}, {3.0, 30.0}};
        final Map<List<Integer>, Cell> cellByCoord = new HashMap<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 2; c++) {
                double v = values[r][c];
                Map<String, Object> cellAnswers = new HashMap<>();
                cellAnswers.put("isEmpty", Boolean.FALSE);
                cellAnswers.put("isNull", Boolean.FALSE);
                cellAnswers.put("getValue", v);
                cellAnswers.put("getDoubleValue", v);
                cellAnswers.put("getFormattedValue", String.valueOf(v));
                cellByCoord.put(Arrays.asList(c, r), proxy(Cell.class, cellAnswers));
            }
        }

        final CellSetAxis fCol = colAxis;
        final CellSetAxis fRow = rowAxis;
        InvocationHandler cellSetHandler = new InvocationHandler() {
            final List<CellSetAxis> axes = Arrays.asList(fCol, fRow);

            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "getAxes":
                        return axes;
                    case "getCell":
                        if (a != null && a.length == 1 && a[0] instanceof List) {
                            return cellByCoord.get(a[0]);
                        }
                        return null;
                    case "toString":
                        return "CellSet@fake";
                    case "equals":
                        return p == a[0];
                    case "hashCode":
                        return System.identityHashCode(p);
                    default:
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt.isPrimitive()) return 0;
                        return null;
                }
            }
        };
        return (CellSet) Proxy.newProxyInstance(
                Query2ResourceArrowTest.class.getClassLoader(), new Class<?>[] {CellSet.class}, cellSetHandler);
    }

    private static Member member(String caption, String uniqueName, Dimension d, Hierarchy h, Level l) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getCaption", caption);
        answers.put("getName", caption);
        answers.put("getUniqueName", uniqueName);
        answers.put("getDimension", d);
        answers.put("getHierarchy", h);
        answers.put("getLevel", l);
        return proxy(Member.class, answers);
    }

    private static Position pos(List<Member> members) {
        return proxy(Position.class, map("getMembers", members, "getOrdinal", 0));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
