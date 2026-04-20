package org.saiku.service.schema.generate.infer;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 * Test-scope serialiser that renders a {@link DraftSchema} as canonical, pretty-printed JSON
 * suitable for golden-file comparison.
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
 */
final class DraftSchemaJson {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private DraftSchemaJson() {}

    static String toJson(DraftSchema schema) {
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

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise DraftSchema", e);
        }
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
}
