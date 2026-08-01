/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Parses a {@code plugin.json} manifest into a {@link TilePluginManifest}.
 *
 * <p>Every failure surfaces as {@link ParseException} with a stable {@link ParseException#code()}
 * the REST layer can echo so operators fix a broken bundle without reading server logs. The
 * {@link org.saiku.service.olap.ai.ask.AgentSkillParser} is the sibling pattern.
 *
 * <p>The {@code id} slug rule ({@code [a-z0-9-]+}, must start alphanumeric) is load-bearing for
 * security: the id becomes both a URL path segment and a filesystem directory name, so anything that
 * could escape {@code saiku-home/tile-plugins/} (a dot, a slash, {@code ..}) is rejected here.
 */
public final class TilePluginParser {

    /**
     * Safe id slug: lowercase alphanumerics and hyphens, starting with an alphanumeric, 1-64 chars.
     * Forbids {@code .}, {@code /}, {@code \}, whitespace and {@code ..} — so an id can never escape
     * the tile-plugins directory or collide with a dotfile.
     */
    static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private static final ObjectMapper JSON = new ObjectMapper();

    private TilePluginParser() {}

    /** True when {@code id} is a safe filename/URL slug. Used by the REST layer to gate path params. */
    public static boolean isValidId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /**
     * Parse the text of a {@code plugin.json} manifest.
     *
     * @param source display path used only for {@link TilePluginManifest#sourcePath()} and error
     *     messages — never opened by this method
     * @param text the raw manifest JSON
     * @throws ParseException on any malformed input; {@link ParseException#code()} is a stable tag
     */
    public static TilePluginManifest parse(String source, String text) throws ParseException {
        if (text == null || text.isBlank()) {
            throw new ParseException("UNPARSEABLE_MANIFEST", source, "plugin.json is empty");
        }
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (IOException e) {
            throw new ParseException(
                    "UNPARSEABLE_MANIFEST", source, "plugin.json is not valid JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new ParseException(
                    "UNPARSEABLE_MANIFEST", source, "plugin.json must be a JSON object ({ \"id\": ..., ... })");
        }

        JsonNode idNode = node.get("id");
        if (idNode == null || idNode.isNull() || !idNode.isTextual()) {
            throw new ParseException("INVALID_ID", source, "manifest field 'id' is required and must be a string");
        }
        String id = idNode.asText();
        if (!isValidId(id)) {
            throw new ParseException(
                    "INVALID_ID",
                    source,
                    "plugin id '" + id + "' must match [a-z0-9-] (start alphanumeric, 1-64 chars, no path separators)");
        }

        JsonNode labelNode = node.get("label");
        if (labelNode == null
                || labelNode.isNull()
                || !labelNode.isTextual()
                || labelNode.asText().isBlank()) {
            throw new ParseException(
                    "UNPARSEABLE_MANIFEST",
                    source,
                    "manifest field 'label' is required and must be a non-blank string");
        }
        String label = labelNode.asText();

        JsonNode optionSchema = node.get("optionSchema");
        if (optionSchema != null && !optionSchema.isNull() && !optionSchema.isObject()) {
            throw new ParseException(
                    "UNPARSEABLE_MANIFEST", source, "manifest field 'optionSchema' must be an object when present");
        }
        if (optionSchema != null && optionSchema.isNull()) {
            optionSchema = null;
        }

        return new TilePluginManifest(id, label, optionSchema, source);
    }

    /** Structured manifest-parsing failure. */
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
