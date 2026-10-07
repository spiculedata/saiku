/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License. You may
 *   obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */
package org.saiku.olap.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
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
import org.saiku.olap.util.QueryGuardrails;
import org.saiku.olap.util.formatter.CellPropertyExtractor;

/**
 * Serialises an Olap4j {@link CellSet} into an Apache Arrow IPC stream (one
 * RecordBatch per call). The schema layout matches the Phase 5 plan:
 *
 * <ul>
 *   <li>Schema metadata key {@code saiku.cellset} holds a JSON blob with
 *       {@code rowHeaderColCount}, {@code columnHeaderRows},
 *       {@code columnHeaderMembers} (caption + uniqueName + dimension per
 *       column-axis member), {@code runtimeMs}, {@code width}, {@code height},
 *       {@code mdx}, {@code queryName}, and (saiku#828) {@code cellPropertyColumns}
 *       — a sparse map of data-column index to the cell-property column suffixes
 *       present for that column.</li>
 *   <li>Row-header columns {@code r{i}_value}, {@code r{i}_uniqueName},
 *       {@code r{i}_dimension}, {@code r{i}_hierarchy}, {@code r{i}_level} —
 *       all dictionary-encoded strings.</li>
 *   <li>Data columns {@code c{j}_raw} (Float64 nullable; null for empty
 *       cells) and {@code c{j}_fmt} (dictionary-encoded string, blank where
 *       the rendered value equals the raw).</li>
 *   <li>(saiku#828) Optional per-measure cell-property columns —
 *       {@code c{j}_fmt_string}, {@code c{j}_fore_color}, {@code c{j}_back_color},
 *       {@code c{j}_font_flags}, {@code c{j}_action_type}, {@code c{j}_error} —
 *       dictionary-encoded strings sourced from {@link CellPropertyExtractor}.
 *       A column is added for measure {@code j} only when at least one cell in
 *       that column has the property populated, keeping the payload lean for
 *       cubes that don't define cell-level metadata.</li>
 * </ul>
 */
public final class ArrowCellsetWriter {

    private static final String METADATA_KEY = "saiku.cellset";

    /**
     * Optional cell-property columns surfaced per data measure (saiku#828).
     * Left entry is the {@link CellPropertyExtractor} map key; right entry is
     * the Arrow column-name suffix appended to {@code c{j}_}.
     */
    private static final String[][] CELL_PROPERTY_COLUMNS = {
        {"formatString", "fmt_string"},
        {"foreColor", "fore_color"},
        {"backColor", "back_color"},
        {"fontFlags", "font_flags"},
        {"actionType", "action_type"},
        {"error", "error"},
    };

