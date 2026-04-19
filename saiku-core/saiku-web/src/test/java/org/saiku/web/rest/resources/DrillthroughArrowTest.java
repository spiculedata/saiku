/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.Test;
import org.saiku.service.olap.ThinQueryService;

/**
 * Round-trip test for drillthrough Arrow IPC serialisation.
 *
 * Stubs {@link ThinQueryService#drillthrough(String, Integer, String)} with
 * a fake {@link ResultSet} of 3 rows and 3 columns (string, integer, double)
 * and asserts the Arrow output matches.
 */
public class DrillthroughArrowTest {

    private static final String ARROW = "application/vnd.apache.arrow.stream";

    @Test
    public void arrowAcceptHeaderProducesArrowStream() throws Exception {
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new StubService(buildFakeResultSet()));

        HttpHeaders headers = fakeHeaders(MediaType.valueOf(ARROW), MediaType.APPLICATION_JSON_TYPE);
        Response resp = resource.drillthrough("q", 100, null, null, headers);

        assertEquals(200, resp.getStatus());
        assertTrue(resp.getMediaType().toString().startsWith(ARROW));

        Object entity = resp.getEntity();
        assertTrue("entity must be StreamingOutput", entity instanceof StreamingOutput);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((StreamingOutput) entity).write(out);
        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0);

        try (BufferAllocator alloc = new RootAllocator();
                ArrowStreamReader reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), alloc)) {
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            Schema schema = root.getSchema();

            // Column count + names
            List<Field> fields = schema.getFields();
            assertEquals(3, fields.size());
            assertEquals("name", fields.get(0).getName());
            assertEquals("qty", fields.get(1).getName());
            assertEquals("price", fields.get(2).getName());

            // Schema metadata
            String md = schema.getCustomMetadata().get("saiku.drillthrough");
            assertNotNull("saiku.drillthrough metadata present", md);
            Map<?, ?> parsed = new ObjectMapper().readValue(md, Map.class);
            assertEquals(3, parsed.get("rowCount"));
            assertNotNull(parsed.get("runtimeMs"));
            assertNotNull(parsed.get("captions"));

            assertTrue("one batch", reader.loadNextBatch());
            assertEquals(3, root.getRowCount());

            // Numeric column assertion: price (Float8) values.
            Float8Vector price = (Float8Vector) root.getVector("price");
            double[] got = new double[3];
            for (int i = 0; i < 3; i++) got[i] = price.get(i);
            assertArrayEquals(new double[] {9.99, 19.5, 5.0}, got, 0.0001);

            // String column (dictionary-encoded): name values.
            IntVector nameIdx = (IntVector) root.getVector("name");
            DictionaryEncoding enc = nameIdx.getField().getDictionary();
            assertNotNull("name column dictionary encoded", enc);
            Dictionary dict = reader.getDictionaryVectors().get(enc.getId());
            assertNotNull(dict);
            VarCharVector dictVec = (VarCharVector) dict.getVector();
            String[] names = new String[3];
            for (int i = 0; i < 3; i++) {
                int idx = nameIdx.get(i);
                names[i] = new String(dictVec.get(idx), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertArrayEquals(new String[] {"Widget", "Gadget", "Sprocket"}, names);

            // Also check numeric qty col (INTEGER collapses to Float8).
            FieldVector qtyVec = root.getVector("qty");
            assertTrue("qty is Float8", qtyVec instanceof Float8Vector);
            Float8Vector qty = (Float8Vector) qtyVec;
            assertEquals(3.0, qty.get(0), 0.0);
            assertEquals(7.0, qty.get(1), 0.0);
            assertEquals(12.0, qty.get(2), 0.0);
        }
    }

    // ---- Test doubles ---------------------------------------------------

    static class StubService extends ThinQueryService {
        private final ResultSet rs;

        StubService(ResultSet rs) {
            this.rs = rs;
        }

        @Override
        public ResultSet drillthrough(String queryName, int maxrows, String returns) {
            return rs;
        }
    }

    private static HttpHeaders fakeHeaders(final MediaType... accept) {
        final List<MediaType> list = Arrays.asList(accept);
        return (HttpHeaders) Proxy.newProxyInstance(
                DrillthroughArrowTest.class.getClassLoader(),
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

    private static ResultSet buildFakeResultSet() {
        // 3 columns: name (VARCHAR), qty (INTEGER), price (DOUBLE) - 3 rows.
        final String[] colNames = {"name", "qty", "price"};
        final int[] colTypes = {Types.VARCHAR, Types.INTEGER, Types.DOUBLE};
        final Object[][] rows = {
            {"Widget", 3, 9.99},
            {"Gadget", 7, 19.5},
            {"Sprocket", 12, 5.0},
        };
        ResultSetMetaData md = (ResultSetMetaData) Proxy.newProxyInstance(
                DrillthroughArrowTest.class.getClassLoader(),
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
                DrillthroughArrowTest.class.getClassLoader(),
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
