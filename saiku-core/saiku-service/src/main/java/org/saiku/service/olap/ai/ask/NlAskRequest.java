/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.List;
import java.util.Objects;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * Input to {@link NlAskProvider#ask(NlAskRequest)}.
 *
 * <p>Carries the plain-English {@code question} plus the cube context the provider needs to
 * produce a valid {@link org.saiku.service.olap.ai.AiQueryRequest}:
 *
 * <ul>
 *   <li>{@code cubeRef} — the active cube. Echoed back into the response's
 *       {@link NlAskResponse#aiQueryRequestJson()} cube field by the provider.
 *   <li>{@code cubeSchemaJson} — the {@link org.saiku.service.olap.ai.AiSchema} for that cube,
 *       serialised as JSON. The provider includes this in the system / context message so the
 *       model can only name things that actually exist.
 *   <li>{@code requestJsonSchema} — the JSON Schema describing {@code AiQueryRequest} (from
 *       {@link org.saiku.service.olap.ai.AiRequestJsonSchema#forRequest()}, serialised). Bound as
 *       the structured-output tool's {@code input_schema} so the model is forced to emit a valid
 *       shape.
 *   <li>{@code history} — prior {@code (user, assistant)} turns for multi-turn follow-ups. May be
 *       empty for a single-shot ask.
 * </ul>
 *
 * <p>Implementations MUST NOT mutate any field; the record is intentionally immutable.
 */
public record NlAskRequest(
        AiCubeRef cubeRef,
        String question,
        String cubeSchemaJson,
        String requestJsonSchema,
        List<NlAskMessage> history) {

    public NlAskRequest {
        Objects.requireNonNull(cubeRef, "cubeRef");
        Objects.requireNonNull(question, "question");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must be non-blank");
        }
        Objects.requireNonNull(cubeSchemaJson, "cubeSchemaJson");
        Objects.requireNonNull(requestJsonSchema, "requestJsonSchema");
        history = history == null ? List.of() : List.copyOf(history);
    }
}
