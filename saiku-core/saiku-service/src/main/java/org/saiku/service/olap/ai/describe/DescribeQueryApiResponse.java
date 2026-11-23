/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

/** Success body for {@code POST /saiku/api/ai/describe-query}. */
public class DescribeQueryApiResponse {

    private String suggestedTitle;
    private String suggestedDescription;

    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    public void setSuggestedTitle(String v) {
        this.suggestedTitle = v;
    }

    public String getSuggestedDescription() {
        return suggestedDescription;
    }

    public void setSuggestedDescription(String v) {
        this.suggestedDescription = v;
    }
}
