package org.saiku.service.schema.generate.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftJoin;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;

/**
 * Canonical, pretty-printed JSON serialiser for {@link DraftSchema}. Originally introduced as a
 * test helper for golden-file comparison (Task A8); promoted to main scope for Task E1 so
 * production save paths can persist generation sidecars alongside the Mondrian XML.
 *
 * <p>Deterministic output rules:
 *
 * <ul>
 *   <li>Keys alphabetically sorted — every {@code ObjectNode} builder below emits fields in
 *       strict alphabetical order. Jackson's {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}
 *       only sorts {@code Map}s, not {@code ObjectNode}s, so the ordering is enforced at
 *       construction time.
 *   <li>Array element order mirrors the draft's insertion order — do not reorder dimensions,
 *       measures, levels etc.; that order carries information about the inference flow.
 *   <li>Pretty printed with 2-space indent (Jackson default) and a trailing newline.
 *   <li>{@link Provenance} rendered as a nested object with keys
 *       {@code confidence}, {@code ruleId}, {@code source}.
 *   <li>Null-valued scalar fields are omitted for readability rather than emitted as
 *       {@code null}. Presence of a field therefore carries meaning; absence does not.
 * </ul>
 *
 * <p>{@link #fromJson(JsonNode)} reconstructs a {@link DraftSchema} from canonical JSON, used by
 * the sidecar reader. The reader tolerates missing scalar fields (they were omitted on write) and
 * assumes the structural keys ({@code cubes}, {@code hierarchies}, {@code levels}, {@code
 * measures}, {@code sharedDimensions}, {@code provenance}) are always present.
 */
public final class DraftSchemaJson {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private DraftSchemaJson() {}

    // --- serialisation ----------------------------------------------------

