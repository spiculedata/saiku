/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for the {@link EvalCommand} — argument parsing, path-vs-name detection, and text
 * report formatting. The HTTP round-trip is exercised by a live-integration test path (deferred);
 * this file focuses on the client-side logic that's easy to test in isolation.
 */
public class EvalCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void suiteNameProducesSuiteNameBody() throws Exception {
        EvalCommand cmd = new EvalCommand();
        String body = cmd.buildRequestBody("foodmart-sales-evals");
        JsonNode node = JSON.readTree(body);
        assertEquals("foodmart-sales-evals", node.path("suiteName").asText());
        assertTrue("must not carry a suiteYaml payload", node.path("suiteYaml").isMissingNode());
    }

    @Test
    public void existingFilePathProducesSuiteYamlBody() throws Exception {
        Path yaml = tmp.newFile("adhoc.eval.yaml").toPath();
        String yamlContent = "name: adhoc-suite\ncube:\n  connectionName: c\n  catalog: cat\n"
                + "  schema: s\n  cubeName: Sales\ncases:\n  - name: a\n    question: q\n";
        Files.writeString(yaml, yamlContent, StandardCharsets.UTF_8);

        EvalCommand cmd = new EvalCommand();
        String body = cmd.buildRequestBody(yaml.toString());
        JsonNode node = JSON.readTree(body);
        assertTrue("must carry a suiteYaml payload", node.has("suiteYaml"));
        assertEquals(yamlContent, node.path("suiteYaml").asText());
        assertTrue("must not carry a suiteName", node.path("suiteName").isMissingNode());
    }

    @Test
    public void missingFilePathFallsBackToSuiteName() throws Exception {
        // A path-shaped arg that doesn't exist as a file: the CLI treats it as a suite name and
        // lets the server-side registry lookup decide. This keeps "typo in filename" from silently
        // becoming an inline-YAML post with garbage content.
        EvalCommand cmd = new EvalCommand();
        String body = cmd.buildRequestBody("/nonexistent/path/foo.yaml");
        JsonNode node = JSON.readTree(body);
        assertEquals("/nonexistent/path/foo.yaml", node.path("suiteName").asText());
        assertTrue(node.path("suiteYaml").isMissingNode());
    }

    @Test
    public void textFormatPrintsSummary() throws Exception {
        JsonNode report = JSON.readTree("{\"suiteName\":\"my-suite\","
                + "\"suiteDescription\":\"the description\","
                + "\"passedCount\":2,\"failedCount\":1,\"degradedCount\":0,\"skippedCount\":0,"
                + "\"totalDurationMs\":1234,"
                + "\"outcomes\":["
                + "{\"caseName\":\"case-a\",\"status\":\"PASS\",\"durationMs\":100,"
                + "\"actualIntent\":\"QUERY\",\"actualModel\":\"claude-x\",\"mismatches\":[]},"
                + "{\"caseName\":\"case-b\",\"status\":\"FAIL\",\"durationMs\":200,"
                + "\"actualIntent\":\"QUERY\",\"actualModel\":\"claude-x\","
                + "\"mismatches\":[{\"path\":\"rows[0].country\",\"message\":\"expected USA got Mexico\"}]}"
                + "]}");

        String output = captureStdout(() -> EvalCommand.printText(report));
        assertTrue("summary line", output.contains("2/2 passed, 1 failed, 0 degraded"));
        assertTrue("suite name", output.contains("Suite: my-suite"));
        assertTrue("description", output.contains("Description: the description"));
        assertTrue("elapsed", output.contains("elapsed 1234ms"));
        assertTrue("pass line", output.contains("PASS: case-a"));
        assertTrue("fail line", output.contains("FAIL: case-b"));
        assertTrue("mismatch surfaced", output.contains("rows[0].country: expected USA got Mexico"));
    }

    @Test
    public void textFormatOmitsEmptyDescription() throws Exception {
        JsonNode report = JSON.readTree("{\"suiteName\":\"n\",\"suiteDescription\":\"\","
                + "\"passedCount\":1,\"failedCount\":0,\"degradedCount\":0,\"skippedCount\":0,"
                + "\"totalDurationMs\":0,\"outcomes\":[]}");
        String output = captureStdout(() -> EvalCommand.printText(report));
        assertTrue("no Description: prefix when empty", !output.contains("Description:"));
    }

    /* ---- helpers ---- */

    private static String captureStdout(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
