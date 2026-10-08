/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.io.IOException;

/**
 * Word-by-word prose replay for providers that haven't implemented real streaming (saiku#1484).
 *
 * <p>Wired as the default {@link NlAskProvider#askStreaming(NlAskRequest, NlAskStreamListener)}:
 * the response is already complete, so all that can be done is split its prose field into
 * word-boundary deltas and hand them over. That is the pre-#1484 behaviour, preserved verbatim for
 * {@link NoopNlAskProvider}, for tests, and for any provider whose transport can't stream — so the
 * client contract never depends on which provider is wired.
 *
 * <p>Only the prose fields stream, matching what the real decoders emit: an insight's markdown, an
 * email draft's summary, a view change's reason. {@code emit_query} and {@code emit_dashboard}
 * carry no prose and produce no deltas.
 */
final class ProseFallback {

    private ProseFallback() {}

    static void replay(NlAskResponse response, NlAskStreamListener listener) {
        try {
            if (response.model() != null) {
                listener.onModel(response.model());
            }
            String toolName = toolNameFor(response.kind());
            if (toolName == null) {
                return;
            }
            listener.onToolSelected(toolName);
            // Same scanner the streaming decoders use, so the replayed fields and their order are
            // identical to what a real stream would have delivered — the client cannot tell which
            // provider (or which transport) produced the turn.
            String prose = new ProseDeltaScanner().accept(response.payloadJson());
            if (prose.isEmpty()) {
                return;
            }
            for (String word : splitOnWordBoundaries(prose)) {
                listener.onDelta(word);
            }
        } catch (IOException e) {
            // The consumer (an SSE client socket) is gone. Nothing to recover: the caller still
            // gets the completed response, it just won't be rendered token by token.
        }
    }

    /** The tool name that produced {@code kind}, or null when the kind has no streaming tool. */
    private static String toolNameFor(NlAskResponse.Kind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case QUERY -> AbstractNlAskProvider.TOOL_NAME;
            case INSIGHT -> AbstractNlAskProvider.INSIGHT_TOOL_NAME;
            case VIEW_CHANGE -> AbstractNlAskProvider.VIEW_CHANGE_TOOL_NAME;
            case EMAIL_DRAFT -> AbstractNlAskProvider.EMAIL_DRAFT_TOOL_NAME;
            case DASHBOARD -> AbstractNlAskProvider.DASHBOARD_TOOL_NAME;
            case REFUSAL, MCP_TOOL -> null;
        };
    }
    /**
     * The prose to replay for {@code response}, read straight out of the tool payload with the same
     * scanner the streaming decoders use.
     */
    /**
     * Split {@code prose} at word boundaries, keeping each chunk's trailing whitespace attached so
     * a client can concatenate the deltas without having to guess the spacing.
     */
    static java.util.List<String> splitOnWordBoundaries(String prose) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int len = prose.length();
        int i = 0;
        while (i < len) {
            int wordEnd = i;
            while (wordEnd < len && !Character.isWhitespace(prose.charAt(wordEnd))) {
                wordEnd++;
            }
            while (wordEnd < len && Character.isWhitespace(prose.charAt(wordEnd))) {
                wordEnd++;
            }
            if (wordEnd > i) {
                out.add(prose.substring(i, wordEnd));
            }
            i = wordEnd;
        }
        return out;
    }
}
