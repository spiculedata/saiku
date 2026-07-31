/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.saiku.launcher.SaikuLauncher;

/**
 * One-shot bootstrap for the saiku-launcher Jetty + WAR stack against an
 * ephemeral port and a temp saiku-home. Used by every {@code *IT} as a static
 * fixture: boot once per IT suite, share across tests, tear down at the end.
 *
 * <p>The harness keeps the boot logic out of individual test classes so the
 * test bodies focus on REST contract assertions. It also centralises the
 * helper methods for issuing HTTP requests with Basic auth and parsing JSON
 * responses.
 *
 * <p>Auth defaults to {@code admin/admin} — the in-memory seed user that
 * Saiku ships with. Tests can override per-call.
 */
public final class SaikuItHarness {

    private static final AtomicReference<SaikuItHarness> SINGLETON = new AtomicReference<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

    private final Server server;
    private final Path home;
    private final String baseUrl;
    private final HttpClient http;
    private final String adminBasicAuth;

    private SaikuItHarness(Server server, Path home) {
        this.server = server;
        this.home = home;
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        this.baseUrl = "http://127.0.0.1:" + port;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.adminBasicAuth =
                "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Boot the harness if it isn't already running, returning the singleton.
     * Concurrent callers race-safely — only one actual boot occurs.
     */
    public static synchronized SaikuItHarness shared() throws Exception {
        SaikuItHarness existing = SINGLETON.get();
        if (existing != null) {
            return existing;
        }
        Path home = Files.createTempDirectory("saiku-it-");
        // saiku#1153: the harness authenticates as the default admin/admin seed
        // user, so it must opt in to the default-credentials policy or bootServer
        // refuses to start. The IT JVM is a trusted local fixture, never exposed.
        System.setProperty("saiku.allowDefaultAdmin", "true");
        // AI data-egress policy: default is fail-closed "schema-only", which 403s every AI-data
        // egress with PERMISSION_DENIED. The data ITs need it relaxed and span BOTH egress kinds:
        // AGGREGATED_RESULT_VALUES (AiQueryIT, AiQueryExtrasIT, AdvancedAiQueryIT, AiQueryAsyncIT,
        // and AppBuilderIT via the embed tile-query path -> AiQueryResource#executeAi) and
        // RAW_ROW_DATA (DrillthroughIT#asyncDrillthrough_resolvesAcrossSessions_saiku862, which
        // needs "full"). Pin "full" (the top of schema-only < aggregated < full) so ALL data ITs
        // are self-contained instead of silently depending on the suite being run with an external
        // policy env. bootServer's resolveDemoAiPolicyDefault respects an explicit ai.policy, so
        // this stands with or without demo mode. No IT asserts a more restrictive default.
        System.setProperty("ai.policy", "full");
        // Boot port=0 → OS picks an ephemeral port. Host 127.0.0.1 to avoid
        // firewall prompts on macOS CI runners.
        Server server = SaikuLauncher.ServeCommand.bootServer(0, "127.0.0.1", "/", home);
        SaikuItHarness h = new SaikuItHarness(server, home);
        // Block until the webapp finishes its Spring init. Without this, the
        // first test races against Spring's bean wiring and gets a 503.
        h.waitForReady();
        SINGLETON.set(h);
        // Shut down at JVM exit (failsafe forks a reusable JVM per test class).
        Runtime.getRuntime().addShutdownHook(new Thread(h::stopQuietly));
        return h;
    }

    private void waitForReady() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/rest/saiku/info"))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 500) {
                    return; // Spring has finished wiring beans.
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Saiku failed to become ready within 2 minutes", last);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Path home() {
        return home;
    }

    /** Issue a GET against {@code path} with admin Basic auth. */
    public HttpResponse<String> getAuth(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", adminBasicAuth)
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Issue a POST against {@code path} with admin Basic auth + JSON body.
     *  Retries once on the JDK HttpClient's transient
     *  "HTTP/1.1 header parser received no bytes" — observed sporadically
     *  when many tests share the same client across a short window. */
    public HttpResponse<String> postAuthJson(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", adminBasicAuth)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException ioe) {
            Thread.sleep(100);
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Issue a GET against {@code path} without auth — used for testing the 401 path. */
    public HttpResponse<String> getAnon(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(HTTP_TIMEOUT)
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Issue a DELETE against {@code path} with admin Basic auth. */
    public HttpResponse<String> deleteAuth(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", adminBasicAuth)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Issue a PUT against {@code path} with admin Basic auth + JSON body. */
    public HttpResponse<String> putAuthJson(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", adminBasicAuth)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Issue a POST against {@code path} with admin Basic auth + form-encoded body. */
    public HttpResponse<String> postAuthForm(String path, String formBody) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", adminBasicAuth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Generic send escape hatch — for tests that need to roll their own request. */
    public HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public JsonNode parse(HttpResponse<String> resp) throws Exception {
        return MAPPER.readTree(resp.body());
    }

    public String adminBasicAuth() {
        return adminBasicAuth;
    }

    private void stopQuietly() {
        try {
            server.stop();
        } catch (Exception ignored) {
        }
    }
}
