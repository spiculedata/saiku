/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.saiku.datasources.connection.encrypt.CryptoUtil;

/**
 * Parses a {@link McpOutboundServer} from its on-disk JSON representation.
 *
 * <p>Mirrors {@code AgentSpaceParser}: every failure surfaces as {@link ParseException} with a
 * stable {@link ParseException#code()}, unknown top-level keys are rejected, and on-disk secrets
 * are decrypted here (via {@link CryptoUtil}, the same at-rest encryption datasource passwords use)
 * so every other layer only ever sees plaintext or ciphertext, never both.
 *
 * <p>On-disk shape:
 *
 * <pre>{@code
 * {
 *   "id": "notion",
 *   "name": "Notion workspace",
 *   "url": "https://mcp.notion.com/mcp",
 *   "authHeaderName": "Authorization",
 *   "authHeaderValue": "v2:...(AES-256-GCM ciphertext, written by the registry on save)...",
 *   "enabledTools": ["search_docs", "get_page"]
 * }
 * }</pre>
 *
 * <p>{@code authHeaderName} / {@code authHeaderValue} are optional together — a server with no
 * credential (an open internal endpoint) omits both. {@code enabledTools} defaults to empty
 * (nothing enabled — see {@link McpOutboundServer}).
 */
public final class McpOutboundServerParser {

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpOutboundServerParser() {}

    public static McpOutboundServer parse(String source, String json) throws ParseException {
        if (json == null || json.isBlank()) {
            throw new ParseException("EMPTY_SERVER", source, "file is empty");
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
                    "server id '" + id + "' must match [a-z0-9][a-z0-9-]{0,63} (kebab-case, 1-64 chars)");
        }
        String name = requireString(root, "name", source);
        String url = requireString(root, "url", source);
        validateUrl(url, source);

        String authHeaderName = optionalString(root, "authHeaderName", source);
        String encryptedValue = optionalString(root, "authHeaderValue", source);
        if ((authHeaderName == null) != (encryptedValue == null)) {
            throw new ParseException(
                    "INCOMPLETE_AUTH",
                    source,
                    "authHeaderName and authHeaderValue must be set together, or both omitted");
        }
        String authHeaderValue = null;
        if (encryptedValue != null) {
            try {
                authHeaderValue = CryptoUtil.decrypt(encryptedValue);
            } catch (RuntimeException e) {
                // Never echo the ciphertext or the decrypt exception's message (may wrap key
                // material paths) into a client-facing error.
                throw new ParseException("BAD_CREDENTIAL", source, "authHeaderValue could not be decrypted");
            }
        }

        Set<String> enabledTools = readStringSet(root, "enabledTools", source);

        java.util.Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            switch (f) {
                case "id":
                case "name":
                case "url":
                case "authHeaderName":
                case "authHeaderValue":
                case "enabledTools":
                    break;
                default:
                    throw new ParseException(
                            "UNKNOWN_FIELD",
                            source,
                            "unknown field '" + f
                                    + "' — allowed: id, name, url, authHeaderName, authHeaderValue, enabledTools");
            }
        }
        return new McpOutboundServer(id, name, url, authHeaderName, authHeaderValue, enabledTools, source);
    }

    private static void validateUrl(String url, String source) throws ParseException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ParseException("INVALID_URL", source, "url is not a valid URI: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new ParseException("INVALID_URL", source, "url must be http(s) — got: " + url);
        }
        if (uri.getHost() == null) {
            throw new ParseException("INVALID_URL", source, "url must include a host: " + url);
        }
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

    private static Set<String> readStringSet(JsonNode node, String field, String source) throws ParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return Set.of();
        }
        if (!v.isArray()) {
            throw new ParseException("TYPE_MISMATCH", source, "field '" + field + "' must be a JSON array of strings");
        }
        Set<String> out = new LinkedHashSet<>();
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

    /** Structured server-parsing failure. */
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
