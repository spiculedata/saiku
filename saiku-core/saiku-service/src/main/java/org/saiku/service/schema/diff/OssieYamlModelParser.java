/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses an Apache Ossie semantic model YAML into a {@link ModelSnapshot} (saiku#1434).
 *
 * <p>Both envelope shapes Saiku ships are handled: a top-level {@code semantic_model:} (the
 * TPC-DS fixture) and the {@code ontology_mappings: [{ semantic_model: … }]} list (the Flights
 * fixture). Ossie datasets become dimensions, fields become levels, metrics become measures, and
 * the enclosing semantic model becomes the cube — see {@link ModelElementKind} for why.
 *
 * <p>Parse failures raise {@link ModelDiffException.Reason#MALFORMED} rather than returning an
 * empty snapshot.
 */
public final class OssieYamlModelParser implements ModelParser {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Override
    public ModelFormat format() {
        return ModelFormat.OSSIE_YAML;
    }

    @Override
    public ModelSnapshot parse(String content, String sourceName) {
        if (content == null || content.isBlank()) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED,
                    describe(sourceName) + " is empty — expected an Ossie YAML model");
        }
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (IOException e) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED,
                    describe(sourceName) + " is not well-formed YAML (" + e.getMessage() + ")",
                    e);
        }
        if (root == null || !root.isObject()) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED,
                    describe(sourceName) + " is not an Ossie model (expected a YAML mapping at the top level)");
        }

        List<JsonNode> models = new ArrayList<>();
        // The block is a MAPPING in one fixture and a LIST in another (Ossie permits several
        // semantic models per document), so both nestings are accepted. Getting this wrong reads
        // a shipped model as having no models at all.
        collectModels(root.get("semantic_model"), models);
        for (JsonNode mapping : root.path("ontology_mappings")) {
            collectModels(mapping.get("semantic_model"), models);
        }
        if (models.isEmpty()) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED,
                    describe(sourceName) + " has no semantic_model block — nothing to diff");
        }

        List<ModelElement> out = new ArrayList<>();
        // A multi-model document (the ontology_mappings list) is named after the document
        // itself, so the diff header is meaningful when there is more than one model inside; a
        // single-model document takes the model's own name, which is what the fixtures carry.
        String documentName = text(root, "name");
        for (JsonNode model : models) {
            readModel(documentName, model, out);
        }
        String snapshotName = documentName;
        if ((snapshotName == null || snapshotName.isBlank()) && models.size() == 1) {
            snapshotName = text(models.get(0), "name");
        }
        return new ModelSnapshot(ModelFormat.OSSIE_YAML, snapshotName, out);
    }

    private static void collectModels(JsonNode node, List<JsonNode> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            out.add(node);
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (child.isObject()) {
                    out.add(child);
                }
            }
        }
    }

    private void readModel(String documentName, JsonNode model, List<ModelElement> out) {
        String cubeName = text(model, "name");
        if (cubeName == null || cubeName.isBlank()) {
            cubeName = documentName;
        }
        if (cubeName == null || cubeName.isBlank()) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED, "an Ossie semantic_model block has no name");
        }
        out.add(new ModelElement(ModelElementKind.CUBE, cubeName, null, cubeName, ""));

        for (JsonNode dataset : model.path("datasets")) {
            String datasetName = named(dataset, "dataset", model);
            out.add(new ModelElement(
                    ModelElementKind.DIMENSION,
                    cubeName,
                    null,
                    datasetName,
                    signature(dataset, "source", "primary_key"),
                    text(dataset, "label")));
            for (JsonNode field : dataset.path("fields")) {
                // A field's label is what an operator sees; the name is what a query binds to, so
                // the signature covers the label and the expression shape, not the field name.
                out.add(new ModelElement(
                        ModelElementKind.LEVEL,
                        cubeName,
                        datasetName,
                        named(field, "field", model),
                        signature(field, "label", "expression", "type", "description"),
                        text(field, "label")));
            }
        }

        for (JsonNode metric : model.path("metrics")) {
            out.add(new ModelElement(
                    ModelElementKind.MEASURE,
                    cubeName,
                    null,
                    named(metric, "metric", model),
                    signature(metric, "aggregation_kind", "expression", "description"),
                    text(metric, "label")));
        }
    }

    /**
     * The {@code name} of a named entry, or a rejection.
     *
     * <p>An entry with no name is a half-written file, not an element to skip: skipping it would
     * make a truncated model diff to "nothing changed", which is the one answer this feature must
     * never give.
     */
    private static String named(JsonNode entry, String what, JsonNode model) {
        if (entry == null || !entry.isObject() || text(entry, "name") == null) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED,
                    "an Ossie semantic_model has a " + what + " entry with no name (in model '" + text(model, "name")
                            + "') — the model is truncated or malformed");
        }
        return text(entry, "name");
    }

    /**
     * Fingerprint the named fields of a node as a lower-cased {@code key=value} string with the
     * keys sorted. Used for rename detection, so it must be stable across a pure rename and
     * sensitive to a real definition change.
     */
    private static String signature(JsonNode node, String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(field.toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(value.isTextual() ? value.asText() : value.toString());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static String describe(String sourceName) {
        return sourceName == null || sourceName.isBlank() ? "the model payload" : "'" + sourceName + "'";
    }
}
