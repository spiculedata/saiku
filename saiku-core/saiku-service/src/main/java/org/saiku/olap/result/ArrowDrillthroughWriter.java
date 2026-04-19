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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
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

/**
 * Serialises a drillthrough {@link ResultSet} into an Apache Arrow IPC stream
 * (one RecordBatch per call). Drillthrough is a flat fact table — unlike a
 * CellSet, there's no row/column header hierarchy, just a wide flat grid.
 *
 * <p>Schema layout:
 * <ul>
 *   <li>Schema metadata key {@code saiku.drillthrough} holds a JSON blob with
 *       {@code captions} (pretty column captions used for display),
 *       {@code rowCount}, {@code runtimeMs}.</li>
 *   <li>One field per JDBC column, named after the JDBC column name:
 *       numeric JDBC types collapse to nullable Float64 (we don't try to
 *       preserve scale — drillthrough output is for display, not
 *       computation);
 *       DATE/TIMESTAMP are emitted as ISO-8601 strings via {@code toString()}
 *       on the JDBC value (simpler and more reliable across drivers than
 *       trying to round-trip a Timestamp{Milli});
 *       everything else becomes a dictionary-encoded UTF-8 string.</li>
 * </ul>
 *
 * <p>Deliberately does NOT reuse Phase 5 Task 5's query cache. Drillthrough
 * keys differ from execute keys (they depend on cell position + maxrows) and
 * the invalidation semantics haven't been worked out. See Query2Resource for
 * the TODO.
 */
public final class ArrowDrillthroughWriter {

    private static final String METADATA_KEY = "saiku.drillthrough";

    /**
     * Write {@code rs} as Arrow IPC to {@code out}. The {@code captions}
     * array, if supplied, is passed through in schema metadata; callers that
     * only have JDBC column names may pass {@code null}.
     */
    public WriteResult write(ResultSet rs, String[] captions, OutputStream out) throws IOException {
        long started = System.currentTimeMillis();
        try {
            return writeInternal(rs, captions, out, started);
        } catch (SQLException e) {
            throw new IOException("Failed to read drillthrough ResultSet", e);
        }
    }

    public static final class WriteResult {
        public final int rowCount;
        public final long runtimeMs;

        WriteResult(int rowCount, long runtimeMs) {
            this.rowCount = rowCount;
            this.runtimeMs = runtimeMs;
        }
    }

    private WriteResult writeInternal(ResultSet rs, String[] captions, OutputStream out, long started)
            throws IOException, SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();

        ColumnSpec[] specs = new ColumnSpec[colCount];
        List<Field> fields = new ArrayList<>(colCount);
        // dictIds[i] is non-zero for dictionary-encoded string columns.
        long[] dictIds = new long[colCount];
        long nextDictId = 1L;

        for (int i = 0; i < colCount; i++) {
            int jdbcType = md.getColumnType(i + 1);
            String name = md.getColumnLabel(i + 1);
            if (name == null || name.isEmpty()) name = md.getColumnName(i + 1);
            if (name == null) name = "col" + i;

            ColumnKind kind = classify(jdbcType);
            specs[i] = new ColumnSpec(name, kind, jdbcType);

            switch (kind) {
                case NUMERIC:
                    fields.add(new Field(
                            name,
                            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                            null));
                    break;
                case STRING:
                default:
                    long id = nextDictId++;
                    dictIds[i] = id;
                    DictionaryEncoding enc = new DictionaryEncoding(id, false, new ArrowType.Int(32, true));
                    FieldType ft = new FieldType(true, new ArrowType.Int(32, true), enc);
                    fields.add(new Field(name, ft, null));
                    break;
            }
        }

