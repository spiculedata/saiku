/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.io.IOException;

/**
 * Consumer of a provider's *live* token stream (saiku#1484).
 *
 * <p>A {@link NlAskProvider} that speaks a streaming API calls these callbacks in wire order as the
 * model emits:
 *
 * <ol>
 *   <li>{@link #onModel(String)} — once, from the first provider event (which carries the model id).
 *   <li>{@link #onToolSelected(String)} — once the provider has told us which tool the model is
 *       calling. This is the streaming equivalent of the {@code intent} event, and it always
 *       arrives before the first {@link #onDelta(String)} for a prose tool, so the consumer can
 *       label the tokens it is about to receive.
 *   <li>{@link #onDelta(String)} — repeatedly, with a piece of the tool's prose. Only prose
 *       fields stream: the insight markdown, the email summary, the view-change reason. A
 *       structured tool whose payload is a query (or a dashboard spec) emits no deltas — there is
 *       nothing human-readable to show while the JSON is still being assembled.
 * </ol>
 *
 * <p>Deltas are NOT guaranteed to be single tokens — providers batch differently, and a tool
 * input arrives as JSON string fragments. They are, however, emitted <em>as they arrive</em> rather
 * than replayed from a completed response, which is the whole point: first-token latency is the
 * provider's, not ours.
 *
 * <p>{@link #onDelta} (and the two events before it) may throw {@link IOException} when the
 * downstream consumer (an SSE client socket, typically) goes away. The provider converts that into a
 * degraded response rather than throwing — the SPI contract is "never throw", unchanged from
 * {@link NlAskProvider#ask}.
 */
public interface NlAskStreamListener {

    /** Discards everything. Useful for callers that want the response but not the token stream. */
    NlAskStreamListener NOOP = new NlAskStreamListener() {
        @Override
        public void onModel(String model) {}

        @Override
        public void onToolSelected(String toolName) {}

        @Override
        public void onDelta(String delta) {}
    };
    /**
     * The model id that answered, known as soon as the provider's first event lands — before any
     * content. May be {@code null} if the provider streams anonymously (some OpenAI-compatible
     * gateways omit it).
     */
    void onModel(String model) throws IOException;

    /** The model committed to a tool. One of the {@code AbstractNlAskProvider} tool-name constants. */
    void onToolSelected(String toolName) throws IOException;

    /** A piece of the tool's prose. Never {@code null}; empty deltas are not delivered. */
    void onDelta(String delta) throws IOException;

    /**
     * A new agentic step is starting (only the chained-ask loop has more than one). Default no-op
     * so single-turn listeners need nothing; a consumer that renders several steps — the chained
     * endpoint tags each event with the step it belongs to — overrides it.
     *
     * @param index zero-based position of the step about to run
     */
    default void onStepStart(int index) {}

    /**
     * Map a tool name to the intent kind a client cares about, or {@code null} when the tool has no
     * streamed intent — the refusal tool, which is a degradation, and any unknown name (forward
     * compatibility with a provider that advertises a tool this build doesn't know).
     */
    static NlAskResponse.Kind kindForTool(String toolName) {
        if (toolName == null) {
            return null;
        }
        if (AbstractNlAskProvider.TOOL_NAME.equals(toolName)) {
            return NlAskResponse.Kind.QUERY;
        }
        if (AbstractNlAskProvider.INSIGHT_TOOL_NAME.equals(toolName)) {
            return NlAskResponse.Kind.INSIGHT;
        }
        if (AbstractNlAskProvider.VIEW_CHANGE_TOOL_NAME.equals(toolName)) {
            return NlAskResponse.Kind.VIEW_CHANGE;
        }
        if (AbstractNlAskProvider.EMAIL_DRAFT_TOOL_NAME.equals(toolName)) {
            return NlAskResponse.Kind.EMAIL_DRAFT;
        }
        if (AbstractNlAskProvider.DASHBOARD_TOOL_NAME.equals(toolName)) {
            return NlAskResponse.Kind.DASHBOARD;
        }
        return null;
    }
}
