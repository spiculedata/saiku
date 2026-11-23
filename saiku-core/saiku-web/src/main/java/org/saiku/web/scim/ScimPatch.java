/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PATCH (RFC 7644 §3.5.2) parsing and normalisation, kept separate from {@link ScimService} so the
 * awkward parts of the real-world dialects are testable on their own.
 *
 * <p>What connectors actually send, and why each case is here:
 * <ul>
 *   <li><b>Mixed-case ops.</b> Okta sends {@code "replace"}; Entra sends {@code "Replace"}.
 *       The RFC says the values are case-insensitive, so they are lower-cased here.</li>
 *   <li><b>URN-qualified paths.</b> Entra sends
 *       {@code urn:ietf:params:scim:schemas:core:2.0:User:userName}; Okta sends
 *       {@code "userName"}. Both reduce to the same attribute.</li>
 *   <li><b>Path-less ops.</b> {@code {"op":"replace","value":{"active":false}}} — the value is an
 *       object whose keys are attribute paths, not a scalar.</li>
 *   <li><b>Filter-qualified paths.</b> {@code emails[type eq "work"].value}.</li>
 *   <li><b>Complex values for a simple path.</b> {@code "value": {"value": "a@b.c"}} for an
 *       email path, and an array of member objects for {@code members}.</li>
 * </ul>
 *
 * <p>Anything outside that set is rejected with an explicit SCIM error rather than ignored — a
 * silently dropped {@code active: false} would leave a deprovisioned user with a live account.
 */
public final class ScimPatch {

    private ScimPatch() {}

    public enum Operation {
        ADD,
        REPLACE,
        REMOVE;

        /** {@code null}/unknown op fails closed as an invalid-syntax 400. */
        public static Operation of(String op) {
            if (op == null) {
                throw ScimException.badRequest("invalidSyntax", "Operation 'op' is required");
            }
            switch (op.trim().toLowerCase(Locale.ROOT)) {
                case "add":
                    return ADD;
                case "replace":
                    return REPLACE;
                case "remove":
                    return REMOVE;
                default:
                    throw ScimException.badRequest("invalidSyntax", "Unsupported operation: " + op);
            }
        }
    }

    /** The operations of a PATCH body; an empty or absent list is a 400 per RFC 7644 §3.5.2. */
    public static List<ScimPatchRequest.Operation> operations(ScimPatchRequest body) {
        if (body == null || body.operations == null || body.operations.isEmpty()) {
            throw ScimException.badRequest("invalidSyntax", "A PATCH body must carry at least one Operation");
        }
        return body.operations;
    }

    /**
     * Lower-cased, URN-stripped attribute path, or {@code null} when the operation has no path
     * (the caller then reads the attribute names out of the value object).
     */
    public static String normalizePath(String path, String schemaUrn) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.trim();
        if (schemaUrn != null && p.toLowerCase(Locale.ROOT).startsWith(schemaUrn.toLowerCase(Locale.ROOT))) {
            p = p.substring(schemaUrn.length());
        }
        // Case-insensitive leading separators, then a filter clause is kept as-is (it is
        // normalised by the caller's switch) but lower-cased for the attribute part.
        p = p.replaceFirst("^[:/]+", "").trim();
        // "emails[type eq \"work\"]" -> "emails[type eq \"work\"]" with the attribute lower-cased.
        int bracket = p.indexOf('[');
        if (bracket > 0) {
            return p.substring(0, bracket).toLowerCase(Locale.ROOT) + p.substring(bracket);
        }
        return p.toLowerCase(Locale.ROOT);
    }

    public static void requireValue(ScimPatchRequest.Operation op) {
        if (op == null || op.value == null || op.value.isNull()) {
            throw ScimException.badRequest("invalidValue", "Operation value is required for this request");
        }
    }

    /** Field map of a path-less operation's object value; empty when the value is not an object. */
    public static Map<String, JsonNode> fields(JsonNode value) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (value != null && value.isObject()) {
            value.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** A scalar attribute value, tolerating the {@code {"value": x}} envelope connectors send. */
    public static String readString(Operation op, JsonNode value) {
        requireValueValue(value);
        if (value.isObject() && value.has("value")) {
            JsonNode inner = value.get("value");
            return inner.isNull() ? null : inner.asText();
        }
        if (value.isObject() && value.has("display")) {
            // A member reference used where a scalar was expected; its display/id is the intent.
            return value.get("display").asText();
        }
        if (value.isArray()) {
            throw ScimException.badRequest("invalidValue", "Expected a single value, got a list");
        }
        return value.asText();
    }

    /** An attribute value that is semantically a boolean. Strict on purpose. */
    public static boolean readBoolean(Operation op, JsonNode value) {
        requireValueValue(value);
        JsonNode v = value;
        if (v.isObject() && v.has("value")) {
            v = v.get("value");
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isTextual()) {
            String t = v.asText().trim();
            if ("true".equalsIgnoreCase(t)) {
                return true;
            }
            if ("false".equalsIgnoreCase(t)) {
                return false;
            }
        }
        throw ScimException.badRequest("invalidValue", "Expected a boolean value");
    }

    /**
     * Member list for a {@code members} operation. Accepts a bare array, a single object
     * ({@code {"value":"<id>"}} — Entra's remove-a-single-member shape), and the
     * {@code {"members":[...]}} wrapper some connectors add.
     */
    public static List<ScimGroup.Member> readMembers(Operation op, JsonNode value) {
        List<ScimGroup.Member> out = new ArrayList<>();
        if (value == null || value.isNull()) {
            return out;
        }
        JsonNode array = value;
        if (value.isObject() && value.has("members")) {
            array = value.get("members");
        }
        if (array.isObject()) {
            out.add(toMember(array));
            return out;
        }
        if (array.isArray()) {
            for (JsonNode n : array) {
                if (n.isObject()) {
                    out.add(toMember(n));
                } else if (!n.isNull()) {
                    ScimGroup.Member m = new ScimGroup.Member();
                    m.value = n.asText();
                    m.display = n.asText();
                    out.add(m);
                }
            }
            return out;
        }
        throw ScimException.badRequest("invalidValue", "Expected a member list");
    }

    private static ScimGroup.Member toMember(JsonNode node) {
        ScimGroup.Member m = new ScimGroup.Member();
        JsonNode v = node.get("value");
        m.value = v == null || v.isNull() ? null : v.asText();
        JsonNode d = node.get("display");
        m.display = d == null || d.isNull() ? m.value : d.asText();
        return m;
    }

    private static void requireValueValue(JsonNode value) {
        if (value == null || value.isNull()) {
            throw ScimException.badRequest("invalidValue", "Operation value is required");
        }
    }
}
