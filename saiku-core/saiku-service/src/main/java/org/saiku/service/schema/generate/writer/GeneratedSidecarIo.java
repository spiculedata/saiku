package org.saiku.service.schema.generate.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Build / write / read helpers for {@link GeneratedSidecar}. The sidecar JSON is hand-assembled at
 * the top level so we can embed the canonical draft tree from {@link DraftSchemaJson} unchanged
 * (key ordering + null-omission rules matter for golden diffs). Op-log elements round-trip via
 * Jackson polymorphic deserialisation — the {@code op} discriminator is already wired on
 * {@link SuggestionOp}.
 *
 * <p>{@link Instant} is serialised as an ISO-8601 string rather than registering
 * {@code jackson-datatype-jsr310} — the module isn't currently on the service classpath and the
 * one-liner below is cheaper than pulling it in.
 */
public final class GeneratedSidecarIo {

    /** Separate mapper from {@link DraftSchemaJson}'s so features stay independent. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private GeneratedSidecarIo() {}

    /** Convenience builder — callers supply the parts; we stamp {@code generatedAt} from the clock. */
    public static GeneratedSidecar build(
            DraftSchema draft, List<SuggestionOp> opLog, String schemaName, String saikuVersion) {
        return build(draft, opLog, schemaName, Instant.now(), saikuVersion);
    }

    public static GeneratedSidecar build(
            DraftSchema draft, List<SuggestionOp> opLog, String schemaName, Instant generatedAt, String saikuVersion) {
        return new GeneratedSidecar(schemaName, generatedAt, saikuVersion, draft, opLog);
    }

    public static String write(GeneratedSidecar sidecar) {
        if (sidecar == null) {
            throw new IllegalArgumentException("sidecar is null");
        }
        ObjectNode root = MAPPER.createObjectNode();
        // Keys alphabetical: draft, generatedAt, opLog, saikuVersion, schemaName
        root.set("draft", sidecar.draft() == null ? MAPPER.nullNode() : DraftSchemaJson.toNode(sidecar.draft()));
        if (sidecar.generatedAt() != null) {
            root.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(sidecar.generatedAt()));
        }
        ArrayNode opLog = MAPPER.createArrayNode();
        for (SuggestionOp op : sidecar.opLog()) {
            opLog.add(MAPPER.valueToTree(op));
        }
        root.set("opLog", opLog);
        if (sidecar.saikuVersion() != null) {
            root.put("saikuVersion", sidecar.saikuVersion());
        }
        if (sidecar.schemaName() != null) {
            root.put("schemaName", sidecar.schemaName());
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise GeneratedSidecar", e);
        }
    }

    public static GeneratedSidecar read(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json is null");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                throw new IllegalArgumentException("sidecar root must be an object");
            }
            String schemaName =
                    root.hasNonNull("schemaName") ? root.get("schemaName").asText() : null;
            Instant generatedAt = root.hasNonNull("generatedAt")
                    ? Instant.parse(root.get("generatedAt").asText())
                    : null;
            String saikuVersion =
                    root.hasNonNull("saikuVersion") ? root.get("saikuVersion").asText() : null;
            DraftSchema draft = root.has("draft") && !root.get("draft").isNull()
                    ? DraftSchemaJson.fromJson(root.get("draft"))
                    : null;

            List<SuggestionOp> opLog = new ArrayList<>();
            JsonNode opLogNode = root.get("opLog");
            if (opLogNode != null && opLogNode.isArray()) {
                // Round-trip each op via the mapper so @JsonTypeInfo discriminator resolves the concrete subtype.
                opLog = MAPPER.convertValue(opLogNode, new TypeReference<List<SuggestionOp>>() {});
            }
            return new GeneratedSidecar(schemaName, generatedAt, saikuVersion, draft, opLog);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse GeneratedSidecar JSON", e);
        }
    }
}
