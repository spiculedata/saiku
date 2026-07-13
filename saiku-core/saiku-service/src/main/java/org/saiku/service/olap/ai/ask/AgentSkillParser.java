/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a skill markdown file into its YAML frontmatter and body, validates the frontmatter, and
 * produces an {@link AgentSkill}.
 *
 * <p>Every failure mode surfaces as {@link ParseException} with a stable {@link
 * ParseException#code()} — the REST layer maps codes to HTTP error responses so operators can fix
 * broken frontmatter without reading logs.
 *
 * <p>Deliberately strict: unknown top-level frontmatter keys are rejected. This keeps the schema
 * evolvable — a typo in {@code descripton} doesn't silently become a nameless skill.
 */
public final class AgentSkillParser {

    /**
     * Frontmatter fence pattern: {@code ---\n...\n---\n} at the very start of the file. The body
     * follows immediately. Multi-line dotall — the frontmatter body can contain newlines.
     */
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*(?:\\n|$)", Pattern.DOTALL);

    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private static final YAMLMapper YAML = new YAMLMapper();

    private AgentSkillParser() {}

    /**
     * Parse the full text of a skill markdown file.
     *
     * @param source display path used only for the resulting skill's {@link AgentSkill#sourcePath()}
     *     — never opened by this method
     * @param text file body (frontmatter + markdown)
     * @throws ParseException on any malformed input; message is safe to log; {@link
     *     ParseException#code()} is a stable machine-readable tag
     */
    public static AgentSkill parse(String source, String text) throws ParseException {
        if (text == null || text.isBlank()) {
            throw new ParseException("EMPTY_SKILL", source, "file is empty");
        }
        Matcher m = FRONTMATTER.matcher(text);
        if (!m.find()) {
            throw new ParseException("MISSING_FRONTMATTER", source, "expected leading `---` YAML frontmatter block");
        }
        String frontmatter = m.group(1);
        String body = text.substring(m.end());
        if (body.isBlank()) {
            throw new ParseException("EMPTY_BODY", source, "skill body must contain markdown after the frontmatter");
        }

        JsonNode node;
        try {
            node = YAML.readTree(frontmatter);
        } catch (IOException e) {
            throw new ParseException("MALFORMED_YAML", source, "frontmatter is not valid YAML: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new ParseException("MALFORMED_YAML", source, "frontmatter must be a YAML mapping (key: value pairs)");
        }

        String name = requireStringField(node, "name", source);
        if (!VALID_NAME.matcher(name).matches()) {
            throw new ParseException(
                    "INVALID_NAME",
                    source,
                    "skill name '"
                            + name
                            + "' must match [a-z][a-z0-9-]{0,63} (kebab-case, start with lowercase letter, 1-64 chars)");
        }
        // YAML gotcha: `description: Yes` (unquoted) parses as boolean true — a common trap for
        // authors writing natural English. Coerce the scalar via asText so booleans and numbers
        // survive without a confusing TYPE_MISMATCH ("Yes." vs "Yes: absolutely" both work).
        String description = requireScalarField(node, "description", source);
        String cube = optionalStringField(node, "cube");

        // Reject unknown top-level keys so typos surface loudly.
        java.util.Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            if (!"name".equals(f) && !"description".equals(f) && !"cube".equals(f)) {
                throw new ParseException(
                        "UNKNOWN_FIELD",
                        source,
                        "unknown frontmatter field '" + f + "' — allowed: name, description, cube");
            }
        }

        return new AgentSkill(name, description, cube, body, source);
    }

    private static String requireStringField(JsonNode node, String field, String source) throws ParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new ParseException("MISSING_FIELD", source, "required frontmatter field '" + field + "' is missing");
        }
        if (!v.isTextual()) {
            throw new ParseException("TYPE_MISMATCH", source, "frontmatter field '" + field + "' must be a string");
        }
        String s = v.asText();
        if (s.isBlank()) {
            throw new ParseException("BLANK_FIELD", source, "frontmatter field '" + field + "' must be non-blank");
        }
        return s;
    }

    /**
     * Like {@link #requireStringField} but coerces booleans and numbers to their text form via
     * {@link JsonNode#asText()}. Used for the {@code description} field where authors writing
     * unquoted natural English shouldn't trip on YAML's boolean literals ({@code Yes}, {@code No},
     * {@code On}, {@code Off}).
     */
    private static String requireScalarField(JsonNode node, String field, String source) throws ParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new ParseException("MISSING_FIELD", source, "required frontmatter field '" + field + "' is missing");
        }
        if (!v.isTextual() && !v.isBoolean() && !v.isNumber()) {
            throw new ParseException(
                    "TYPE_MISMATCH",
                    source,
                    "frontmatter field '" + field + "' must be a string (or a scalar coercible to one)");
        }
        String s = v.asText();
        if (s.isBlank()) {
            throw new ParseException("BLANK_FIELD", source, "frontmatter field '" + field + "' must be non-blank");
        }
        return s;
    }

    private static String optionalStringField(JsonNode node, String field) throws ParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ParseException(
                    "TYPE_MISMATCH", field, "frontmatter field '" + field + "' must be a string when present");
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    /** Structured skill-parsing failure. */
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