        // We need the row count for schema metadata, but metadata is written
        // at stream start. Materialise rows into per-column buffers first,
        // then emit the stream with a fully-populated metadata blob.
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[colCount];
            for (int i = 0; i < colCount; i++) {
                row[i] = readCell(rs, i + 1, specs[i]);
            }
            rows.add(row);
        }
        int rowCount = rows.size();
        long runtimeMs = Math.max(0L, System.currentTimeMillis() - started);

        String[] outCaptions = captions != null && captions.length == colCount ? captions : fallbackCaptions(specs);
        Schema schema = new Schema(fields, buildMetadata(outCaptions, rowCount, runtimeMs));

        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
                DictionaryProvider.MapDictionaryProvider provider = new DictionaryProvider.MapDictionaryProvider()) {
            root.setRowCount(rowCount);

            Map<Integer, DictBuilder> dicts = new HashMap<>();
            for (int i = 0; i < colCount; i++) {
                if (dictIds[i] != 0L) {
                    VarCharVector dictVec = new VarCharVector(specs[i].name + "_dict", allocator);
                    dictVec.allocateNew();
                    dicts.put(i, new DictBuilder(dictIds[i], dictVec));
                }
            }

            try {
                for (int i = 0; i < colCount; i++) {
                    ColumnSpec spec = specs[i];
                    if (spec.kind == ColumnKind.NUMERIC) {
                        Float8Vector vec = (Float8Vector) root.getVector(spec.name);
                        vec.allocateNew(rowCount);
                        for (int r = 0; r < rowCount; r++) {
                            Object v = rows.get(r)[i];
                            if (v == null) {
                                vec.setNull(r);
                            } else {
                                vec.setSafe(r, ((Number) v).doubleValue());
                            }
                        }
                        vec.setValueCount(rowCount);
                    } else {
                        IntVector vec = (IntVector) root.getVector(spec.name);
                        DictBuilder db = dicts.get(i);
                        for (int r = 0; r < rowCount; r++) {
                            Object v = rows.get(r)[i];
                            String s = v == null ? "" : String.valueOf(v);
                            vec.setSafe(r, db.intern(s));
                        }
                        vec.setValueCount(rowCount);
                    }
                }

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
                for (DictBuilder db : dicts.values()) {
                    db.vector.close();
                }
            }
        }

        return new WriteResult(rowCount, runtimeMs);
    }

    // --- helpers ----------------------------------------------------------

    private static Object readCell(ResultSet rs, int idx, ColumnSpec spec) throws SQLException {
        if (spec.kind == ColumnKind.NUMERIC) {
            double v = rs.getDouble(idx);
            if (rs.wasNull()) return null;
            return v;
        }
        // Strings, dates, timestamps all collapse to a string here.
        Object v = rs.getObject(idx);
        if (v == null) return null;
        // Date/Timestamp#toString() yields ISO-8601-ish output that's
        // perfectly adequate for display. We picked the simple option
        // over TimestampMilli because several JDBC drivers return
        // zoneless timestamps and dates, and round-tripping them through
        // epoch millis has been a source of off-by-a-timezone bugs.
        return v.toString();
    }

    private static ColumnKind classify(int jdbcType) {
        switch (jdbcType) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.DECIMAL:
            case Types.NUMERIC:
                return ColumnKind.NUMERIC;
            default:
                return ColumnKind.STRING;
        }
    }

    private static String[] fallbackCaptions(ColumnSpec[] specs) {
        String[] out = new String[specs.length];
        for (int i = 0; i < specs.length; i++) out[i] = specs[i].name;
        return out;
    }

    private static Map<String, String> buildMetadata(String[] captions, int rowCount, long runtimeMs) {
        Map<String, Object> blob = new LinkedHashMap<>();
        List<String> captionList = new ArrayList<>(captions.length);
        for (String c : captions) captionList.add(c == null ? "" : c);
        blob.put("captions", captionList);
        blob.put("rowCount", rowCount);
        blob.put("runtimeMs", runtimeMs);
        try {
            String json = new ObjectMapper().writeValueAsString(blob);
            Map<String, String> md = new LinkedHashMap<>();
            md.put(METADATA_KEY, json);
            return md;
        } catch (Exception e) {
            throw new RuntimeException("failed to encode drillthrough metadata", e);
        }
    }

    private enum ColumnKind {
        NUMERIC,
        STRING
    }

    private static final class ColumnSpec {
        final String name;
        final ColumnKind kind;
        final int jdbcType;

        ColumnSpec(String name, ColumnKind kind, int jdbcType) {
            this.name = name;
            this.kind = kind;
            this.jdbcType = jdbcType;
        }
    }

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
}
