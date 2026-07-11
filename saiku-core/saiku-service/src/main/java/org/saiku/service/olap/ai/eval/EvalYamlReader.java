/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * Loads an {@link EvalSuite} from its on-disk YAML form (saiku#1424).
 *
 * <p>Every failure surfaces as {@link EvalParseException} with a stable {@link
 * EvalParseException#code()} so the report generator can distinguish "author wrote a broken YAML"
 * from "the ask surface produced a wrong answer" — the two shouldn't be conflated in CI.
 *
 * <p>On-disk shape (spec lives at {@code docs/EVAL-SPEC.md}):
 *
 * <pre>{@code
 * name: foodmart-sales-evals
 * description: Ground-truth cases for FoodMart Sales
 * cube:
 *   connectionName: unknown_foodmart
 *   catalog: FoodMart
 *   schema: FoodMart
 *   cubeName: Sales
 * cases:
 *   - name: sales-by-country
 *     question: "show store sales by country"
 *     expectedRows:
 *       - {country: "USA", storeSales: 565238.13}
 *       - {country: "Canada", storeSales: 79063.11}
 *     tolerance: {relative: 0.001}
 *     orderMatters: true
 *   - name: refuse-off-topic
 *     question: "What's the weather?"
 *     expectedIntent: REFUSED
 *     expectedRefusalContains: cube
 * }</pre>
 */
public final class EvalYamlReader {

    private static final YAMLMapper YAML = new YAMLMapper();

    private EvalYamlReader() {}

    public static EvalSuite read(Reader reader, String source) throws EvalParseException {
        JsonNode node;
        try {
            node = YAML.readTree(reader);
        } catch (IOException e) {
            throw new EvalParseException("MALFORMED_YAML", source, "not valid YAML: " + e.getMessage());
        }
        return read(node, source);
    }

    public static EvalSuite read(String yaml, String source) throws EvalParseException {
        JsonNode node;
        try {
            node = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new EvalParseException("MALFORMED_YAML", source, "not valid YAML: " + e.getMessage());
        }
        return read(node, source);
    }

    private static EvalSuite read(JsonNode node, String source) throws EvalParseException {
        if (node == null || !node.isObject()) {
            throw new EvalParseException("MALFORMED_YAML", source, "top-level must be a YAML mapping");
        }
        String name = requireStringField(node, "name", source);
        String description = optionalStringField(node, "description");
        AiCubeRef cube = readCubeRef(node, source);

        List<EvalCase> cases = new ArrayList<>();
        JsonNode casesNode = node.get("cases");
        if (casesNode == null || !casesNode.isArray()) {
            throw new EvalParseException("MISSING_FIELD", source, "'cases' must be a non-empty list");
        }
        int idx = 0;
        for (JsonNode c : casesNode) {
            cases.add(readCase(c, source, idx));
            idx++;
        }
        if (cases.isEmpty()) {
            throw new EvalParseException("MISSING_FIELD", source, "'cases' must be a non-empty list");
        }
        return new EvalSuite(name, description, cube, cases);
    }

    private static AiCubeRef readCubeRef(JsonNode root, String source) throws EvalParseException {
        JsonNode cubeNode = root.get("cube");
        if (cubeNode == null || !cubeNode.isObject()) {
            throw new EvalParseException(
                    "MISSING_FIELD", source, "'cube' must be an object with connectionName/catalog/schema/cubeName");
        }
        return new AiCubeRef(
                requireStringField(cubeNode, "connectionName", source),
                requireStringField(cubeNode, "catalog", source),
                requireStringField(cubeNode, "schema", source),
                requireStringField(cubeNode, "cubeName", source));
    }

    private static EvalCase readCase(JsonNode node, String source, int idx) throws EvalParseException {
        if (node == null || !node.isObject()) {
            throw new EvalParseException("MALFORMED_YAML", source, "cases[" + idx + "] must be a mapping");
        }
        String name = requireStringField(node, "name", source);
        String question = requireStringField(node, "question", source);
        String expectedIntent = optionalStringField(node, "expectedIntent");
        String expectedRefusalContains = optionalStringField(node, "expectedRefusalContains");
        boolean orderMatters =
                node.has("orderMatters") ? node.get("orderMatters").asBoolean(true) : true;

        List<Map<String, String>> history = readHistory(node.get("history"), source, idx);
        List<Map<String, Object>> expectedRows = readRows(node.get("expectedRows"), source, idx);
        List<String> expectedInsightContains = readStringArray(node.get("expectedInsightContains"), source, idx);
        EvalTolerance tolerance = readTolerance(node.get("tolerance"), source, idx);

        return new EvalCase(
                name,
                question,
                history,
                expectedIntent,
                expectedRefusalContains,
                expectedRows,
                orderMatters,
                expectedInsightContains,
                tolerance);
    }

    private static List<Map<String, String>> readHistory(JsonNode h, String source, int idx) throws EvalParseException {
        if (h == null || h.isNull()) return List.of();
        if (!h.isArray()) {
            throw new EvalParseException("TYPE_MISMATCH", source, "cases[" + idx + "].history must be an array");
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode entry : h) {
            if (!entry.isObject()) {
                throw new EvalParseException(
                        "TYPE_MISMATCH", source, "cases[" + idx + "].history[] entries must be mappings");
            }
            Map<String, String> m = new LinkedHashMap<>();
            entry.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().asText()));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> readRows(JsonNode r, String source, int idx) throws EvalParseException {
        if (r == null || r.isNull()) return null;
        if (!r.isArray()) {
            throw new EvalParseException("TYPE_MISMATCH", source, "cases[" + idx + "].expectedRows must be an array");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode row : r) {
            if (!row.isObject()) {
                throw new EvalParseException(
                        "TYPE_MISMATCH", source, "cases[" + idx + "].expectedRows[] entries must be mappings");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            row.fields().forEachRemaining(f -> m.put(f.getKey(), jsonScalarToJava(f.getValue())));
            out.add(m);
        }
        return out;
    }

    private static List<String> readStringArray(JsonNode a, String source, int idx) throws EvalParseException {
        if (a == null || a.isNull()) return null;
        if (!a.isArray()) {
            throw new EvalParseException(
                    "TYPE_MISMATCH", source, "cases[" + idx + "].expectedInsightContains must be an array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : a) {
            if (!e.isTextual()) {
                throw new EvalParseException(
                        "TYPE_MISMATCH",
                        source,
                        "cases[" + idx + "].expectedInsightContains[] entries must be strings");
            }
            out.add(e.asText());
        }
        return out;
    }

    private static EvalTolerance readTolerance(JsonNode t, String source, int idx) throws EvalParseException {
        if (t == null || t.isNull()) return null;
        if (!t.isObject()) {
            throw new EvalParseException("TYPE_MISMATCH", source, "cases[" + idx + "].tolerance must be a mapping");
        }
        double abs = t.has("absolute") ? t.get("absolute").asDouble(0.0) : 0.0;
        double rel = t.has("relative") ? t.get("relative").asDouble(0.0) : 0.0;
        try {
            return new EvalTolerance(abs, rel);
        } catch (IllegalArgumentException e) {
            throw new EvalParseException(
                    "INVALID_TOLERANCE", source, "cases[" + idx + "].tolerance: " + e.getMessage());
        }
    }

    private static Object jsonScalarToJava(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isInt()) return v.asInt();
        if (v.isLong()) return v.asLong();
        if (v.isDouble() || v.isFloat()) return v.asDouble();
        if (v.isBigDecimal()) return v.decimalValue();
        return v.asText();
    }

    private static String requireStringField(JsonNode node, String field, String source) throws EvalParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new EvalParseException("MISSING_FIELD", source, "required field '" + field + "' is missing");
        }
        if (!v.isTextual()) {
            throw new EvalParseException("TYPE_MISMATCH", source, "field '" + field + "' must be a string");
        }
        String s = v.asText();
        if (s.isBlank()) {
            throw new EvalParseException("BLANK_FIELD", source, "field '" + field + "' must be non-blank");
        }
        return s;
    }

    private static String optionalStringField(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    /** Structured YAML-parse failure — mirrors the shape of the skill and space parsers. */
    public static final class EvalParseException extends Exception {
        private final String code;
        private final String source;

        public EvalParseException(String code, String source, String message) {
            super(message);
            this.code = code;
            this.source = source;
        }

        public String code() {
            return code;
        }

        public String source() {
            return source;
        }
    }
}
