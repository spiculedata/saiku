/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * Parses an {@link AgentSpace} from its on-disk JSON representation.
 *
 * <p>Every failure surfaces as {@link ParseException} with a stable {@link ParseException#code()}
 * so the REST layer maps codes to structured HTTP error bodies. Deliberately strict: unknown
 * top-level keys are rejected so a typo (e.g. {@code sytemPrompt}) surfaces rather than being
 * silently dropped.
 *
 * <p>On-disk shape:
 *
 * <pre>{@code
 * {
 *   "id": "foodmart-sales-analyst",
 *   "name": "FoodMart Sales Analyst",
 *   "description": "Weekly and monthly sales rollups over FoodMart.",
 *   "systemPrompt": "You are the FoodMart Sales Analyst...",
 *   "cubeAllowlist": [
 *     {"connectionName": "unknown_foodmart", "catalog": "FoodMart", "schema": "FoodMart", "cubeName": "Sales"}
 *   ],
 *   "skillAllowlist": ["weekly-foodmart-rollup"],
 *   "suggestedPrompts": [
 *     "How did sales track last week vs the prior week?",
 *     "Break down store sales by Product Family for Q4."
 *   ]
 * }
 * }</pre>
 */
public final class AgentSpaceParser {

    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentSpaceParser() {}

    public static AgentSpace parse(String source, String json) throws ParseException {
        if (json == null || json.isBlank()) {
            throw new ParseException("EMPTY_SPACE", source, "file is empty");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ParseException("MALFORMED_JSON", source, "not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ParseException("MALFORMED_JSON", source, "top-level must be a JSON object");
        }

        String id = requireString(root, "id", source);
        if (!VALID_ID.matcher(id).matches()) {
            throw new ParseException(
                    "INVALID_ID",
                    source,
                    "space id '"
                            + id
                            + "' must match [a-z][a-z0-9-]{0,63} (kebab-case, start with lowercase letter, 1-64 chars)");
        }
        String name = requireString(root, "name", source);
        String description = optionalString(root, "description", source);
        String systemPrompt = optionalString(root, "systemPrompt", source);
        List<AiCubeRef> cubes = readCubeAllowlist(root, source);
        List<String> skills = readStringArray(root, "skillAllowlist", source);
        List<String> suggested = readStringArray(root, "suggestedPrompts", source);

        // Reject unknown top-level keys so typos surface loudly.
        java.util.Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            switch (f) {
                case "id":
                case "name":
                case "description":
                case "systemPrompt":
                case "cubeAllowlist":
                case "skillAllowlist":
                case "suggestedPrompts":
                    break;
                default:
                    throw new ParseException(
                            "UNKNOWN_FIELD",
                            source,
                            "unknown field '"
                                    + f
                                    + "' — allowed: id, name, description, systemPrompt, cubeAllowlist, skillAllowlist, suggestedPrompts");
            }
        }
        return new AgentSpace(id, name, description, systemPrompt, cubes, skills, suggested, source);
    }

    private static String requireString(JsonNode node, String field, String source) throws ParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new ParseException("MISSING_FIELD", source, "required field '" + field + "' is missing");
        }
        if (!v.isTextual()) {
            throw new ParseException("TYPE_MISMATCH", source, "field '" + field + "' must be a string");
        }
        String s = v.asText();
        if (s.isBlank()) {
            throw new ParseException("BLANK_FIELD", source, "field '" + field + "' must be non-blank");
        }
        return s;
    }

    private static String optionalString(JsonNode node, String field, String source) throws ParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ParseException("TYPE_MISMATCH", source, "field '" + field + "' must be a string when present");
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static List<String> readStringArray(JsonNode node, String field, String source) throws ParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            throw new ParseException("TYPE_MISMATCH", source, "field '" + field + "' must be a JSON array of strings");
        }
        List<String> out = new ArrayList<>();
        int idx = 0;
        for (JsonNode entry : v) {
            if (!entry.isTextual()) {
                throw new ParseException(
                        "TYPE_MISMATCH", source, "field '" + field + "[" + idx + "]' must be a string");
            }
            String s = entry.asText();
            if (!s.isBlank()) {
                out.add(s);
            }
            idx++;
        }
        return out;
    }

    private static List<AiCubeRef> readCubeAllowlist(JsonNode node, String source) throws ParseException {
        JsonNode v = node.get("cubeAllowlist");
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            throw new ParseException(
                    "TYPE_MISMATCH", source, "field 'cubeAllowlist' must be a JSON array of cube refs");
        }
        List<AiCubeRef> out = new ArrayList<>();
        int idx = 0;
        for (JsonNode entry : v) {
            if (!entry.isObject()) {
                throw new ParseException(
                        "TYPE_MISMATCH", source, "field 'cubeAllowlist[" + idx + "]' must be a cube-ref object");
            }
            String connection = requireCubeField(entry, "connectionName", idx, source);
            String catalog = requireCubeField(entry, "catalog", idx, source);
            String schema = requireCubeField(entry, "schema", idx, source);
            String cubeName = requireCubeField(entry, "cubeName", idx, source);
            out.add(new AiCubeRef(connection, catalog, schema, cubeName));
            idx++;
        }
        if (out.isEmpty()) {
            throw new ParseException(
                    "EMPTY_ALLOWLIST",
                    source,
                    "cubeAllowlist must contain at least one cube — a space with no cubes cannot answer questions");
        }
        return out;
    }

    private static String requireCubeField(JsonNode entry, String field, int idx, String source) throws ParseException {
        JsonNode v = entry.get(field);
        if (v == null || v.isNull() || !v.isTextual() || v.asText().isBlank()) {
            throw new ParseException(
                    "INVALID_CUBE_REF", source, "cubeAllowlist[" + idx + "]." + field + "' must be a non-blank string");
        }
        return v.asText();
    }

    /** Structured space-parsing failure. */
    public static final class ParseException extends Exception {
        private final String code;
        private final String source;

        public ParseException(String code, String source, String message) {
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
