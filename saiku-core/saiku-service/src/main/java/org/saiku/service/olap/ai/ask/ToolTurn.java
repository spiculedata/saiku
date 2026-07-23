/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.Objects;

/**
 * One completed tool step in a server-side agentic ask loop, ready to replay to the provider on the
 * next turn: the tool the model called plus the result the server fed back.
 *
 * <p>Distinct from {@link NlAskMessage} (which only carries user/assistant prose): a tool step needs
 * the provider-assigned call id, the tool name, the exact input JSON the model emitted, and the
 * result digest. Providers render this into their own multi-turn wire shape (Anthropic
 * tool_use/tool_result blocks; OpenAI tool_calls + role:"tool" messages).
 *
 * @param toolCallId provider-assigned id linking the tool call to its result (Anthropic tool_use id
 *     / OpenAI tool_call id). Non-blank.
 * @param toolName the tool the model called, e.g. {@code "emit_query"}. Non-blank.
 * @param toolInputJson the raw JSON arguments the model emitted for the tool. Non-null (may be an
 *     empty object {@code "{}"}).
 * @param resultDigest the server's result fed back to the model. {@code null} when withheld by the
 *     egress policy (schema-only) — the provider must still render a tool_result, using an empty /
 *     "result withheld" body, so the transcript stays well-formed.
 */
public record ToolTurn(String toolCallId, String toolName, String toolInputJson, String resultDigest) {
    public ToolTurn {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(toolInputJson, "toolInputJson");
        if (toolCallId.isBlank()) throw new IllegalArgumentException("toolCallId must be non-blank");
        if (toolName.isBlank()) throw new IllegalArgumentException("toolName must be non-blank");
        // resultDigest intentionally nullable (egress-withheld case).
    }
}
