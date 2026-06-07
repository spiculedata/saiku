/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Objects;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiRequestJsonSchema;
import org.saiku.service.olap.ai.AiSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator for the natural-language ask layer.
 *
 * <p>Composes three pieces:
 *
 * <ol>
 *   <li>Loads the {@link AiSchema} for the requested cube via the existing
 *       {@link AiCubeMetadataService} (same path as {@code /ai/schema}). The schema is the only
 *       grounding the LLM gets — there's nothing else it can use to invent names.
 *   <li>Calls the configured {@link NlAskProvider} with the schema, the AiQueryRequest JSON Schema,
 *       and the user's question + history.
 *   <li>Deserialises the model's JSON output into an {@link AiQueryRequest} so the caller can route
 *       it through the existing {@code /ai/query} converter unchanged.
 * </ol>
 *
 * <p>Execution of the resulting {@link AiQueryRequest} is the resource's concern; this service
 * stops at producing a typed request the user could have authored by hand.
 */
public class AiAskService {

    private static final Logger log = LoggerFactory.getLogger(AiAskService.class);

    private final AiCubeMetadataService metadataService;
    private final NlAskProvider provider;
    private final ObjectMapper mapper;

    public AiAskService(AiCubeMetadataService metadataService, NlAskProvider provider) {
        this(metadataService, provider, defaultMapper());
    }

    public AiAskService(AiCubeMetadataService metadataService, NlAskProvider provider, ObjectMapper mapper) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Whether the underlying provider can answer a real request. Surfaced by the
     * {@code /ai/ask/health} endpoint so the UI can hide the "Ask the AI" button on instances that
     * haven't wired up an LLM key.
     */
    public boolean isConfigured() {
        return provider.isConfigured();
    }

    /** Result of an {@link #ask(AiCubeRef, String, List)} call. */
    public record AskOutcome(boolean degraded, String reason, AiQueryRequest request, String model) {
        public static AskOutcome ok(AiQueryRequest request, String model) {
            return new AskOutcome(false, null, request, model);
        }

        public static AskOutcome degraded(String reason, String model) {
            return new AskOutcome(true, reason, null, model);
        }
    }

    /**
     * Translate a natural-language question into an {@link AiQueryRequest} grounded on the cube
     * pointed to by {@code ref}.
     *
     * @param ref cube to target; must be non-null and have a non-null cubeName
     * @param question free-form English question; must be non-blank
     * @param history prior turns; may be null / empty for single-shot asks
     */
    public AskOutcome ask(AiCubeRef ref, String question, List<NlAskMessage> history) {
        if (ref == null) {
            return AskOutcome.degraded("cube ref required", null);
        }
        if (question == null || question.isBlank()) {
            return AskOutcome.degraded("question must be non-blank", null);
        }

        AiSchema schema;
        try {
            schema = metadataService.getSchema(ref);
        } catch (RuntimeException e) {
            log.warn("Failed to load schema for {} — cannot ask AI", ref, e);
            return AskOutcome.degraded("failed to load cube schema: " + e.getMessage(), null);
        }

        String schemaJson;
        String requestSchemaJson;
        try {
            schemaJson = mapper.writeValueAsString(schema);
            requestSchemaJson = mapper.writeValueAsString(AiRequestJsonSchema.forRequest());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise schema / request-schema for ask", e);
            return AskOutcome.degraded("schema serialisation failed", null);
        }

        NlAskRequest req =
                new NlAskRequest(ref, question, schemaJson, requestSchemaJson, history == null ? List.of() : history);
        NlAskResponse resp = provider.ask(req);

        if (resp.degraded()) {
            return AskOutcome.degraded(resp.reason(), resp.model());
        }

        try {
            AiQueryRequest parsed = mapper.readValue(resp.aiQueryRequestJson(), AiQueryRequest.class);
            return AskOutcome.ok(parsed, resp.model());
        } catch (JsonProcessingException e) {
            log.warn("Provider returned non-AiQueryRequest JSON: {}", e.getMessage());
            return AskOutcome.degraded("provider emitted invalid AiQueryRequest JSON: " + e.getMessage(), resp.model());
        }
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper m = new ObjectMapper();
        // Keep the schema-as-context terse — Saiku's AiSchema toggles already control includes.
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
