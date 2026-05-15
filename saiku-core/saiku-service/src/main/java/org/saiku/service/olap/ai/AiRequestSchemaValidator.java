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
        String message = first.getMessage();
        throw new AiValidationException(field, message, availableFor(first));
    }

    /** Extract the legal values from a validation message when the schema
     *  has an {@code enum} constraint at the failing location. Agents
     *  rely on the {@code available[]} list to self-correct without
     *  needing to parse the error string. Returns null for non-enum
     *  violations — the exception's constructor coerces null to empty.
     *
     *  <p>networknt's {@code getSchemaNode()} for enum violations
     *  points <i>at</i> the enum array, not the wrapping schema object —
     *  so we iterate it directly. */
    static List<String> availableFor(ValidationMessage msg) {
        if (!"enum".equals(msg.getType())) return null;
        JsonNode schemaNode = msg.getSchemaNode();
        if (schemaNode == null || !schemaNode.isArray() || schemaNode.isEmpty()) return null;
        List<String> out = new ArrayList<>(schemaNode.size());
        for (JsonNode v : schemaNode) {
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
