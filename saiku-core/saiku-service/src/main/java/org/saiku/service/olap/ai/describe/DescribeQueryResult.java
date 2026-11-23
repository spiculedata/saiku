/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

/**
 * Outcome of {@link TileDescribeService#describe(String)}. Either a populated
 * {@code title}/{@code description} pair, or a {@code degraded} result carrying a
 * short {@code reason} the caller can log / map to an HTTP status — mirrors the
 * "never throw" contract used elsewhere on the AI surface (see {@code NlAskResponse},
 * {@code EnrichResponse}).
 */
public record DescribeQueryResult(
        boolean degraded,
        String title,
        String description,
        String reason,
        String model,
        int promptTokens,
        int responseTokens) {

    public static DescribeQueryResult ok(
            String title, String description, String model, int promptTokens, int responseTokens) {
        return new DescribeQueryResult(false, title, description, null, model, promptTokens, responseTokens);
    }

    public static DescribeQueryResult degraded(String reason) {
        return new DescribeQueryResult(true, null, null, reason, null, -1, -1);
    }
}
