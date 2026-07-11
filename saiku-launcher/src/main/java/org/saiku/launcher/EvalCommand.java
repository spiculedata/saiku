/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code saiku eval} — run an agent-eval suite against a running launcher (saiku#1424 follow-up).
 *
 * <p>Wraps the {@code POST /rest/saiku/api/ai/evals/run} endpoint so operators + CI can invoke
 * a suite without wiring curl + jq into every job:
 *
 * <pre>{@code
 * saiku eval foodmart-sales-evals \
 *   --server http://localhost:8080 \
 *   --user admin --password admin
 * }</pre>
 *
 * <p>The positional {@code SUITE} arg is either a registered suite name (looked up server-side
 * against {@code saiku-home/evals/}) or a path to a YAML file on the local filesystem. The
 * distinguishing test is simple: if the arg exists as a readable file, it's treated as a path
 * and the YAML is posted inline; otherwise it's treated as a suite name.
 *
 * <p>Exit codes:
 *
 * <ul>
 *   <li>{@code 0} — every case passed.
 *   <li>{@code 1} — at least one case failed or degraded. The report is still printed.
 *   <li>{@code 2} — HTTP error (network / auth / 4xx / 5xx). Server response printed to stderr.
 *   <li>{@code 3} — argument error (missing suite arg, unreadable file, bad flag).
 * </ul>
 *
 * <p>This command is a pure HTTP client — no Spring context, no in-process launcher. The server
 * must already be running (typically via {@code saiku serve}).
 */
@Command(name = "eval", description = "Run an agent-eval suite against a running launcher.")
public class EvalCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(
            index = "0",
            paramLabel = "SUITE",
            description = "Suite name (registered on the server) OR path to a local YAML file.")
    String suite;

    @Option(
            names = {"-s", "--server"},
            description = "Launcher URL (default: http://localhost:8080).",
            defaultValue = "http://localhost:8080")
    String server;

    @Option(
            names = {"-u", "--user"},
            description = "Username for HTTP Basic auth (default: admin).",
            defaultValue = "admin")
    String user;

    @Option(
            names = {"-P", "--password"},
            description = "Password for HTTP Basic auth (default: admin). "
                    + "Prefer setting SAIKU_EVAL_PASSWORD in the environment instead.",
            defaultValue = "admin")
    String password;

    @Option(
            names = {"--format"},
            description = "Output format: text (default) or json.",
            defaultValue = "text")
    String format;

    @Option(
            names = {"--timeout"},
            description = "Server request timeout in seconds (default 300 — evals can take minutes).",
            defaultValue = "300")
    int timeoutSeconds;

    @Option(
            names = {"--fail-on-degraded"},
            description = "Exit non-zero when any case degrades (default: true).",
            defaultValue = "true",
            negatable = true)
    boolean failOnDegraded;

    @Override
    public Integer call() {
        // Pull password from env if set — SAIKU_EVAL_PASSWORD beats the --password default so CI
        // doesn't have to pass the password on argv (visible in process listings).
        String envPassword = System.getenv("SAIKU_EVAL_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            password = envPassword;
        }

        if (suite == null || suite.isBlank()) {
            System.err.println("saiku eval: SUITE argument is required");
            return 3;
        }
        if (!"text".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
            System.err.println("saiku eval: --format must be 'text' or 'json' (got '" + format + "')");
            return 3;
        }

        String requestBody;
        try {
            requestBody = buildRequestBody(suite);
        } catch (IOException e) {
            System.err.println("saiku eval: cannot read suite file '" + suite + "': " + e.getMessage());
            return 3;
        }

        String url = server.replaceAll("/+$", "") + "/rest/saiku/api/ai/evals/run";
        String auth =
                "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("saiku eval: transport error hitting " + url + ": " + e.getMessage());
            return 2;
        }

        if (res.statusCode() / 100 != 2) {
            System.err.println("saiku eval: HTTP " + res.statusCode() + " from " + url);
            System.err.println(res.body());
            return 2;
        }

        JsonNode report;
        try {
            report = MAPPER.readTree(res.body());
        } catch (IOException e) {
            System.err.println("saiku eval: cannot parse server response as JSON: " + e.getMessage());
            System.err.println(res.body());
            return 2;
        }

        if ("json".equalsIgnoreCase(format)) {
            System.out.println(res.body());
        } else {
            printText(report);
        }

        int failed = report.path("failedCount").asInt(0);
        int degraded = report.path("degradedCount").asInt(0);
        if (failed > 0) return 1;
        if (failOnDegraded && degraded > 0) return 1;
        return 0;
    }

    /**
     * Build the request body. If {@code suite} points at a readable file, post it inline as
     * {@code suiteYaml}; otherwise treat as a registered {@code suiteName}.
     */
    String buildRequestBody(String suite) throws IOException {
        Path p = Path.of(suite);
        if (Files.isReadable(p)) {
            String yaml = Files.readString(p, StandardCharsets.UTF_8);
            return MAPPER.writeValueAsString(java.util.Map.of("suiteYaml", yaml));
        }
        return MAPPER.writeValueAsString(java.util.Map.of("suiteName", suite));
    }

    /**
     * Render an {@code EvalReport} JSON node as a human-readable text summary. Mirrors the
     * {@code EvalReportWriter.toText} format that the service module produces — kept in sync by
     * hand rather than a shared library so the CLI doesn't need the whole {@code saiku-service}
     * classpath.
     */
    static void printText(JsonNode report) {
        String suiteName = report.path("suiteName").asText("(unnamed)");
        String description = report.path("suiteDescription").asText("");
        System.out.println("Suite: " + suiteName);
        if (!description.isEmpty()) {
            System.out.println("Description: " + description);
        }
        int total = report.path("outcomes").isArray() ? report.path("outcomes").size() : 0;
        int passed = report.path("passedCount").asInt(0);
        int failed = report.path("failedCount").asInt(0);
        int degraded = report.path("degradedCount").asInt(0);
        int skipped = report.path("skippedCount").asInt(0);
        long duration = report.path("totalDurationMs").asLong(0);
        System.out.println(passed + "/" + total + " passed, " + failed + " failed, " + degraded + " degraded, "
                + skipped + " skipped (elapsed " + duration + "ms)");
        System.out.println();

        for (JsonNode outcome : report.path("outcomes")) {
            String status = outcome.path("status").asText("?");
            String caseName = outcome.path("caseName").asText("(unnamed)");
            long caseDuration = outcome.path("durationMs").asLong(0);
            String intent = outcome.path("actualIntent").asText("null");
            String model = outcome.path("actualModel").asText("null");
            System.out.println(
                    status + ": " + caseName + " (" + caseDuration + "ms, intent=" + intent + ", model=" + model + ")");
            for (JsonNode mismatch : outcome.path("mismatches")) {
                System.out.println("  " + mismatch.path("path").asText("") + ": "
                        + mismatch.path("message").asText(""));
            }
        }
    }

    /** For tests that want to invoke the command with a pre-built HttpClient shim. */
    public static int runWith(String[] args) {
        return new CommandLine(new EvalCommand()).execute(args);
    }
}