    public void write(CellSet cellSet, ThinQuery query, OutputStream out) throws IOException {
        CellsetShape shape = CellsetShape.of(cellSet);
        CellDataMatrix data = CellDataMatrix.of(cellSet, shape);
        long started = System.currentTimeMillis();

        // saiku#1914: bounded allocator. This used to be `new RootAllocator()` —
        // Long.MAX_VALUE — so a crossjoin producing a huge cellset could allocate
        // off-heap without limit (CWE-770). The budget is saiku.olap.arrow.max.bytes
        // (default 256 MiB); exceeding it throws OutOfMemoryException from Arrow,
        // which the caller already surfaces as a query failure.
        try (BufferAllocator allocator = new RootAllocator(QueryGuardrails.arrowAllocatorBytes())) {
            AtomicLong dictIdSeq = new AtomicLong(1L);
            Map<String, Long> dictIds = new LinkedHashMap<>();
            List<Field> fields = buildFields(shape, data, dictIdSeq, dictIds);
            Schema schema = new Schema(fields, buildMetadata(shape, data, query, started));

            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
                    DictionaryProvider.MapDictionaryProvider provider =
                            new DictionaryProvider.MapDictionaryProvider()) {

                // Create dictionary vectors, one per dictionary id. We populate
                // them in fillRoot() as values are encountered.
                Map<String, DictBuilder> dicts = new HashMap<>();
                for (Map.Entry<String, Long> e : dictIds.entrySet()) {
                    String vectorName = e.getKey();
                    long id = e.getValue();
                    VarCharVector dictVec = new VarCharVector(vectorName + "_dict", allocator);
                    dictVec.allocateNew();
                    dicts.put(vectorName, new DictBuilder(id, dictVec));
                }

                try {
                    fillRoot(root, shape, data, dicts);

                    // finalise dictionary vectors and register with provider
                    for (DictBuilder db : dicts.values()) {
                        db.vector.setValueCount(db.size());
                        DictionaryEncoding enc = new DictionaryEncoding(db.id, false, new ArrowType.Int(32, true));
                        provider.put(new Dictionary(db.vector, enc));
                    }

                    try (ArrowStreamWriter writer = new ArrowStreamWriter(root, provider, Channels.newChannel(out))) {
                        writer.start();
                        writer.writeBatch();
                        writer.end();
                    }
                } finally {
                    // release dictionary buffers
                    for (DictBuilder db : dicts.values()) {
                        db.vector.close();
                    }
                }
            }
        }
    }

    // ---- schema ----------------------------------------------------------

    private static List<Field> buildFields(
            CellsetShape shape, CellDataMatrix data, AtomicLong dictIdSeq, Map<String, Long> dictIds) {
        List<Field> fields = new ArrayList<>();

        String[] rowSuffixes = {"_value", "_uniqueName", "_dimension", "_hierarchy", "_level"};
        for (int i = 0; i < shape.rowHeaderColCount; i++) {
            for (String suffix : rowSuffixes) {
                String name = "r" + i + suffix;
                fields.add(dictStringField(name, dictIdSeq, dictIds));
            }
        }
        for (int j = 0; j < shape.dataColCount; j++) {
            fields.add(new Field(
                    "c" + j + "_raw",
                    FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                    null));
            fields.add(dictStringField("c" + j + "_fmt", dictIdSeq, dictIds));

            for (int p = 0; p < CELL_PROPERTY_COLUMNS.length; p++) {
                if (data.propertyPresent[j][p]) {
                    String name = "c" + j + "_" + CELL_PROPERTY_COLUMNS[p][1];
                    fields.add(dictStringField(name, dictIdSeq, dictIds));
                }
            }
        }
        return fields;
    }

    private static Field dictStringField(String name, AtomicLong dictIdSeq, Map<String, Long> dictIds) {
        long id = dictIdSeq.getAndIncrement();
        dictIds.put(name, id);
        DictionaryEncoding enc = new DictionaryEncoding(id, false, new ArrowType.Int(32, true));
        // Indices are nullable ints; dictionary holds the strings.
        FieldType ft = new FieldType(true, new ArrowType.Int(32, true), enc);
        return new Field(name, ft, null);
    }

    private static Map<String, String> buildMetadata(
            CellsetShape shape, CellDataMatrix data, ThinQuery query, long started) {
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("rowHeaderColCount", shape.rowHeaderColCount);
        blob.put("columnHeaderRows", shape.columnHeaderRows);
        blob.put("columnHeaderMembers", shape.columnHeaderMembers);
        blob.put("runtimeMs", Math.max(0L, System.currentTimeMillis() - started));
        blob.put("width", shape.rowHeaderColCount + shape.dataColCount);
        blob.put("height", shape.rowCount);
        blob.put("mdx", query != null ? query.getMdx() : null);
        blob.put("queryName", query != null ? query.getName() : null);

        Map<String, List<String>> cellPropertyColumns = new LinkedHashMap<>();
        for (int j = 0; j < data.dataColCount; j++) {
            List<String> suffixes = new ArrayList<>();
            for (int p = 0; p < CELL_PROPERTY_COLUMNS.length; p++) {
                if (data.propertyPresent[j][p]) {
                    suffixes.add(CELL_PROPERTY_COLUMNS[p][1]);
                }
            }
            if (!suffixes.isEmpty()) {
                cellPropertyColumns.put(String.valueOf(j), suffixes);
            }
        }
        if (!cellPropertyColumns.isEmpty()) {
            blob.put("cellPropertyColumns", cellPropertyColumns);
        }

        try {
            String json = new ObjectMapper().writeValueAsString(blob);
            Map<String, String> md = new LinkedHashMap<>();
            md.put(METADATA_KEY, json);
            return md;
        } catch (Exception e) {
            throw new RuntimeException("failed to encode cellset metadata", e);
        }
    }

    // ---- body ------------------------------------------------------------

    private static void fillRoot(
            VectorSchemaRoot root, CellsetShape shape, CellDataMatrix data, Map<String, DictBuilder> dicts) {
        int rowCount = shape.rowCount;
        root.setRowCount(rowCount);

        // Row-header columns
        List<Position> rowPositions = shape.rowPositions;
        for (int rIdx = 0; rIdx < shape.rowHeaderColCount; rIdx++) {
            IntVector valueIdx = getIdxVec(root, "r" + rIdx + "_value");
            IntVector uniqIdx = getIdxVec(root, "r" + rIdx + "_uniqueName");
            IntVector dimIdx = getIdxVec(root, "r" + rIdx + "_dimension");
            IntVector hierIdx = getIdxVec(root, "r" + rIdx + "_hierarchy");
            IntVector lvlIdx = getIdxVec(root, "r" + rIdx + "_level");

            DictBuilder valueDict = dicts.get("r" + rIdx + "_value");
            DictBuilder uniqDict = dicts.get("r" + rIdx + "_uniqueName");
            DictBuilder dimDict = dicts.get("r" + rIdx + "_dimension");
            DictBuilder hierDict = dicts.get("r" + rIdx + "_hierarchy");
            DictBuilder lvlDict = dicts.get("r" + rIdx + "_level");

            for (int row = 0; row < rowCount; row++) {
                Member m = null;
                if (rowPositions != null && row < rowPositions.size()) {
                    Position pos = rowPositions.get(row);
                    List<Member> members = pos.getMembers();
                    if (members != null && rIdx < members.size()) {
                        m = members.get(rIdx);
                    }
                }
                String value = "", uniq = "", dimName = "", hierName = "", lvlName = "";
                if (m != null) {
                    value = nullToEmpty(safeCaption(m));
                    uniq = nullToEmpty(m.getUniqueName());
                    Dimension d = m.getDimension();
                    Hierarchy h = m.getHierarchy();
                    Level l = m.getLevel();
                    dimName = d != null ? nullToEmpty(d.getName()) : "";
                    hierName = h != null ? nullToEmpty(h.getUniqueName()) : "";
                    lvlName = l != null ? nullToEmpty(l.getUniqueName()) : "";
                }
                valueIdx.setSafe(row, valueDict.intern(value));
                uniqIdx.setSafe(row, uniqDict.intern(uniq));
                dimIdx.setSafe(row, dimDict.intern(dimName));
                hierIdx.setSafe(row, hierDict.intern(hierName));
                lvlIdx.setSafe(row, lvlDict.intern(lvlName));
            }
            valueIdx.setValueCount(rowCount);
            uniqIdx.setValueCount(rowCount);
            dimIdx.setValueCount(rowCount);
            hierIdx.setValueCount(rowCount);
            lvlIdx.setValueCount(rowCount);
        }

        // Data columns
        for (int cIdx = 0; cIdx < shape.dataColCount; cIdx++) {
            Float8Vector raw = (Float8Vector) root.getVector("c" + cIdx + "_raw");
            IntVector fmtIdx = getIdxVec(root, "c" + cIdx + "_fmt");
            DictBuilder fmtDict = dicts.get("c" + cIdx + "_fmt");
            raw.allocateNew(rowCount);

            for (int row = 0; row < rowCount; row++) {
                if (data.hasValue[cIdx][row]) {
                    raw.setSafe(row, data.rawVal[cIdx][row]);
                } else {
                    raw.setNull(row);
                }
                fmtIdx.setSafe(row, fmtDict.intern(data.fmt[cIdx][row]));
            }
            raw.setValueCount(rowCount);
            fmtIdx.setValueCount(rowCount);

            for (int p = 0; p < CELL_PROPERTY_COLUMNS.length; p++) {
                if (!data.propertyPresent[cIdx][p]) continue;
                String propKey = CELL_PROPERTY_COLUMNS[p][0];
                String colName = "c" + cIdx + "_" + CELL_PROPERTY_COLUMNS[p][1];
                IntVector propIdx = getIdxVec(root, colName);
                DictBuilder propDict = dicts.get(colName);
                for (int row = 0; row < rowCount; row++) {
                    String value = data.properties[cIdx][row].getOrDefault(propKey, "");
                    propIdx.setSafe(row, propDict.intern(value));
                }
                propIdx.setValueCount(rowCount);
            }
        }
    }

    private static IntVector getIdxVec(VectorSchemaRoot root, String name) {
        FieldVector v = root.getVector(name);
        if (!(v instanceof IntVector)) {
            throw new IllegalStateException("expected IntVector for " + name + " got " + v.getClass());
        }
        return (IntVector) v;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String safeCaption(Member m) {
        String c = m.getCaption();
        if (c != null && !c.isEmpty()) return c;
        return m.getName();
    }

    // ---- helpers ---------------------------------------------------------

    /** Maintains a string → int index map for dictionary-encoded columns. */
    private static final class DictBuilder {
        final long id;
        final VarCharVector vector;
        final Map<String, Integer> index = new LinkedHashMap<>();
        int next = 0;

        DictBuilder(long id, VarCharVector vector) {
            this.id = id;
            this.vector = vector;
        }

        int intern(String value) {
            String key = value == null ? "" : value;
            Integer existing = index.get(key);
            if (existing != null) return existing;
            int idx = next++;
            index.put(key, idx);
            vector.setSafe(idx, key.getBytes(StandardCharsets.UTF_8));
            return idx;
        }

        int size() {
            return next;
        }
    }

    /**
     * Captures the shape of a CellSet we need up-front to size vectors:
     * number of row-header columns, column-header rows, total data columns,
     * row count, and the raw row positions for header extraction.
     */
    static final class CellsetShape {
        final int rowHeaderColCount;
        final int dataColCount;
        final int rowCount;
        final List<List<String>> columnHeaderRows;
        /** Parallel to {@link #columnHeaderRows}: dimension / hierarchy / uniqueName
         *  per column-axis member so the SPA can substitute cell-link placeholders. */
        final List<List<Map<String, String>>> columnHeaderMembers;

        final List<Position> rowPositions;

        private CellsetShape(
                int rowHeaderColCount,
                int dataColCount,
                int rowCount,
                List<List<String>> columnHeaderRows,
                List<List<Map<String, String>>> columnHeaderMembers,
                List<Position> rowPositions) {
            this.rowHeaderColCount = rowHeaderColCount;
            this.dataColCount = dataColCount;
            this.rowCount = rowCount;
            this.columnHeaderRows = columnHeaderRows;
            this.columnHeaderMembers = columnHeaderMembers;
            this.rowPositions = rowPositions;
        }

        static CellsetShape of(CellSet cellSet) {
            CellSetAxis colAxis = null;
            CellSetAxis rowAxis = null;
            if (cellSet != null && cellSet.getAxes() != null) {
                for (CellSetAxis a : cellSet.getAxes()) {
                    Axis ord = a.getAxisOrdinal();
                    if (ord == Axis.COLUMNS) colAxis = a;
                    else if (ord == Axis.ROWS) rowAxis = a;
                }
            }

            int dataColCount = colAxis != null ? colAxis.getPositionCount() : 0;
            int rowCount = rowAxis != null ? rowAxis.getPositionCount() : 0;

            int rowHeaderDepth = 1;
            List<Position> rowPositions = null;
            if (rowAxis != null) {
                rowPositions = rowAxis.getPositions();
                if (rowPositions != null && !rowPositions.isEmpty()) {
                    List<Member> members = rowPositions.get(0).getMembers();
                    rowHeaderDepth = members == null || members.isEmpty() ? 1 : members.size();
                }
            }

            // Column-header rows: one row per hierarchy on the column axis.
            List<List<String>> colHeaderRows = new ArrayList<>();
            List<List<Map<String, String>>> colHeaderMembers = new ArrayList<>();
            int colHeaderDepth = 1;
            List<Position> colPositions = colAxis != null ? colAxis.getPositions() : null;
            if (colPositions != null && !colPositions.isEmpty()) {
                List<Member> first = colPositions.get(0).getMembers();
                colHeaderDepth = first == null || first.isEmpty() ? 1 : first.size();
            }
            for (int depth = 0; depth < colHeaderDepth; depth++) {
                List<String> header = new ArrayList<>();
                List<Map<String, String>> members = new ArrayList<>();
                if (colPositions != null) {
                    for (Position p : colPositions) {
                        List<Member> ms = p.getMembers();
                        if (ms != null && depth < ms.size()) {
                            Member m = ms.get(depth);
                            String caption = m.getCaption() != null ? m.getCaption() : m.getName();
                            header.add(caption);
                            members.add(memberMeta(m, caption));
                        } else {
                            header.add("");
                            members.add(new LinkedHashMap<>());
                        }
                    }
                }
                colHeaderRows.add(header);
                colHeaderMembers.add(members);
            }
            if (colHeaderRows.isEmpty()) {
                colHeaderRows.add(new ArrayList<>());
                colHeaderMembers.add(new ArrayList<>());
            }

            return new CellsetShape(
                    rowHeaderDepth, dataColCount, rowCount, colHeaderRows, colHeaderMembers, rowPositions);
        }

        private static Map<String, String> memberMeta(Member m, String caption) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("caption", caption == null ? "" : caption);
            if (m.getUniqueName() != null) {
                meta.put("uniqueName", m.getUniqueName());
            }
            Dimension d = m.getDimension();
            if (d != null && d.getName() != null) {
                meta.put("dimension", d.getName());
            }
            Hierarchy h = m.getHierarchy();
            if (h != null && h.getUniqueName() != null) {
                meta.put("hierarchy", h.getUniqueName());
            }
            Level l = m.getLevel();
            if (l != null && l.getUniqueName() != null) {
                meta.put("level", l.getUniqueName());
            }
            return meta;
        }
    }

    /**
     * Pre-extracted per-cell data for every (dataColumn, row) coordinate —
     * raw numeric value, rendered format string, and (saiku#828) the
     * olap4j {@code StandardCellProperty} map from {@link CellPropertyExtractor}.
     * Computed once up-front so that (a) the Arrow schema can decide which
     * optional cell-property columns to add before any vectors are
     * allocated, and (b) {@link #fillRoot} never re-touches the olap4j
     * {@link Cell} API.
     */
    static final class CellDataMatrix {
        final int dataColCount;
        final int rowCount;
        final boolean[][] hasValue;
        final double[][] rawVal;
        final String[][] fmt;
        final Map<String, String>[][] properties;
        /** [dataColumn][propertyIndex into {@link #CELL_PROPERTY_COLUMNS}]. */
        final boolean[][] propertyPresent;

        private CellDataMatrix(
                int dataColCount,
                int rowCount,
                boolean[][] hasValue,
                double[][] rawVal,
                String[][] fmt,
                Map<String, String>[][] properties,
                boolean[][] propertyPresent) {
            this.dataColCount = dataColCount;
            this.rowCount = rowCount;
            this.hasValue = hasValue;
            this.rawVal = rawVal;
            this.fmt = fmt;
            this.properties = properties;
            this.propertyPresent = propertyPresent;
        }

        @SuppressWarnings("unchecked")
        static CellDataMatrix of(CellSet cellSet, CellsetShape shape) {
            int dataColCount = shape.dataColCount;
            int rowCount = shape.rowCount;
            boolean[][] hasValue = new boolean[dataColCount][rowCount];
            double[][] rawVal = new double[dataColCount][rowCount];
            String[][] fmt = new String[dataColCount][rowCount];
            Map<String, String>[][] properties = new Map[dataColCount][rowCount];
            boolean[][] propertyPresent = new boolean[dataColCount][CELL_PROPERTY_COLUMNS.length];

            for (int col = 0; col < dataColCount; col++) {
                for (int row = 0; row < rowCount; row++) {
                    Cell cell = cellSet.getCell(Arrays.asList(col, row));
                    boolean has = false;
                    double v = 0d;
                    String rendered = "";
                    if (cell != null && !cell.isEmpty() && !cell.isNull()) {
                        Object val = cell.getValue();
                        if (val instanceof Number) {
                            v = ((Number) val).doubleValue();
                            has = true;
                        }
                        String rv = cell.getFormattedValue();
                        if (rv != null) {
                            if (!has) {
                                rendered = rv;
                            } else if (!rv.equals(String.valueOf(v)) && !rv.equals(Double.toString(v))) {
                                rendered = rv;
                            }
                        }
                    }
                    hasValue[col][row] = has;
                    rawVal[col][row] = v;
                    fmt[col][row] = rendered;

                    Map<String, String> props = CellPropertyExtractor.extract(cell);
                    properties[col][row] = props;
                    for (int p = 0; p < CELL_PROPERTY_COLUMNS.length; p++) {
                        String propVal = props.get(CELL_PROPERTY_COLUMNS[p][0]);
                        if (propVal != null && !propVal.isEmpty()) {
                            propertyPresent[col][p] = true;
                        }
                    }
                }
            }
            return new CellDataMatrix(dataColCount, rowCount, hasValue, rawVal, fmt, properties, propertyPresent);
        }
    }
}
