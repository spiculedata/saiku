/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Output of the {@code emit_insight} tool — a natural-language analysis the model produced about
 * the user's current cellset. Returned by {@code POST /saiku/api/ai/ask} when the model picks the
 * insight intent (e.g. "spot any trends in this data", "what's interesting here").
 *
 * <p>Pure-data render contract: the UI renders {@link #markdown} as markdown inside the AI Query
 * drawer's chat thread. No execution side effects.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AiInsight {

    /** The analysis itself. Markdown is preferred — the UI renders it as such. */
    private String markdown;

    /** Optional 1-line headline shown above the markdown body in the drawer. */
    private String headline;

    public AiInsight() {}

    public AiInsight(String markdown, String headline) {
        this.markdown = markdown;
        this.headline = headline;
    }

    public String getMarkdown() {
        return markdown;
    }

    public void setMarkdown(String v) {
        this.markdown = v;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String v) {
        this.headline = v;
    }
}
