/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.saiku.olap.query2.ThinQuery;

/**
 * Parses a {@link CertifiedQuery} from its on-disk JSON representation (saiku#1430).
 *
 * <p>Every failure surfaces as {@link ParseException} with a stable {@link ParseException#code()}
 * so the registry can report it in the {@code /ai/certified?errors=true} payload and the web layer
 * can map codes to structured HTTP bodies. Deliberately strict, for the same reason the space
 * parser is: an operator who typos a field name of the CFO-approved revenue query must be told,
 * not silently handed a query that quietly does something else.
 *
 * <p>On-disk shape:
 *
 * <pre>{@code
 * {
 *   "id": "monthly-net-revenue",
 *   "description": "Official monthly net revenue by region and product family.",
 *   "matchIntent": ["monthly revenue", "monthly net revenue", "revenue by month"],
 *   "query": { <ThinQuery JSON> }
 * }
 * }</pre>
 *
 * <p>The {@code query} body is validated for RUNNABILITY, not just well-formedness: it must
 * deserialise to a {@link ThinQuery}, name a cube (name + connection), and actually carry a
 * statement ({@code mdx} or {@code queryModel}). A certified entry that can't run is a broken
 * guarantee, so it is rejected at scan time rather than at 3am when the CFO asks.
 */
public final class CertifiedQueryParser {

    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bounds on {@code matchIntent} — an operator typo shouldn't be able to wedge the scanner. */
    private static final int MAX_INTENTS = 32;

    private static final int MAX_INTENT_CHARS = 200;

    private CertifiedQueryParser() {}

    public static CertifiedQuery parse(String source, String json) throws ParseException {
        if (json == null || json.isBlank()) {
            throw new ParseException("EMPTY_CERTIFIED_QUERY", source, "file is empty");
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
                    "certified query id '"
                            + id
                            + "' must match [a-z][a-z0-9-]{0,63} (kebab-case, start with lowercase letter, 1-64 chars)");
        }
        String description = optionalString(root, "description", source);
        List<String> intents = readMatchIntent(root, source);
        ThinQuery query = readQuery(root, source);

        // Reject unknown top-level keys so typos surface loudly. Without this, "matchInents"
        // would silently parse as an empty intent list and the entry would never fire.
        java.util.Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            switch (f) {
                case "id":
                case "description":
                case "matchIntent":
                case "query":
                    break;
                default:
                    throw new ParseException(
                            "UNKNOWN_FIELD",
                            source,
                            "unknown field '" + f + "' — allowed: id, description, matchIntent, query");
            }
        }
        return new CertifiedQuery(id, description, intents, query, source);
    }

    /**
     * {@code matchIntent} must be a non-empty array of non-blank strings, capped at {@value
     * #MAX_INTENTS} entries of {@value #MAX_INTENT_CHARS} chars each. Entries are trimmed,
     * lower-cased and whitespace-collapsed so the file stays readable while matching stays
     * case- and punctuation-insensitive. Duplicates collapse (they'd only re-score identically).
     */
    private static List<String> readMatchIntent(JsonNode root, String source) throws ParseException {
        JsonNode v = root.get("matchIntent");
        if (v == null || v.isNull()) {
            throw new ParseException(
                    "INVALID_MATCH_INTENT",
                    source,
                    "required field 'matchIntent' is missing — a certified query with no intent "
                            + "phrasing can never be routed to");
        }
        if (!v.isArray()) {
            throw new ParseException(
                    "INVALID_MATCH_INTENT", source, "field 'matchIntent' must be a JSON array of strings");
        }
        if (v.isEmpty()) {
            throw new ParseException(
                    "INVALID_MATCH_INTENT", source, "field 'matchIntent' must declare at least one phrasing");
        }
        if (v.size() > MAX_INTENTS) {
            throw new ParseException(
                    "INVALID_MATCH_INTENT",
                    source,
                    "field 'matchIntent' has " + v.size() + " entries; the cap is " + MAX_INTENTS);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int idx = 0;
        for (JsonNode entry : v) {
            if (entry == null || !entry.isTextual()) {
                throw new ParseException(
                        "INVALID_MATCH_INTENT", source, "field 'matchIntent[" + idx + "]' must be a string");
            }
            String s = entry.asText();
            if (s.isBlank()) {
                throw new ParseException(
                        "INVALID_MATCH_INTENT", source, "field 'matchIntent[" + idx + "]' must be non-blank");
            }
            if (s.length() > MAX_INTENT_CHARS) {
                throw new ParseException(
                        "INVALID_MATCH_INTENT",
                        source,
                        "field 'matchIntent[" + idx + "]' is " + s.length() + " chars; the cap is " + MAX_INTENT_CHARS);
            }
            out.add(normaliseIntent(s));
            idx++;
        }
        if (out.isEmpty()) {
            throw new ParseException("INVALID_MATCH_INTENT", source, "field 'matchIntent' declared no usable phrasing");
        }
        return List.copyOf(out);
    }

    /** Trim, lower-case, collapse runs of whitespace (and punctuation) to single spaces. */
    private static String normaliseIntent(String s) {
        return s.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .trim();
    }

    /**
     * Read + validate the {@code query} body. Beyond deserialisation this enforces that the entry
     * is actually runnable: a cube with a name and connection, and a statement to run.
     */
    private static ThinQuery readQuery(JsonNode root, String source) throws ParseException {
        JsonNode v = root.get("query");
        if (v == null || v.isNull()) {
            throw new ParseException("MISSING_QUERY", source, "required field 'query' is missing");
        }
        if (!v.isObject()) {
            throw new ParseException("TYPE_MISMATCH", source, "field 'query' must be a JSON object (a ThinQuery)");
        }
        ThinQuery tq;
        try {
            tq = MAPPER.treeToValue((ObjectNode) v, ThinQuery.class);
        } catch (IOException e) {
            throw new ParseException(
                    "INVALID_QUERY", source, "field 'query' is not a valid ThinQuery: " + e.getMessage());
        }
        if (tq.getCube() == null) {
            throw new ParseException(
                    "INVALID_QUERY", source, "query.cube is required — a certified query must name its cube");
        }
        if (tq.getCube().getName() == null || tq.getCube().getName().isBlank()) {
            throw new ParseException("INVALID_QUERY", source, "query.cube.name is required");
        }
        if (tq.getCube().getConnection() == null || tq.getCube().getConnection().isBlank()) {
            throw new ParseException(
                    "INVALID_QUERY",
                    source,
                    "query.cube.connection is required — it selects the datasource to run against");
        }
        boolean hasMdx = tq.getMdx() != null && !tq.getMdx().isBlank();
        boolean hasModel = tq.getQueryModel() != null;
        if (!hasMdx && !hasModel) {
            throw new ParseException(
                    "INVALID_QUERY", source, "query must carry either an 'mdx' statement or a 'queryModel'");
        }
        return tq;
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

    /**
     * Re-serialise a certified query back to the on-disk shape {@link #parse} reads. Used by the
     * admin tooling and by the tests that assert round-trip fidelity; the {@code query} body is
     * written back as the ThinQuery itself.
     */
    public static ObjectNode toJson(CertifiedQuery certified) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", certified.id());
        if (certified.description() != null) {
            node.put("description", certified.description());
        }
        var intents = node.putArray("matchIntent");
        for (String intent : certified.matchIntent()) {
            intents.add(intent);
        }
        node.set("query", MAPPER.valueToTree(certified.query()));
        return node;
    }

    /** Structured certified-query parsing failure. */
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