    public static String toJson(DraftSchema schema) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toNode(schema)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise DraftSchema", e);
        }
    }

    /** Build the canonical tree without pretty-printing — used by the sidecar so the draft can be
     * embedded as a structured sub-tree rather than a string. */
    public static ObjectNode toNode(DraftSchema schema) {
        ObjectNode root = MAPPER.createObjectNode();

        ArrayNode cubes = MAPPER.createArrayNode();
        for (DraftCube c : schema.cubes()) {
            cubes.add(cubeNode(c));
        }
        root.set("cubes", cubes);

        putIfNotNull(root, "name", schema.name());

        ArrayNode shared = MAPPER.createArrayNode();
        for (DraftDimension d : schema.sharedDimensions()) {
            shared.add(dimensionNode(d));
        }
        root.set("sharedDimensions", shared);

        return root;
    }

    private static ObjectNode cubeNode(DraftCube c) {
        ObjectNode n = MAPPER.createObjectNode();
        ArrayNode dims = MAPPER.createArrayNode();
        for (DraftDimension d : c.dimensions()) {
            dims.add(dimensionNode(d));
        }
        n.set("dimensions", dims);

        ArrayNode measures = MAPPER.createArrayNode();
        for (DraftMeasure m : c.measures()) {
            measures.add(measureNode(m));
        }
        n.set("measures", measures);

        putIfNotNull(n, "name", c.name());
        n.set("provenance", provenanceNode(c.provenance()));
        putIfNotNull(n, "sourceFactTable", c.sourceFactTable());
        return n;
    }

    private static ObjectNode dimensionNode(DraftDimension d) {
        ObjectNode n = MAPPER.createObjectNode();
        putIfNotNull(n, "foreignKey", d.foreignKey());

        ArrayNode hierarchies = MAPPER.createArrayNode();
        for (DraftHierarchy h : d.hierarchies()) {
            hierarchies.add(hierarchyNode(h));
        }
        n.set("hierarchies", hierarchies);

        putIfNotNull(n, "name", d.name());
        n.set("provenance", provenanceNode(d.provenance()));
        putIfNotNull(n, "sourceTable", d.sourceTable());
        if (d.type() != null) {
            n.put("type", d.type().name());
        }
        return n;
    }

    private static ObjectNode hierarchyNode(DraftHierarchy h) {
        ObjectNode n = MAPPER.createObjectNode();
        if (h.join() != null) {
            n.set("join", joinNode(h.join()));
        }

        ArrayNode levels = MAPPER.createArrayNode();
        for (DraftLevel l : h.levels()) {
            levels.add(levelNode(l));
        }
        n.set("levels", levels);

        putIfNotNull(n, "name", h.name());
        putIfNotNull(n, "primaryKey", h.primaryKey());
        n.set("provenance", provenanceNode(h.provenance()));
        return n;
    }

    private static ObjectNode joinNode(DraftJoin j) {
        ObjectNode n = MAPPER.createObjectNode();
        putIfNotNull(n, "leftKey", j.leftKey());
        putIfNotNull(n, "leftTable", j.leftTable());
        putIfNotNull(n, "rightKey", j.rightKey());
        putIfNotNull(n, "rightTable", j.rightTable());
        return n;
    }

    private static ObjectNode levelNode(DraftLevel l) {
        ObjectNode n = MAPPER.createObjectNode();
        putIfNotNull(n, "column", l.column());
        putIfNotNull(n, "name", l.name());
        n.set("provenance", provenanceNode(l.provenance()));
        if (l.type() != null) {
            n.put("type", l.type().name());
        }
        return n;
    }

    private static ObjectNode measureNode(DraftMeasure m) {
        ObjectNode n = MAPPER.createObjectNode();
        if (m.aggregator() != null) {
            n.put("aggregator", m.aggregator().name());
        }
        putIfNotNull(n, "column", m.column());
        putIfNotNull(n, "name", m.name());
        n.set("provenance", provenanceNode(m.provenance()));
        return n;
    }

    private static ObjectNode provenanceNode(Provenance p) {
        ObjectNode n = MAPPER.createObjectNode();
        if (p == null) {
            return n;
        }
        n.put("confidence", p.confidence());
        putIfNotNull(n, "ruleId", p.ruleId());
        if (p.source() != null) {
            n.put("source", p.source().name());
        }
        return n;
    }

    private static void putIfNotNull(ObjectNode n, String key, String value) {
        if (value != null) {
            n.put(key, value);
        }
    }

    // --- deserialisation --------------------------------------------------

    /**
     * Reconstruct a {@link DraftSchema} from its canonical JSON tree. Tolerates missing scalar
     * fields ({@code toNode} omits nulls) but requires the structural keys to be present.
     */
    public static DraftSchema fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("draft node must be an object");
        }
        DraftSchema schema = new DraftSchema(textOrNull(root, "name"));
        JsonNode shared = root.get("sharedDimensions");
        if (shared != null) {
            for (JsonNode dn : shared) {
                schema.sharedDimensions().add(readDimension(dn));
            }
        }
        JsonNode cubes = root.get("cubes");
        if (cubes != null) {
            for (JsonNode cn : cubes) {
                schema.cubes().add(readCube(cn));
            }
        }
        return schema;
    }

    private static DraftCube readCube(JsonNode n) {
        DraftCube cube = new DraftCube(
                textOrNull(n, "name"), textOrNull(n, "sourceFactTable"), readProvenance(n.get("provenance")));
        JsonNode dims = n.get("dimensions");
        if (dims != null) {
            for (JsonNode dn : dims) {
                cube.dimensions().add(readDimension(dn));
            }
        }
        JsonNode measures = n.get("measures");
        if (measures != null) {
            for (JsonNode mn : measures) {
                cube.measures().add(readMeasure(mn));
            }
        }
        return cube;
    }

    private static DraftDimension readDimension(JsonNode n) {
        DraftDimension.Type type = null;
        JsonNode typeNode = n.get("type");
        if (typeNode != null && !typeNode.isNull()) {
            type = DraftDimension.Type.valueOf(typeNode.asText());
        }
        DraftDimension d = new DraftDimension(textOrNull(n, "name"), type, readProvenance(n.get("provenance")));
        d.setSourceTable(textOrNull(n, "sourceTable"));
        d.setForeignKey(textOrNull(n, "foreignKey"));
        JsonNode hiers = n.get("hierarchies");
        if (hiers != null) {
            for (JsonNode hn : hiers) {
                d.hierarchies().add(readHierarchy(hn));
            }
        }
        return d;
    }

    private static DraftHierarchy readHierarchy(JsonNode n) {
        DraftHierarchy h = new DraftHierarchy(
                textOrNull(n, "name"), textOrNull(n, "primaryKey"), readProvenance(n.get("provenance")));
        JsonNode join = n.get("join");
        if (join != null && !join.isNull()) {
            h.setJoin(new DraftJoin(
                    textOrNull(join, "leftTable"),
                    textOrNull(join, "leftKey"),
                    textOrNull(join, "rightTable"),
                    textOrNull(join, "rightKey")));
        }
        JsonNode levels = n.get("levels");
        if (levels != null) {
            for (JsonNode ln : levels) {
                h.levels().add(readLevel(ln));
            }
        }
        return h;
    }

    private static DraftLevel readLevel(JsonNode n) {
        DraftLevel.Type type = null;
        JsonNode typeNode = n.get("type");
        if (typeNode != null && !typeNode.isNull()) {
            type = DraftLevel.Type.valueOf(typeNode.asText());
        }
        return new DraftLevel(
                textOrNull(n, "name"), textOrNull(n, "column"), type, readProvenance(n.get("provenance")));
    }

    private static DraftMeasure readMeasure(JsonNode n) {
        DraftMeasure.Aggregator agg = null;
        JsonNode aggNode = n.get("aggregator");
        if (aggNode != null && !aggNode.isNull()) {
            agg = DraftMeasure.Aggregator.valueOf(aggNode.asText());
        }
        return new DraftMeasure(
                textOrNull(n, "name"), textOrNull(n, "column"), agg, readProvenance(n.get("provenance")));
    }

    private static Provenance readProvenance(JsonNode n) {
        if (n == null || !n.isObject() || n.isEmpty()) {
            return null;
        }
        JsonNode src = n.get("source");
        if (src == null || src.isNull()) {
            // source is required on Provenance; if absent, treat as no provenance at all.
            return null;
        }
        String ruleId = n.hasNonNull("ruleId") ? n.get("ruleId").asText() : "";
        double confidence = n.hasNonNull("confidence") ? n.get("confidence").asDouble() : 0.0;
        return new Provenance(Provenance.Source.valueOf(src.asText()), ruleId, confidence);
    }

    private static String textOrNull(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }
}
