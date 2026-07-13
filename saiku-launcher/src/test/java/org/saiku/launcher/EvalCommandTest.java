/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/** Exit-code logic for {@code saiku eval} — the contract CI gates on. */
public class EvalCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    public void allGreenExitsZero() throws Exception {
        EvalCommand cmd = new EvalCommand();
        JsonNode body = json("{\"hasFailures\":false,\"summary\":\"6/6\",\"results\":[],\"skipped\":[]}");
        assertEquals(Integer.valueOf(0), cmd.report(body));
    }

    @Test
    public void regressionExitsOne() throws Exception {
        EvalCommand cmd = new EvalCommand();
        JsonNode body = json(
                "{\"hasFailures\":true,\"summary\":\"5/6\",\"results\":[{\"suiteName\":\"s\",\"passed\":5,\"total\":6}],\"skipped\":[]}");
        assertEquals(Integer.valueOf(1), cmd.report(body));
    }

    @Test
    public void skippedSuiteCountsAsFailure() throws Exception {
        EvalCommand cmd = new EvalCommand();
        JsonNode body = json(
                "{\"hasFailures\":true,\"summary\":\"0 run, 1 skipped\",\"results\":[],\"skipped\":[{\"suiteName\":\"bad\",\"reason\":\"MISSING_FIELD\"}]}");
        assertEquals(Integer.valueOf(1), cmd.report(body));
    }

    @Test
    public void reportOnlyModeExitsZeroDespiteRegression() throws Exception {
        EvalCommand cmd = new EvalCommand();
        cmd.noFailOnRegression = true;
        JsonNode body = json("{\"hasFailures\":true,\"summary\":\"5/6\",\"results\":[],\"skipped\":[]}");
        assertEquals(Integer.valueOf(0), cmd.report(body));
    }
}
