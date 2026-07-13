/*
 *   Copyright 2026 Spicule Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.web.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiSchema;

/**
 * Generates one Query field per cube, with typed enums for measures + levels and a typed row
 * output type. Consumers get autocomplete on the measure/dimension names ("Cube's whole DX
 * pitch") without the initial slice's generic {@code executeMdx(input: ...): JSON!} shape.
 *
 * <p>Naming rules (deterministic):
 * <ul>
 *   <li><b>Query field</b> — camelCase of the cube name. Colliding cubes get catalog / schema /
 *       connection prefixes appended in that order until unique.</li>
 *   <li><b>Measure enum values</b> — SCREAMING_SNAKE_CASE of the canonical measure name.</li>
 *   <li><b>Level enum values</b> — {@code <DIMENSION>__<LEVEL>} (double underscore) so
 *       ambiguous level names ("Name", "Description") disambiguate cleanly.</li>
 *   <li><b>Row output fields</b> — camelCase of the canonical name. Levels get
 *       {@code dimensionLevel} concatenation.</li>
 * </ul>
 *
 * <p>Response marshalling: {@link #materialiseRow(Map, List, List)} maps an AiQueryResponse
 * record (keyed by measure / level caption) onto the row shape the client selected. Cells the
 * client didn't ask for are absent from the record; cells with no value are {@code null}.
 *
 * <p>Package-private on purpose — driven by {@link SaikuGraphQlService}.
 */
final class CubeTypeGenerator {

    private final AiCubeSummary summary;
    private final AiSchema schema;
    private final String queryFieldName;
    private final String typePrefix;

    /** enumValue → canonical name (measures). */
    private final Map<String, String> measureEnumToName = new LinkedHashMap<>();
    /** measure canonical name → GraphQL row-field name (camelCase). */
    private final Map<String, String> measureNameToField = new LinkedHashMap<>();

    /** enumValue → AxisRef (dimension, hierarchy, level). */
    private final Map<String, AxisRef> levelEnumToAxis = new LinkedHashMap<>();
    /** level canonical name → GraphQL row-field name (camelCase). */
    private final Map<String, String> levelNameToField = new LinkedHashMap<>();

    static final class AxisRef {
        final String dimension;
        final String hierarchy;
        final String level;

        AxisRef(String dimension, String hierarchy, String level) {
            this.dimension = dimension;
            this.hierarchy = hierarchy;
            this.level = level;
        }
    }

    CubeTypeGenerator(AiCubeSummary summary, AiSchema schema, String queryFieldName) {
        this.summary = summary;
        this.schema = schema;
        this.queryFieldName = queryFieldName;
        this.typePrefix = pascalCase(queryFieldName);
        buildNameMaps();
    }

    // ------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------

    String getQueryFieldName() {
        return queryFieldName;
    }

    AiCubeRef toCubeRef() {
        AiCubeRef r = new AiCubeRef();
        r.setConnectionName(summary.getConnectionName());
        r.setCatalog(summary.getCatalog());
        r.setSchema(summary.getSchema());
        r.setCubeName(summary.getCubeName());
        return r;
    }

    String measureEnumToCanonical(String enumValue) {
        return measureEnumToName.get(enumValue);
    }

    AxisRef levelEnumToAxis(String enumValue) {
        return levelEnumToAxis.get(enumValue);
    }

    // ------------------------------------------------------------
    // SDL fragment
    // ------------------------------------------------------------

