/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;

/**
 * saiku#909 — projects an {@link AiQueryRequest} to a compact, Tier-1
 * (schema-only) JSON summary suitable for an LLM prompt: selected measures,
 * row/column axes, and slicer filters. Deliberately excludes anything that
 * requires executing the query — no cell values, no aggregated results.
 *
 * <p>Respects saiku#902 PII annotations exactly like {@link AiSchema#toAgentView()}:
 * axis / filter member captions on a {@code pii=true} level are replaced with
 * the {@link #REDACTED} sentinel (the axis/filter shape — dimension, hierarchy,
 * level — is kept so the model still understands "this breaks down by X", it just
 * never sees which members). Measure and dimension/level *names* are never
 * PII-redacted here, matching {@code toAgentView()}'s own policy of keeping
 * names/uniqueNames while stripping captions and samples.
 */
public final class QueryStructureSummarizer {

    /** Sentinel written in place of any member caption on a PII-flagged level. */
    public static final String REDACTED = "[REDACTED]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QueryStructureSummarizer() {}

    public static String summarize(AiQueryRequest req, AiSchema schema) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("cube", req.getCube() != null ? req.getCube().getCubeName() : null);

        ArrayNode measures = root.putArray("measures");
        for (AiMeasureSelection m : req.getMeasures()) {
            if (m == null || m.getName() == null || m.getName().isBlank()) {
                continue;
            }
            measures.add(canonicalMeasureName(m.getName(), schema));
        }

        root.set("rows", axisArray(req.getRows(), schema));
        root.set("columns", axisArray(req.getColumns(), schema));
        root.set("filters", filterArray(req.getFilters(), schema));

        try {
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("could not serialise query structure", e);
        }
    }

    private static ArrayNode axisArray(List<AiAxisSelection> axis, AiSchema schema) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (axis == null) {
            return arr;
        }
        for (AiAxisSelection a : axis) {
            if (a == null) {
                continue;
            }
            ObjectNode n = arr.addObject();
            n.put("dimension", a.getDimension());
            n.put("hierarchy", a.getHierarchy());
            n.put("level", a.getLevel());
            boolean pii = isPii(a.getDimension(), a.getHierarchy(), a.getLevel(), schema);
            addMembers(n, a.getMembers(), pii);
        }
        return arr;
    }

    private static ArrayNode filterArray(List<AiFilterSelection> filters, AiSchema schema) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (filters == null) {
            return arr;
        }
        for (AiFilterSelection f : filters) {
            if (f == null) {
                continue;
            }
            ObjectNode n = arr.addObject();
            n.put("dimension", f.getDimension());
            n.put("hierarchy", f.getHierarchy());
            n.put("level", f.getLevel());
            n.put("op", f.getOp());
            boolean pii = isPii(f.getDimension(), f.getHierarchy(), f.getLevel(), schema);
            addMembers(n, f.getMembers(), pii);
            if (f.getValue() != null && !f.getValue().isBlank()) {
                n.put("relative", f.getValue());
            }
        }
        return arr;
    }

    private static void addMembers(ObjectNode n, List<String> members, boolean pii) {
        if (members == null || members.isEmpty()) {
            return;
        }
        ArrayNode arr = n.putArray("members");
        if (pii) {
            arr.add(REDACTED);
            return;
        }
        for (String m : members) {
            arr.add(m);
        }
    }

    private static String canonicalMeasureName(String requested, AiSchema schema) {
        if (schema == null) {
            return requested;
        }
        AiSchema.Measure m = schema.measures.get(AiSchema.key(requested));
        if (m == null) {
            String canonKey = schema.measureAliases.get(AiSchema.key(requested));
            if (canonKey != null) {
                m = schema.measures.get(canonKey);
            }
        }
        return m != null && m.name != null && !m.name.isBlank() ? m.name : requested;
    }

    private static boolean isPii(String dimension, String hierarchy, String level, AiSchema schema) {
        AiSchema.Level resolved = resolveLevel(dimension, hierarchy, level, schema);
        return resolved != null && resolved.pii;
    }

    private static AiSchema.Level resolveLevel(String dimension, String hierarchy, String level, AiSchema schema) {
        if (schema == null || dimension == null || level == null) {
            return null;
        }
        AiSchema.Dimension dim = schema.dimensions.get(AiSchema.key(dimension));
        if (dim == null) {
            return null;
        }
        AiSchema.Hierarchy hier = null;
        if (hierarchy != null && !hierarchy.isBlank()) {
            hier = dim.hierarchies.get(AiSchema.key(hierarchy));
        }
        if (hier == null && dim.hierarchies.size() == 1) {
            hier = dim.hierarchies.values().iterator().next();
        }
        if (hier != null) {
            return hier.levels.get(AiSchema.key(level));
        }
        // Hierarchy unresolved/ambiguous — fall back to scanning every hierarchy on the
        // dimension for a level with this name, same as the axis/filter converter does.
        for (AiSchema.Hierarchy h : dim.hierarchies.values()) {
            AiSchema.Level lvl = h.levels.get(AiSchema.key(level));
            if (lvl != null) {
                return lvl;
            }
        }
        return null;
    }
}
