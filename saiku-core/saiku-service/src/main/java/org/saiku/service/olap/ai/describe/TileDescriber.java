/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

/**
 * SPI for the saiku#909 tile-naming upstream call — mirrors the shape of
 * {@code NlAskProvider} / {@code LlmProvider} elsewhere on the AI surface, kept
 * separate from {@link TileDescribeService} so callers outside this package
 * (notably {@code AiQueryResource}'s tests) can inject a fake without a real
 * {@link java.net.http.HttpClient}.
 */
public interface TileDescriber {

    /** True only when an upstream provider is actually configured (e.g. an API key is set). */
    boolean isConfigured();

    /**
     * Ask the model to name the tile described by {@code querySummaryJson} (the
     * output of {@link QueryStructureSummarizer#summarize}). Never throws —
     * transport / upstream / parse failures come back as a
     * {@link DescribeQueryResult#degraded(String)}.
     */
    DescribeQueryResult describe(String querySummaryJson);
}
