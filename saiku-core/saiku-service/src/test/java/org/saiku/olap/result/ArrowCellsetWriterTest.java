/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.olap.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.types.pojo.Field;
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
import org.saiku.olap.query2.ThinQuery;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercises {@link ArrowCellsetWriter} against a hand-rolled fake CellSet
 * (3 rows x 2 measures). We avoid Mockito because the project still ships
 * mockito-all 1.8.5, which cannot instrument classes on JDK 17.
 */
public class ArrowCellsetWriterTest {

    @Test
    public void writesSingleRecordBatchWithRowsAndMeasures() throws Exception {
        CellSet cellSet = buildFakeCellSet();
        ThinQuery query = new ThinQuery();
        query.setName("States_x_Measures");
        query.setMdx("SELECT ... fake ...");

        ArrowCellsetWriter writer = new ArrowCellsetWriter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(cellSet, query, out);

        byte[] bytes = out.toByteArray();
        assertTrue("writer produced bytes", bytes.length > 0);

        try (BufferAllocator alloc = new RootAllocator();
             ArrowStreamReader reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), alloc)) {
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            Schema schema = root.getSchema();

            String meta = schema.getCustomMetadata().get("saiku.cellset");
            assertNotNull("saiku.cellset metadata present", meta);
            ObjectMapper om = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = om.readValue(meta, Map.class);
            assertEquals(1, ((Number) decoded.get("rowHeaderColCount")).intValue());
            List<?> colHeaderRows = (List<?>) decoded.get("columnHeaderRows");
            assertTrue("columnHeaderRows non-empty", colHeaderRows.size() >= 1);
            assertEquals("States_x_Measures", decoded.get("queryName"));

            assertTrue("has batch", reader.loadNextBatch());
            assertEquals(3, root.getRowCount());

            FieldVector r0 = root.getVector("r0_value");
            assertNotNull(r0);
            List<String> rowValues = decodeDictString(r0, reader);
            assertEquals(Arrays.asList("USA", "CA", "OR"), rowValues);

            Float8Vector c0raw = (Float8Vector) root.getVector("c0_raw");
            assertEquals(1.0, c0raw.get(0), 0.0001);
            assertEquals(2.0, c0raw.get(1), 0.0001);
            assertEquals(3.0, c0raw.get(2), 0.0001);

            Float8Vector c1raw = (Float8Vector) root.getVector("c1_raw");
            assertEquals(10.0, c1raw.get(0), 0.0001);
            assertEquals(20.0, c1raw.get(1), 0.0001);
            assertEquals(30.0, c1raw.get(2), 0.0001);

            assertFalse("no second batch", reader.loadNextBatch());
        }
    }

    private static List<String> decodeDictString(FieldVector encoded, ArrowStreamReader reader) throws Exception {
        Field f = encoded.getField();
        long dictId = f.getDictionary().getId();
        Dictionary dict = reader.getDictionaryVectors().get(dictId);
        VarCharVector dictVec = (VarCharVector) dict.getVector();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < encoded.getValueCount(); i++) {
            Object idxObj = encoded.getObject(i);
            if (idxObj == null) { out.add(null); continue; }
            int idx = ((Number) idxObj).intValue();
            byte[] b = dictVec.get(idx);
            out.add(b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    // --- fakes via Proxy ---------------------------------------------------

    private static <T> T proxy(Class<T> iface, Map<String, Object> answers) {
        return proxy(new Class<?>[]{iface}, answers, iface);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?>[] ifaces, Map<String, Object> answers, Class<T> primary) {
        Object obj = Proxy.newProxyInstance(
                ArrowCellsetWriterTest.class.getClassLoader(),
                ifaces,
                new InvocationHandler() {
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
                        if (rt == float.class) return 0f;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == char.class) return (char) 0;
                        if (rt == void.class) return null;
                        return null;
                    }
                });
        return (T) obj;
    }

    private CellSet buildFakeCellSet() {
        // Row axis members
        Dimension dim = proxy(Dimension.class, map("getName", "Store"));
        Hierarchy hier = proxy(Hierarchy.class, map("getName", "Store", "getUniqueName", "[Store]", "getDimension", dim));
        Level lvl = proxy(Level.class, map("getName", "State", "getUniqueName", "[Store].[State]"));

        Member mUSA = member("USA", "[Store].[USA]", dim, hier, lvl);
        Member mCA = member("CA", "[Store].[USA].[CA]", dim, hier, lvl);
        Member mOR = member("OR", "[Store].[USA].[OR]", dim, hier, lvl);

        Position pUSA = pos(Collections.singletonList(mUSA));
        Position pCA = pos(Collections.singletonList(mCA));
        Position pOR = pos(Collections.singletonList(mOR));

        List<Position> rowPositions = Arrays.asList(pUSA, pCA, pOR);
        Map<String, Object> rowAxisAnswers = new HashMap<>();
        rowAxisAnswers.put("getAxisOrdinal", Axis.ROWS);
        rowAxisAnswers.put("getPositions", rowPositions);
        rowAxisAnswers.put("getPositionCount", 3);
        CellSetAxis rowAxis = proxy(CellSetAxis.class, rowAxisAnswers);

        // Column axis: measures
        Dimension mdim = proxy(Dimension.class, map("getName", "Measures"));
        Hierarchy mhier = proxy(Hierarchy.class, map("getName", "Measures", "getUniqueName", "[Measures]", "getDimension", mdim));
        Level mlvl = proxy(Level.class, map("getName", "MeasuresLevel", "getUniqueName", "[Measures].[MeasuresLevel]"));
        Member meas1 = member("Unit Sales", "[Measures].[Unit Sales]", mdim, mhier, mlvl);
        Member meas2 = member("Store Sales", "[Measures].[Store Sales]", mdim, mhier, mlvl);
        List<Position> colPositions = Arrays.asList(pos(Collections.singletonList(meas1)),
                pos(Collections.singletonList(meas2)));
        Map<String, Object> colAxisAnswers = new HashMap<>();
        colAxisAnswers.put("getAxisOrdinal", Axis.COLUMNS);
        colAxisAnswers.put("getPositions", colPositions);
        colAxisAnswers.put("getPositionCount", 2);
        CellSetAxis colAxis = proxy(CellSetAxis.class, colAxisAnswers);

        double[][] values = {
            { 1.0, 10.0 },
            { 2.0, 20.0 },
            { 3.0, 30.0 }
        };
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

        // CellSet proxy — override getCell(List) dynamically.
        InvocationHandler cellSetHandler = new InvocationHandler() {
            final List<CellSetAxis> axes = Arrays.asList(colAxis, rowAxis);
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "getAxes": return axes;
                    case "getCell":
                        if (a != null && a.length == 1 && a[0] instanceof List) {
                            return cellByCoord.get(a[0]);
                        }
                        return null;
                    case "toString": return "CellSet@fake";
                    case "equals": return p == a[0];
                    case "hashCode": return System.identityHashCode(p);
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
        };
        return (CellSet) Proxy.newProxyInstance(
                ArrowCellsetWriterTest.class.getClassLoader(),
                new Class<?>[]{CellSet.class},
                cellSetHandler);
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
