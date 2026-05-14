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
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.junit.Test;
import org.olap4j.CellSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.async.AsyncQueryHandle;
import org.saiku.service.async.AsyncQueryService;
import org.springframework.web.context.request.RequestAttributes;

/**
 * Happy-path unit test for the async /execute-async /status /result /cancel
 * routes. Uses a real {@link AsyncQueryService} with a pre-resolved
 * {@link CompletableFuture} so we don't need an OLAP driver on the classpath.
 */
public class AsyncQueryResourceTest {

    private static final String ARROW = "application/vnd.apache.arrow.stream";

    /**
     * Minimal AsyncQueryService subclass whose {@code submit} registers a
     * handle that's already resolved against a caller-supplied CellSet —
     * skipping the executor / ThinQueryService entirely. The sweeper still
     * runs but won't evict anything in the test window.
     */
    static class ResolvedAsyncQueryService extends AsyncQueryService {
        private final CellSet cellSet;

        ResolvedAsyncQueryService(CellSet cs) {
            super();
            this.cellSet = cs;
        }

        @Override
        public AsyncQueryHandle submit(ThinQuery q) {
            return submit(q, null);
        }

        @Override
        public AsyncQueryHandle submit(ThinQuery q, RequestAttributes ignored) {
            String id = java.util.UUID.randomUUID().toString();
            AsyncQueryHandle h = new AsyncQueryHandle(id, q);
            h.setFuture(CompletableFuture.completedFuture(cellSet));
            // Seed the map via reflection — handles field is private in parent.
            try {
                java.lang.reflect.Field f = AsyncQueryService.class.getDeclaredField("handles");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, AsyncQueryHandle> map = (Map<String, AsyncQueryHandle>) f.get(this);
                map.put(id, h);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            // Stay PENDING for the first status assertion; caller flips to DONE.
            return h;
        }
    }

    @Test
    public void executeAsyncStatusResultAndCancelHappyPath() throws Exception {
        Query2ResourceArrowTest helper = new Query2ResourceArrowTest();
        // Reuse the fake-cellset helper from the sibling test via reflection — it's
        // private, so invoke it reflectively.
        Method m = Query2ResourceArrowTest.class.getDeclaredMethod("buildFakeCellSet");
        m.setAccessible(true);
        CellSet cellSet = (CellSet) m.invoke(helper);

        ResolvedAsyncQueryService svc = new ResolvedAsyncQueryService(cellSet);
        Query2Resource resource = new Query2Resource();
        resource.setAsyncQueryService(svc);

        ThinQuery tq = new ThinQuery();
        tq.setName("async-happy");
        tq.setMdx("SELECT FROM [stub]");

        // ---- 1. executeAsync ----
        Response accepted = resource.executeAsync(tq);
        assertEquals(202, accepted.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) accepted.getEntity();
        String id = (String) body.get("queryId");
        assertNotNull("queryId present", id);
        assertEquals("PENDING", body.get("status"));

        // ---- 2. status transitions when we flip the handle to DONE ----
        AsyncQueryHandle h = svc.get(id);
        assertNotNull(h);
        h.setStatus(AsyncQueryHandle.Status.DONE);
        Response status = resource.asyncStatus(id);
        assertEquals(200, status.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> statusBody = (Map<String, Object>) status.getEntity();
        assertEquals("DONE", statusBody.get("status"));

        // ---- 3. result with Arrow Accept streams Arrow ----
        HttpHeaders arrowAccept = fakeHeaders(MediaType.valueOf(ARROW));
        Response result = resource.asyncResult(id, arrowAccept);
        assertEquals(200, result.getStatus());
        assertTrue(
                "content-type starts with arrow",
                result.getMediaType().toString().startsWith(ARROW));
        assertTrue("entity is StreamingOutput", result.getEntity() instanceof StreamingOutput);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ((StreamingOutput) result.getEntity()).write(baos);
        byte[] bytes = baos.toByteArray();
        assertTrue("arrow body non-empty", bytes.length > 0);
        try (BufferAllocator alloc = new RootAllocator();
                ArrowStreamReader reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), alloc)) {
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            assertNotNull(root.getSchema().getCustomMetadata().get("saiku.cellset"));
            assertTrue("at least one batch", reader.loadNextBatch());
            assertEquals(3, root.getRowCount());
        }

        // ---- 4. cancel returns 204 and flips handle status ----
        Response cancelled = resource.asyncCancel(id);
        assertEquals(204, cancelled.getStatus());
        assertEquals(AsyncQueryHandle.Status.CANCELLED, h.getStatus());

        // Tidy: shut the executor down so the JVM doesn't keep threads alive.
        svc.shutdown();
    }

    @Test
    public void unknownIdReturns404() {
        ResolvedAsyncQueryService svc = new ResolvedAsyncQueryService(null);
        Query2Resource resource = new Query2Resource();
        resource.setAsyncQueryService(svc);

        assertEquals(404, resource.asyncStatus("nope").getStatus());
        assertEquals(
                404,
                resource.asyncResult("nope", fakeHeaders(MediaType.APPLICATION_JSON_TYPE))
                        .getStatus());
        assertEquals(404, resource.asyncCancel("nope").getStatus());
        svc.shutdown();
    }

    @Test
    public void resultWhilePendingReturnsConflict() throws Exception {
        Query2ResourceArrowTest helper = new Query2ResourceArrowTest();
        Method m = Query2ResourceArrowTest.class.getDeclaredMethod("buildFakeCellSet");
        m.setAccessible(true);
        CellSet cellSet = (CellSet) m.invoke(helper);

        ResolvedAsyncQueryService svc = new ResolvedAsyncQueryService(cellSet);
        Query2Resource resource = new Query2Resource();
        resource.setAsyncQueryService(svc);

        ThinQuery tq = new ThinQuery();
        tq.setName("still-pending");
        Response accepted = resource.executeAsync(tq);
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) accepted.getEntity();
        String id = (String) b.get("queryId");

        // Handle is still PENDING — result must be 409.
        Response r = resource.asyncResult(id, fakeHeaders(MediaType.valueOf(ARROW)));
        assertEquals(409, r.getStatus());
        svc.shutdown();
    }

    private static HttpHeaders fakeHeaders(final MediaType... accept) {
        final List<MediaType> list = Arrays.asList(accept);
        return (HttpHeaders) Proxy.newProxyInstance(
                AsyncQueryResourceTest.class.getClassLoader(),
                new Class<?>[] {HttpHeaders.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method mm, Object[] a) {
                        if ("getAcceptableMediaTypes".equals(mm.getName())) return list;
                        Class<?> rt = mm.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt.isPrimitive()) return 0;
                        return null;
                    }
                });
    }
}