    /**
     * Emit the SDL fragment for this cube:
     * <pre>
     *   enum {Prefix}Measure { STORE_SALES ... }
     *   enum {Prefix}Level   { PRODUCT__PRODUCT_FAMILY ... }
     *   type {Prefix}Row     { storeSales: Float, productProductFamily: String, ... }
     *   extend type Query {
     *     {field}(measures: [{Prefix}Measure!]!, rows: [{Prefix}Level!], ...): [{Prefix}Row!]!
     *   }
     * </pre>
     * Returns an empty string if the cube has no measures — GraphQL enums can't be empty and a
     * measures-less cube isn't queryable through /ai/query anyway.
     */
    String toSdl() {
        if (measureEnumToName.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Cube: ")
                .append(safeComment(summary.getConnectionName()))
                .append(" / ")
                .append(safeComment(summary.getCatalog()))
                .append(" / ")
                .append(safeComment(summary.getSchema()))
                .append(" / ")
                .append(safeComment(summary.getCubeName()))
                .append('\n');

        sb.append("enum ").append(typePrefix).append("Measure {\n");
        for (String enumValue : measureEnumToName.keySet()) {
            sb.append("  ").append(enumValue).append('\n');
        }
        sb.append("}\n\n");

        if (!levelEnumToAxis.isEmpty()) {
            sb.append("enum ").append(typePrefix).append("Level {\n");
            for (String enumValue : levelEnumToAxis.keySet()) {
                sb.append("  ").append(enumValue).append('\n');
            }
            sb.append("}\n\n");
        }

        sb.append("type ").append(typePrefix).append("Row {\n");
        for (String field : measureNameToField.values()) {
            sb.append("  ").append(field).append(": Float\n");
        }
        for (String field : levelNameToField.values()) {
            sb.append("  ").append(field).append(": String\n");
        }
        sb.append("}\n\n");

        sb.append("extend type Query {\n");
        sb.append("  ").append(queryFieldName).append("(\n");
        sb.append("    measures: [").append(typePrefix).append("Measure!]!\n");
        if (!levelEnumToAxis.isEmpty()) {
            sb.append("    rows: [").append(typePrefix).append("Level!]\n");
            sb.append("    columns: [").append(typePrefix).append("Level!]\n");
        }
        sb.append("    filters: [JSON!]\n");
        sb.append("    limit: Int\n");
        sb.append("  ): [").append(typePrefix).append("Row!]!\n");
        sb.append("}\n\n");

        return sb.toString();
    }

    // ------------------------------------------------------------
    // Response marshalling
    // ------------------------------------------------------------

    /**
     * Marshal one AiQueryResponse record onto the typed row shape the client selected.
     *
     * @param record raw record from {@code AiQueryResponse.data} — keyed by measure / level caption
     * @param selectedMeasures canonical measure names the client asked for
     * @param selectedLevels axis refs the client asked for
     */
    Map<String, Object> materialiseRow(
            Map<String, Object> record, List<String> selectedMeasures, List<AxisRef> selectedLevels) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String canonical : selectedMeasures) {
            String fieldName = measureNameToField.get(canonical);
            if (fieldName == null) continue;
            Object value = record.get(canonical);
            if (value == null) {
                // Try the display caption — buildResponse may key on that instead of canonical
                AiSchema.Measure m = lookupMeasure(canonical);
                if (m != null && m.displayName != null) {
                    value = record.get(m.displayName);
                }
            }
            out.put(fieldName, coerceNumeric(value));
        }
        for (AxisRef axis : selectedLevels) {
            String key = axis.level;
            String fieldName = levelNameToField.get(key);
            if (fieldName == null) continue;
            Object value = record.get(key);
            if (value == null) {
                // Levels may report captions under a dimension-scoped key too
                value = record.get(axis.dimension + " - " + axis.level);
            }
            out.put(fieldName, value == null ? null : value.toString());
        }
        return out;
    }

    // ------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------

    private void buildNameMaps() {
        // Collision detection for Row-type field names is UNIFIED across measures + levels —
        // both go into the same GraphQL type, so a measure `Store Sqft` collides with a level
        // `Store Sqft` even though they live in different metadata maps. Historical bug: kept
        // them separate and got 'declared a field with a non-unique name' on real FoodMart cubes.
        java.util.Set<String> takenRowFields = new java.util.HashSet<>();

        // Measures
        for (Map.Entry<String, AiSchema.Measure> e : schema.measures.entrySet()) {
            AiSchema.Measure m = e.getValue();
            if (m == null || m.name == null || m.name.isBlank()) continue;
            String enumValue = uniqueScreamingSnake(measureEnumToName.keySet(), m.name);
            measureEnumToName.put(enumValue, m.name);
            String field = uniqueCamel(takenRowFields, m.name);
            measureNameToField.put(m.name, field);
            takenRowFields.add(field);
        }
        // Levels — walk dimensions → hierarchies → levels
        for (AiSchema.Dimension dim : schema.dimensions.values()) {
            if (dim == null || dim.hierarchies == null) continue;
            String dimensionName = dim.name != null ? dim.name : "Dimension";
            for (AiSchema.Hierarchy hier : dim.hierarchies.values()) {
                if (hier == null || hier.levels == null) continue;
                String hierarchyName = hier.name != null ? hier.name : dimensionName;
                for (AiSchema.Level lvl : hier.levels.values()) {
                    if (lvl == null || lvl.name == null || lvl.name.isBlank()) continue;
                    // Enum value is DIMENSION__LEVEL — the double-underscore separator is a
                    // GraphQL/Postgres convention that survives even if either side contains
                    // spaces or hyphens (each side is screaming-snaked independently).
                    String enumValue = uniqueLevelEnum(
                            levelEnumToAxis.keySet(), screamingSnake(dimensionName), screamingSnake(lvl.name));
                    levelEnumToAxis.put(enumValue, new AxisRef(dimensionName, hierarchyName, lvl.name));
                    // Row-field naming uses just the level name by default. If it would collide
                    // with a measure OR another level's field, retry with the dimension prefix.
                    if (levelNameToField.containsKey(lvl.name)) continue;
                    String preferredField = uniqueCamel(takenRowFields, lvl.name);
                    if (isDuplicateBasis(takenRowFields, preferredField)) {
                        preferredField = uniqueCamel(takenRowFields, dimensionName + " " + lvl.name);
                    }
                    levelNameToField.put(lvl.name, preferredField);
                    takenRowFields.add(preferredField);
                }
            }
        }
    }

    private AiSchema.Measure lookupMeasure(String canonicalName) {
        for (AiSchema.Measure m : schema.measures.values()) {
            if (m != null && canonicalName.equals(m.name)) return m;
        }
        return null;
    }

    private static Object coerceNumeric(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).replace(",", ""));
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // Sanitisation
    // ------------------------------------------------------------

    /**
     * GraphQL identifiers are restricted to ASCII {@code [_A-Za-z][_0-9A-Za-z]*} — the JVM's
     * {@link Character#isLetterOrDigit} accepts CJK, Cyrillic, and other Unicode letter
     * categories which parse as tokens in Java but not in GraphQL. Everything outside the ASCII
     * subset gets treated as a word break.
     */
    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isAsciiUpper(char c) {
        return c >= 'A' && c <= 'Z';
    }

    static String screamingSnake(String raw) {
        if (raw == null || raw.isBlank()) return "UNKNOWN";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean prevBreak = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isAsciiLetterOrDigit(c)) {
                if (isAsciiUpper(c) && !prevBreak && sb.length() > 0) {
                    char last = sb.charAt(sb.length() - 1);
                    if (last != '_') {
                        sb.append('_');
                    }
                }
                sb.append(Character.toUpperCase(c));
                prevBreak = false;
            } else {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                prevBreak = true;
            }
        }
        // trim leading/trailing underscores
        int start = 0, end = sb.length();
        while (start < end && sb.charAt(start) == '_') start++;
        while (end > start && sb.charAt(end - 1) == '_') end--;
        String out = sb.substring(start, end);
        if (out.isBlank()) return "UNKNOWN";
        // Ensure it starts with a letter — GraphQL enum values may not start with a digit
        if (out.charAt(0) >= '0' && out.charAt(0) <= '9') return "_" + out;
        return out;
    }

    static String camelCase(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean nextUpper = false;
        boolean seenLetter = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isAsciiLetterOrDigit(c)) {
                if (!seenLetter) {
                    sb.append(Character.toLowerCase(c));
                    seenLetter = true;
                } else if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            } else {
                if (seenLetter) nextUpper = true;
            }
        }
        String out = sb.toString();
        if (out.isBlank()) return "unknown";
        if (out.charAt(0) >= '0' && out.charAt(0) <= '9') return "_" + out;
        return out;
    }

    static String pascalCase(String raw) {
        String camel = camelCase(raw);
        if (camel.isEmpty()) return "Unknown";
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    /**
     * Comment-safe text for the SDL header. Strips newlines / carriage returns so a hostile
     * cube name can't break the schema by injecting invalid GraphQL after {@code #}.
     */
    private static String safeComment(String raw) {
        if (raw == null) return "";
        return raw.replace('\n', ' ').replace('\r', ' ');
    }

    private static String uniqueScreamingSnake(java.util.Set<String> existing, String basis) {
        String out = screamingSnake(basis);
        int counter = 2;
        while (existing.contains(out)) {
            out = screamingSnake(basis) + "_" + counter++;
        }
        return out;
    }

    /** Build a DIMENSION__LEVEL enum value and disambiguate with a counter if it collides. */
    private static String uniqueLevelEnum(java.util.Set<String> existing, String dimension, String level) {
        String base = dimension + "__" + level;
        String out = base;
        int counter = 2;
        while (existing.contains(out)) {
            out = base + "_" + counter++;
        }
        return out;
    }

    /**
     * Guard for {@code buildNameMaps}: we always want the second dimension's level "Name" to
     * become e.g. {@code customerName}, not {@code name2}. We can't easily know inside
     * {@code uniqueCamel} which basis to prefer, so we detect the collision here and re-derive
     * the field name with the dimension-prefixed basis on the second sighting.
     */
    private static boolean isDuplicateBasis(java.util.Collection<String> values, String candidate) {
        // If candidate ends with a digit (e.g. name2), the plain basis collided — prefer dimension prefix.
        if (candidate.isEmpty()) return false;
        char last = candidate.charAt(candidate.length() - 1);
        return Character.isDigit(last);
    }

    private static String uniqueCamel(java.util.Collection<String> existing, String basis) {
        String out = camelCase(basis);
        int counter = 2;
        while (existing.contains(out)) {
            out = camelCase(basis) + counter++;
        }
        return out;
    }
}
