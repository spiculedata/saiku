/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

import org.saiku.service.olap.ai.AiQueryRequest;

/** Body for {@code POST /saiku/api/ai/describe-query}: {@code {"query": <AiQueryRequest>}}. */
public class DescribeQueryApiRequest {

    private AiQueryRequest query;

    public AiQueryRequest getQuery() {
        return query;
    }

    public void setQuery(AiQueryRequest v) {
        this.query = v;
    }
}
