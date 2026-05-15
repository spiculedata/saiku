/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Shape-only validation for {@link AiQueryRequest} bodies. Runs *before*
 * the {@link AiSchemaConverter}'s domain-resolution validation (which
 * handles missing-measure, cross-prefix-member-ref, etc.) so an agent
 * gets one structured error per failure mode rather than a wall of
 * cascading errors.
 *
 * <p>Validation source of truth is the {@link AiRequestJsonSchema} doc —
 * the same JSON Schema embedded in {@code /schema/{cubeId}.requestSchema}
 * for client-side validation. This wrapper applies that same schema
 * server-side, so what the agent sees as the contract and what the
 * server enforces stay aligned by construction.
 *
 * <p>Failure mode: throws {@link AiValidationException} with
 * {@code field} = the JSON Pointer path of the first reported error
 * (e.g. {@code measures[].name} for a missing measure name), and
 * {@code error} = a single concatenated human-readable summary.
 * Multiple-error reporting is intentionally collapsed to the first
 * since agents typically retry one error at a time.
 *
 * <p>Thread-safe: the schema instance is built once at construction.
 */
public final class AiRequestSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonSchema schema;

    public AiRequestSchemaValidator() {
        try {
            // Build the schema doc once and hand it to networknt's
            // JsonSchemaFactory. The doc declares
            // "$schema": "https://json-schema.org/draft/2020-12/schema"
            // so we ask the factory for the 2020-12 dialect explicitly —
            // factory auto-detection works but explicit is one fewer
            // source of drift if the doc's $schema header ever changes.
            JsonNode schemaNode = MAPPER.valueToTree(AiRequestJsonSchema.forRequest());
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            this.schema = factory.getSchema(schemaNode);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to build AiRequestJsonSchema validator", e);
        }
    }

    /**
     * Validate a request body, throwing {@link AiValidationException} on
     * the first reported shape error. No-op if the body conforms.
     *
     * @param body parsed AiQueryRequest as a JsonNode (e.g. from Jackson
     *             deserialisation of the raw POST body)
     */
    public void assertValid(JsonNode body) {
        if (body == null) {
            throw new AiValidationException("body", "request body required", null);
        }
        Set<ValidationMessage> errors = schema.validate(body);
        if (errors.isEmpty()) return;

        // Surface the first error. Agents typically fix one at a time;
        // multi-error blasts make recovery messier without making it
        // faster. The remaining errors (if any) get reported on the
        // next round-trip.
        ValidationMessage first = errors.iterator().next();
        String field = fieldFor(first);
        List<String> available = availableFor(first);
        String message = friendlyMessage(first, field, available, body);
        throw new AiValidationException(field, message, available);
    }

    /** Replace networknt's default error strings with Saiku-voice messages
     *  that mirror the semantic-validator's tone. Falls back to
     *  networknt's message when we don't have a dedicated template —
     *  better to surface the raw error than to lie about it. */
    static String friendlyMessage(ValidationMessage msg, String field, List<String> available, JsonNode body) {
        String type = msg.getType();
        if ("enum".equals(type) && available != null && !available.isEmpty()) {
            String bad = readBadValue(body, msg.getInstanceLocation().toString());
            String role = enumRoleFor(field);
            String legals = String.join(", ", available);
            if (bad == null) {
                return "Unknown " + role + " for " + field + ". Use one of: " + legals + ".";
            }
            return "Unknown " + role + " '" + bad + "'. Use one of: " + legals + ".";
        }
        if ("required".equals(type)) {
            String missing = msg.getProperty();
            if (missing == null || missing.isEmpty()) missing = field;
            if (available != null && !available.isEmpty()) {
                return "Missing required field '" + missing + "'. The body must include: "
                        + String.join(", ", available) + ".";
            }
            return "Missing required field '" + missing + "'.";
        }
        // Type mismatches and other shapes fall through unchanged —
        // networknt's default message is already specific (e.g.
        // "string found, integer expected").
        return msg.getMessage();
    }

    /** Map an enum-violation field path to a human role name used in the
     *  friendly message. Field paths the converter / spec use are listed
     *  here; anything unmapped falls back to "value". */
    static String enumRoleFor(String field) {
        if (field == null) return "value";
        if (field.endsWith(".op") || field.equals("op")) return "filter op";
        if (field.endsWith(".direction") || field.equals("direction")) return "order direction";
        if (field.endsWith(".value") && field.contains("filters")) return "relative preset";
        if (field.equals("format")) return "format";
        return "value";
    }

    /** Pull the offending value out of the request body at the given
     *  instance-location pointer, so the friendly message can quote it
     *  back ("Unknown filter op 'bogus_op'"). Best-effort — returns null
     *  if the pointer doesn't resolve cleanly. */
    private static String readBadValue(JsonNode body, String pointer) {
        if (body == null || pointer == null) return null;
        String p = pointer;
        if (p.startsWith("$.")) p = p.substring(1); // -> .filters[0].op
        else if (p.startsWith("$")) p = p.substring(1);
        // networknt uses dot-and-bracket; JsonPointer uses slash-and-
        // indices. Translate: .filters[0].op -> /filters/0/op
        StringBuilder jp = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                jp.append('/');
                i++;
            } else if (c == '[') {
                int end = p.indexOf(']', i);
                if (end < 0) return null;
                jp.append('/').append(p, i + 1, end);
                i = end + 1;
            } else {
                jp.append(c);
                i++;
            }
        }
        try {
            JsonNode at = body.at(jp.toString());
            if (at.isMissingNode() || at.isNull()) return null;
            return at.asText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Extract the {@code available[]} list for a validation message.
     *  Agents rely on this to self-correct without parsing the error
     *  string. Returns null when the violation has no useful list —
     *  the exception's constructor coerces null to empty.
     *
     *  <ul>
     *    <li><b>enum</b> — schemaNode points at the enum array itself,
     *        we iterate it directly.</li>
     *    <li><b>required</b> — the parent schema's full {@code required}
     *        list, so the agent sees every field the parent object
     *        needs (e.g. {@code [connectionName, catalog, schema, cubeName]}
     *        for a partial cube, or {@code [cube, measures]} for an empty
     *        body). Helpful when an agent forgot more than one field.</li>
     *  </ul>
     */
    static List<String> availableFor(ValidationMessage msg) {
        String type = msg.getType();
        JsonNode schemaNode = msg.getSchemaNode();
        if (schemaNode == null) return null;
        if ("enum".equals(type)) {
            if (!schemaNode.isArray() || schemaNode.isEmpty()) return null;
            return collectStrings(schemaNode);
        }
        if ("required".equals(type)) {
            // networknt points schemaNode at the array of required field
            // names (e.g. ["cube","measures"]); same flat-array shape as
            // enum, just a different semantic.
            if (schemaNode.isArray() && !schemaNode.isEmpty()) {
                return collectStrings(schemaNode);
            }
            // Fallback: the parent schema's "required" property — happens
            // on some networknt branches that surface the property name
            // directly rather than the array.
            JsonNode req = schemaNode.get("required");
            if (req != null && req.isArray() && !req.isEmpty()) {
                return collectStrings(req);
            }
            return null;
        }
        return null;
    }

    private static List<String> collectStrings(JsonNode arr) {
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode v : arr) {
            // asText() handles strings, numbers, and booleans uniformly.
            out.add(v.asText());
        }
        return Collections.unmodifiableList(out);
    }

    /** Compute the agent-facing field pointer for a single validation
     *  error. For {@code required} violations, prefer the missing
     *  property name (so the agent sees "field=cube", not "field=body").
     *  For everything else, fall back to the JSON-Pointer→dotted-array
     *  conversion on the instance location. */
    static String fieldFor(ValidationMessage msg) {
        // Required-violations: the instance location is the parent object;
        // the missing field is in the type-specific property accessor.
        // networknt 1.5.x exposes this via getProperty() — null on other
        // error types.
        String property = msg.getProperty();
        if ("required".equals(msg.getType()) && property != null && !property.isEmpty()) {
            return property;
        }
        return jsonPointerToField(msg.getInstanceLocation().toString());
    }

    /**
     * Convert a networknt instance-location (e.g. {@code $.filters[0].op})
     * to the dotted-array notation the AI surface uses (e.g.
     * {@code filters[0].op}). Numeric indices are preserved so an agent
     * sees exactly which array element failed — saves a follow-up
     * round-trip when multiple entries are wrong.
     */
    static String jsonPointerToField(String pointer) {
        if (pointer == null || pointer.isEmpty() || pointer.equals("$")) return "body";
        if (pointer.startsWith("$.")) return pointer.substring(2);
        if (pointer.startsWith("$")) return pointer.substring(1);
        return pointer;
    }
}
