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
        String field = jsonPointerToField(first.getInstanceLocation().toString());
        String message = first.getMessage();
        throw new AiValidationException(field, message, null);
    }

    /**
     * Convert a JSON Pointer (e.g. {@code /measures/0/name}) or networknt
     * instance-location (e.g. {@code $.measures[0].name}) to the dotted-array
     * notation the rest of the AI surface uses (e.g. {@code measures[].name}).
     * Keeps the {@code field} contract consistent across schema-validator-
     * driven errors and AiSchemaConverter-emitted errors — agents only ever
     * see one naming convention.
     *
     * <p>The notable simplification: array indices become {@code []}
     * regardless of the numeric index. Field-pointer consumers care about
     * "which array element" only insofar as it points to a specific node;
     * the human reading the error treats {@code measures[].name} as "the
     * name field of a measures entry" which is enough.
     */
    static String jsonPointerToField(String pointer) {
        if (pointer == null || pointer.isEmpty() || pointer.equals("$")) return "body";
        String s;
        if (pointer.startsWith("$.")) {
            s = pointer.substring(2);
        } else if (pointer.startsWith("$")) {
            s = pointer.substring(1);
        } else {
            s = pointer;
        }
        return s.replaceAll("\\[\\d+\\]", "[]");
    }
}
