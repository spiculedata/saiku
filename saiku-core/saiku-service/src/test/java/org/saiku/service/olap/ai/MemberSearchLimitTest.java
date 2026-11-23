/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** saiku#2325: the member-search limit is clamped in the shared service, so REST and MCP agree. */
public class MemberSearchLimitTest {

    @Test
    public void restLimitIsClamped() {
        assertEquals(500, OlapAiCubeMetadataService.MAX_SEARCH_LIMIT);
        assertEquals(500, OlapAiCubeMetadataService.clampSearchLimit(2_000_000_000));
        assertEquals(500, OlapAiCubeMetadataService.clampSearchLimit(Integer.MAX_VALUE));
    }

    @Test
    public void nonPositiveLimitFallsBackToDefault() {
        assertEquals(20, OlapAiCubeMetadataService.clampSearchLimit(0));
        assertEquals(20, OlapAiCubeMetadataService.clampSearchLimit(-5));
    }

    @Test
    public void limitWithinBoundsIsHonoured() {
        assertEquals(1, OlapAiCubeMetadataService.clampSearchLimit(1));
        assertEquals(500, OlapAiCubeMetadataService.clampSearchLimit(500));
    }
}
