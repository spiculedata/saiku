/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Output of the {@code emit_email_draft} tool — an email body the model composed from the user's
 * current cellset. Returned by {@code POST /saiku/api/ai/ask} when the model picks the email-draft
 * intent (e.g. "draft an email summarising this for my manager").
 *
 * <p>Draft-only: the AI never sends. The UI opens a pre-filled email composer with {@link
 * #summary} so the human can review and send.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AiEmailDraft {

    /** The composed email body. */
    private String summary;

    public AiEmailDraft() {}

    public AiEmailDraft(String summary) {
        this.summary = summary;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String v) {
        this.summary = v;
    }
}
