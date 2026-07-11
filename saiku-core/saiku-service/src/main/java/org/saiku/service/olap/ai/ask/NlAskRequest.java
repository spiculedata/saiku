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
 *   <li>{@code cellsetDigest} — optional markdown / text digest of the user's currently-rendered
 *       cellset. Only set when the user already has results on screen and the AI policy permits
 *       data passthrough. Lets the model see the data shape so it can route to the {@code
 *       emit_insight} or {@code emit_view_change} tools (trend analysis / "switch to chart"). When
 *       {@code null}, the model can still produce queries from the schema alone but can't do
 *       insight or sensibly route view changes.
 * </ul>
 *
 * <p>Implementations MUST NOT mutate any field; the record is intentionally immutable.
 */
public record NlAskRequest(
        AiCubeRef cubeRef,
        String question,
        String cubeSchemaJson,
        String requestJsonSchema,
        List<NlAskMessage> history,
        String cellsetDigest,
        ForceTool forceTool,
        String currentQueryJson,
        String skillsFragment) {

    /**
     * Optional override for the tool the LLM is allowed to call. Default (null/{@code AUTO}) leaves
     * the full four-tool picker on. Setting one of {@link #QUERY}/{@link #INSIGHT}/{@link
     * #VIEW_CHANGE} narrows the provider's tool list to just that one + the refusal tool, so the
     * model is forced into the user-chosen intent and can't auto-route to a different one.
     *
     * <p>Refusal stays available whichever mode is picked — even an explicitly-forced "query" turn
     * should be able to refuse if the question is off-topic.
     */
    public enum ForceTool {
        AUTO,
        QUERY,
        INSIGHT,
        VIEW_CHANGE
    }

    public NlAskRequest {
        Objects.requireNonNull(cubeRef, "cubeRef");
        Objects.requireNonNull(question, "question");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must be non-blank");
        }
        Objects.requireNonNull(cubeSchemaJson, "cubeSchemaJson");
        Objects.requireNonNull(requestJsonSchema, "requestJsonSchema");
        history = history == null ? List.of() : List.copyOf(history);
        if (forceTool == null) forceTool = ForceTool.AUTO;
    }

    /** Pre-skills ctor — kept for callers that don't have a skills catalogue. */
    public NlAskRequest(
            AiCubeRef cubeRef,
            String question,
            String cubeSchemaJson,
            String requestJsonSchema,
            List<NlAskMessage> history,
            String cellsetDigest,
            ForceTool forceTool,
            String currentQueryJson) {
        this(
                cubeRef,
                question,
                cubeSchemaJson,
                requestJsonSchema,
                history,
                cellsetDigest,
                forceTool,
                currentQueryJson,
                null);
    }

    /** Pre-forceTool ctor — kept for callers that don't need the override. */
    public NlAskRequest(
            AiCubeRef cubeRef,
            String question,
            String cubeSchemaJson,
            String requestJsonSchema,
            List<NlAskMessage> history,
            String cellsetDigest) {
        this(cubeRef, question, cubeSchemaJson, requestJsonSchema, history, cellsetDigest, ForceTool.AUTO, null, null);
    }

    /** Pre-currentQueryJson ctor — for callers that don't have a current-query snapshot. */
    public NlAskRequest(
            AiCubeRef cubeRef,
            String question,
            String cubeSchemaJson,
            String requestJsonSchema,
            List<NlAskMessage> history,
            String cellsetDigest,
            ForceTool forceTool) {
        this(cubeRef, question, cubeSchemaJson, requestJsonSchema, history, cellsetDigest, forceTool, null, null);
    }

    /**
     * Pre-multi-tool ctor — kept so call sites that don't have a cellset digest stay terse. New
     * code passing a digest should use the 6- or 7-arg canonical ctor.
     */
    public NlAskRequest(
            AiCubeRef cubeRef,
            String question,
            String cubeSchemaJson,
            String requestJsonSchema,
            List<NlAskMessage> history) {
        this(cubeRef, question, cubeSchemaJson, requestJsonSchema, history, null, ForceTool.AUTO, null, null);
    }
}
