/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code saiku eval} — run the agent-eval suites against a RUNNING Saiku server and report accuracy
 * (saiku#1424 / saiku#1478).
 *
 * <p>This is the out-of-process runner: it POSTs to {@code /rest/saiku/admin/ai-evals/run} on a live
 * server, which executes every suite in {@code saiku-home/evals/} <em>inside a real request</em> (so
 * the session-scoped query service resolves and queries run under the admin's data scope — a
 * background cron thread can't), persists each scored run to the H2 result store, and returns the
 * tallies. The command prints them and exits non-zero when any case failed or a suite couldn't run,
 * so CI can gate on accuracy regressions:
 *
 * <pre>{@code
 * # Nightly CI job against a running server:
 * saiku eval --server https://saiku.example.com --username admin --password ****
 * # exit 0 = all green, exit 1 = a regression, exit 2 = couldn't reach / run
 * }</pre>
 *
 * <p>Auth is HTTP Basic (CSRF-exempt on the REST surface); the account must have {@code ROLE_ADMIN}.
 */
@Command(name = "eval", description = "Run the agent-eval suites against a running Saiku server and report accuracy.")
public class EvalCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(
            names = {"-s", "--server"},
            description = "Base URL of the running Saiku server (default: ${DEFAULT-VALUE}).")
    String server = "http://localhost:8080";

    @Option(
            names = {"-u", "--username"},
            description = "Admin username (default: ${DEFAULT-VALUE}).")
    String username = "admin";

    @Option(
            names = {"-p", "--password"},
            description = "Admin password (default: ${DEFAULT-VALUE}).")
    String password = "admin";

    @Option(names = "--no-fail-on-regression", description = "Exit 0 even when a suite has failures (report-only).")
    boolean noFailOnRegression = false;

    @Option(
            names = "--timeout-minutes",
            description = "How long to wait for the sweep — LLM latency × cases (default: ${DEFAULT-VALUE}).")
    int timeoutMinutes = 15;

    @Override
    public Integer call() {
        String base = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        String url = base + "/rest/saiku/admin/ai-evals/run";
        String auth = "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> resp;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(Math.max(1, timeoutMinutes)))
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("saiku eval: could not reach " + url + " — " + e.getMessage());
            return 2;
        }

        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            System.err.println("saiku eval: authentication failed (HTTP " + resp.statusCode()
                    + ") — check --username/--password and that the account has ROLE_ADMIN.");
            return 2;
        }
        if (resp.statusCode() != 200) {
            System.err.println("saiku eval: server returned HTTP " + resp.statusCode() + " — " + resp.body());
            return 2;
        }

        JsonNode json;
        try {
            json = MAPPER.readTree(resp.body());
        } catch (Exception e) {
            System.err.println("saiku eval: could not parse response — " + e.getMessage());
            return 2;
        }

        return report(json);
    }

    /** Print the sweep tallies and derive the exit code. Package-visible for unit testing. */
    Integer report(JsonNode json) {
        System.out.println("Agent eval sweep — " + json.path("summary").asText(""));
        for (JsonNode r : json.path("results")) {
            int passed = r.path("passed").asInt();
            int total = r.path("total").asInt();
            String mark = passed >= total ? "PASS" : "FAIL";
            System.out.printf("  %-5s %-32s %d/%d%n", mark, r.path("suiteName").asText(), passed, total);
        }
        for (JsonNode s : json.path("skipped")) {
            System.out.printf(
                    "  SKIP  %-32s %s%n",
                    s.path("suiteName").asText(), s.path("reason").asText());
        }

        boolean hasFailures = json.path("hasFailures").asBoolean(false);
        if (!hasFailures) {
            System.out.println("All suites green.");
            return 0;
        }
        if (noFailOnRegression) {
            System.out.println("Regressions found (report-only mode — exit 0).");
            return 0;
        }
        System.out.println("Regressions found — failing.");
        return 1;
    }
}
