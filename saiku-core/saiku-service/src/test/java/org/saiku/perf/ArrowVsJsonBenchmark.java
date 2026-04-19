/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.perf;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Assume;
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
import org.saiku.olap.result.ArrowCellsetWriter;

/**
 * Offline benchmark that compares Arrow IPC vs. a JSON payload mirroring the
 * existing wire contract (rows of row-header member cells + data cells).
 *
 * <p>Skipped by default. Run with:
 * <pre>
 *   mvn -pl saiku-core/saiku-service test \
 *     -Dtest=ArrowVsJsonBenchmark -Dsaiku.benchmark=true \
 *     -Dsurefire.failIfNoSpecifiedTests=false -q
 * </pre>
 *
 * <p>Uses a hand-rolled {@link Proxy} CellSet — no OLAP server required.
 */
public class ArrowVsJsonBenchmark {

    private static final int[][] SHAPES = {
        {100, 4},
        {1_000, 8},
        {10_000, 4},
        {50_000, 4},
    };

    private static final int WARMUP = 1;
    private static final int RUNS = 5;

    @Test
    public void benchmark() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("saiku.benchmark"));

        StringBuilder table = new StringBuilder();
        table.append("| rows   | cols | format | bytes      | ms   | ratio (bytes) | ratio (ms) |\n");
        table.append("|--------|------|--------|------------|------|---------------|------------|\n");

        for (int[] shape : SHAPES) {
            int rows = shape[0];
            int cols = shape[1];
            CellSet cs = buildFakeCellSet(rows, cols);
            ThinQuery tq = new ThinQuery();
            tq.setName("bench_" + rows + "x" + cols);
            tq.setMdx("SELECT <benchmark> ON COLUMNS FROM [Bench]");

            Result json = measureJson(cs, tq);
            Result arrow = measureArrow(cs, tq);

            double bytesRatio = (double) arrow.bytes / (double) json.bytes;
            double msRatio = arrow.ms / json.ms;

            row(table, rows, cols, "json", json.bytes, json.ms, 1.0, 1.0);
            row(table, rows, cols, "arrow", arrow.bytes, arrow.ms, bytesRatio, msRatio);
        }

        String out = table.toString();
        System.out.println();
        System.out.println("=== Arrow vs JSON benchmark ===");
        System.out.println(out);
    }

    // ---------------- measurement --------------------------------------------

    private static final class Result {
        final long bytes;
        final double ms;

        Result(long bytes, double ms) {
            this.bytes = bytes;
            this.ms = ms;
        }
    }

    private static Result measureArrow(CellSet cs, ThinQuery tq) throws Exception {
        // warmup
        for (int i = 0; i < WARMUP; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new ArrowCellsetWriter().write(cs, tq, out);
        }
        long[] nanos = new long[RUNS];
        long lastBytes = 0;
        for (int i = 0; i < RUNS; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long t0 = System.nanoTime();
            new ArrowCellsetWriter().write(cs, tq, out);
            long t1 = System.nanoTime();
            nanos[i] = t1 - t0;
            lastBytes = out.size();
        }
        return new Result(lastBytes, medianMs(nanos));
    }

    private static Result measureJson(CellSet cs, ThinQuery tq) throws Exception {
        // warmup
        for (int i = 0; i < WARMUP; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeJson(cs, tq, out);
        }
        long[] nanos = new long[RUNS];
        long lastBytes = 0;
        for (int i = 0; i < RUNS; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long t0 = System.nanoTime();
            writeJson(cs, tq, out);
            long t1 = System.nanoTime();
            nanos[i] = t1 - t0;
            lastBytes = out.size();
        }
        return new Result(lastBytes, medianMs(nanos));
    }

    /**
     * Hand-rolled JSON that mirrors the existing {@code QueryResult} wire
     * contract closely enough for payload-size comparison: each row is an
     * array of cells; each cell is {@code {value,type,properties?}}. The
     * column-header row comes first. An {@code ObjectMapper} is constructed
     * inside the timed block to mirror the real request path where a fresh
     * mapper is pulled from Jackson-Jersey per-request.
     */
    private static void writeJson(CellSet cs, ThinQuery tq, ByteArrayOutputStream out) throws Exception {
        ObjectMapper om = new ObjectMapper();
        try (JsonGenerator g = om.getFactory().createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("queryName", tq.getName());
            g.writeStringField("mdx", tq.getMdx());

            CellSetAxis colAxis = null, rowAxis = null;
            for (CellSetAxis a : cs.getAxes()) {
                if (a.getAxisOrdinal() == Axis.COLUMNS) colAxis = a;
                else if (a.getAxisOrdinal() == Axis.ROWS) rowAxis = a;
            }
            int cols = colAxis == null ? 0 : colAxis.getPositionCount();
            int rows = rowAxis == null ? 0 : rowAxis.getPositionCount();
            int rowHdrCols = 1;
            List<Position> rowPositions = rowAxis == null ? Collections.emptyList() : rowAxis.getPositions();
            if (!rowPositions.isEmpty()) {
                List<Member> m = rowPositions.get(0).getMembers();
                rowHdrCols = m == null || m.isEmpty() ? 1 : m.size();
            }

            g.writeNumberField("width", rowHdrCols + cols);
            g.writeNumberField("height", rows + 1);

            g.writeArrayFieldStart("cellset");

            // column header row
            g.writeStartArray();
            for (int i = 0; i < rowHdrCols; i++) {
                g.writeStartObject();
                g.writeStringField("value", "");
                g.writeStringField("type", "ROW_HEADER_HEADER");
                g.writeEndObject();
            }
            if (colAxis != null) {
                for (Position p : colAxis.getPositions()) {
                    List<Member> mems = p.getMembers();
                    Member m = mems == null || mems.isEmpty() ? null : mems.get(mems.size() - 1);
                    g.writeStartObject();
                    g.writeStringField("value", m == null ? "" : nn(m.getCaption(), m.getName()));
                    g.writeStringField("type", "COLUMN_HEADER");
                    g.writeEndObject();
                }
            }
            g.writeEndArray();

            // body rows
            for (int r = 0; r < rows; r++) {
                g.writeStartArray();
                Position rp = rowPositions.get(r);
                List<Member> rmems = rp.getMembers();
                for (int h = 0; h < rowHdrCols; h++) {
                    Member m = rmems != null && h < rmems.size() ? rmems.get(h) : null;
                    g.writeStartObject();
                    g.writeStringField("value", m == null ? "" : nn(m.getCaption(), m.getName()));
                    g.writeStringField("type", "ROW_HEADER");
                    if (m != null) {
                        g.writeObjectFieldStart("properties");
                        g.writeStringField("uniquename", nz(m.getUniqueName()));
                        Dimension d = m.getDimension();
                        g.writeStringField("dimension", d == null ? "" : nz(d.getName()));
                        Hierarchy hier = m.getHierarchy();
                        g.writeStringField("hierarchy", hier == null ? "" : nz(hier.getUniqueName()));
                        Level l = m.getLevel();
                        g.writeStringField("level", l == null ? "" : nz(l.getUniqueName()));
                        g.writeEndObject();
                    }
                    g.writeEndObject();
                }
                for (int c = 0; c < cols; c++) {
                    Cell cell = cs.getCell(Arrays.asList(c, r));
                    g.writeStartObject();
                    if (cell == null || cell.isEmpty() || cell.isNull()) {
                        g.writeStringField("value", "");
                        g.writeStringField("type", "DATA_CELL");
                    } else {
                        Object v = cell.getValue();
                        double raw = v instanceof Number ? ((Number) v).doubleValue() : 0d;
                        g.writeNumberField("raw", raw);
                        g.writeStringField("value", nz(cell.getFormattedValue()));
                        g.writeStringField("type", "DATA_CELL");
                    }
                    g.writeEndObject();
                }
                g.writeEndArray();
            }
            g.writeEndArray();
            g.writeEndObject();
            g.flush();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String nn(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    private static double medianMs(long[] nanos) {
        long[] copy = nanos.clone();
        Arrays.sort(copy);
        long med = copy[copy.length / 2];
        return med / 1_000_000.0;
    }

    private static void row(
            StringBuilder table,
            int rows,
            int cols,
            String format,
            long bytes,
            double ms,
            double bytesRatio,
            double msRatio) {
        table.append(String.format(
                Locale.ROOT,
                "| %-6d | %-4d | %-6s | %10d | %4.1f | %13.2f | %10.2f |%n",
                rows,
                cols,
                format,
                bytes,
                ms,
                bytesRatio,
                msRatio));
    }

    // ---------------- synthetic CellSet --------------------------------------

    private static <T> T proxy(Class<T> iface, Map<String, Object> answers) {
        return proxy(new Class<?>[] {iface}, answers, iface);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?>[] ifaces, Map<String, Object> answers, Class<T> primary) {
        Object obj = Proxy.newProxyInstance(ArrowVsJsonBenchmark.class.getClassLoader(), ifaces, (p, m, a) -> {
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
        });
        return (T) obj;
    }

    private static Member member(String caption, String uniqueName, Dimension d, Hierarchy h, Level l) {
        Map<String, Object> a = new HashMap<>();
        a.put("getCaption", caption);
        a.put("getName", caption);
        a.put("getUniqueName", uniqueName);
        a.put("getDimension", d);
        a.put("getHierarchy", h);
        a.put("getLevel", l);
        return proxy(Member.class, a);
    }

    private static Position pos(List<Member> members) {
        Map<String, Object> a = new HashMap<>();
        a.put("getMembers", members);
        a.put("getOrdinal", 0);
        return proxy(Position.class, a);
    }

    /** Build a deterministic CellSet with {@code rows} rows and {@code cols} measures. */
    static CellSet buildFakeCellSet(int rows, int cols) {
        Dimension rdim = proxy(Dimension.class, Collections.singletonMap("getName", "Store"));
        Hierarchy rhier =
                proxy(Hierarchy.class, mapOf("getName", "Store", "getUniqueName", "[Store]", "getDimension", rdim));
        Level rlvl = proxy(Level.class, mapOf("getName", "City", "getUniqueName", "[Store].[City]"));

        List<Position> rowPositions = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            // diverse captions — avoid collapsing to a single dictionary entry
            String caption = "City_" + i;
            String uniq = "[Store].[City].[" + caption + "]";
            Member m = member(caption, uniq, rdim, rhier, rlvl);
            rowPositions.add(pos(Collections.singletonList(m)));
        }
        Map<String, Object> rowAxisAnswers = new HashMap<>();
        rowAxisAnswers.put("getAxisOrdinal", Axis.ROWS);
        rowAxisAnswers.put("getPositions", rowPositions);
        rowAxisAnswers.put("getPositionCount", rows);
        CellSetAxis rowAxis = proxy(CellSetAxis.class, rowAxisAnswers);

        Dimension mdim = proxy(Dimension.class, Collections.singletonMap("getName", "Measures"));
        Hierarchy mhier = proxy(
                Hierarchy.class, mapOf("getName", "Measures", "getUniqueName", "[Measures]", "getDimension", mdim));
        Level mlvl =
                proxy(Level.class, mapOf("getName", "MeasuresLevel", "getUniqueName", "[Measures].[MeasuresLevel]"));

        List<Position> colPositions = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            String name = "Measure_" + c;
            String uniq = "[Measures].[" + name + "]";
            Member m = member(name, uniq, mdim, mhier, mlvl);
            colPositions.add(pos(Collections.singletonList(m)));
        }
        Map<String, Object> colAxisAnswers = new HashMap<>();
        colAxisAnswers.put("getAxisOrdinal", Axis.COLUMNS);
        colAxisAnswers.put("getPositions", colPositions);
        colAxisAnswers.put("getPositionCount", cols);
        CellSetAxis colAxis = proxy(CellSetAxis.class, colAxisAnswers);

        InvocationHandler handler = new InvocationHandler() {
            final List<CellSetAxis> axes = Arrays.asList(colAxis, rowAxis);

            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "getAxes":
                        return axes;
                    case "getCell":
                        if (a != null && a.length == 1 && a[0] instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Integer> coord = (List<Integer>) a[0];
                            int c = coord.get(0);
                            int r = coord.get(1);
                            return cellFor(r, c);
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
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == double.class) return 0d;
                        if (rt.isPrimitive()) return 0;
                        return null;
                }
            }
        };
        return (CellSet) Proxy.newProxyInstance(
                ArrowVsJsonBenchmark.class.getClassLoader(), new Class<?>[] {CellSet.class}, handler);
    }

    private static Cell cellFor(int row, int col) {
        double v = ((row + 1) * 7.0) + col * 0.5;
        Map<String, Object> answers = new HashMap<>();
        answers.put("isEmpty", Boolean.FALSE);
        answers.put("isNull", Boolean.FALSE);
        answers.put("getValue", v);
        answers.put("getDoubleValue", v);
        answers.put("getFormattedValue", String.format(Locale.ROOT, "%,.2f", v));
        return proxy(Cell.class, answers);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
