/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Thin wrapper around Saiku's REST surface. Holds a session cookie via
 * {@link CookieManager} and transparently re-logs-in on 401. Designed for
 * the {@link SaikuMcpServer} stdio loop — one client per process,
 * single-threaded use.
 *
 * <p>This client does no schema awareness — every method returns the raw
 * server response body as a {@link JsonNode}. Conversion to MCP
 * {@code CallToolResult} happens in the server class.
 */
final class SaikuRestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String user;
    private final String pass;
    private final HttpClient http;
    private boolean loggedIn = false;

    SaikuRestClient(String baseUrl, String user, String pass) {
        // Strip trailing slash so path-joins are consistent.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.user = user;
        this.pass = pass;
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .cookieHandler(cm)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** GET {@code /rest/saiku/api/ai/cubes}. */
    JsonNode listCubes() throws IOException, InterruptedException {
        return getJson("/rest/saiku/api/ai/cubes");
    }

    /** GET {@code /rest/saiku/api/ai/schema/{cubeId}}. {@code cubeId} is
     *  {@code connection/catalog/schema/cubeName} — passed through as a
     *  multi-segment path (no encoding of the slashes). */
    JsonNode describeCube(String cubeId) throws IOException, InterruptedException {
        return getJson("/rest/saiku/api/ai/schema/" + cubeId);
    }

    /** GET {@code /rest/saiku/api/ai/members/search}. */
    JsonNode searchMembers(String cubeId, String dimension, String hierarchy, String level, String q, Integer limit)
            throws IOException, InterruptedException {
        StringBuilder p = new StringBuilder("/rest/saiku/api/ai/members/search?cubeId=").append(enc(cubeId));
        if (dimension != null) p.append("&dimension=").append(enc(dimension));
        if (hierarchy != null) p.append("&hierarchy=").append(enc(hierarchy));
        if (level != null) p.append("&level=").append(enc(level));
        if (q != null) p.append("&q=").append(enc(q));
        if (limit != null) p.append("&limit=").append(limit);
        return getJson(p.toString());
    }

    /** POST {@code /rest/saiku/api/ai/query}. {@code format} is the
     *  optional {@code ?format=records|matrix} query-string switch. */
    JsonNode runQuery(JsonNode body, String format) throws IOException, InterruptedException {
        String path = "/rest/saiku/api/ai/query";
        if (format != null && !format.isBlank()) path += "?format=" + enc(format);
        return postJson(path, body);
    }

    /** POST {@code /rest/saiku/api/ai/query/preview}. */
    JsonNode previewQuery(JsonNode body) throws IOException, InterruptedException {
        return postJson("/rest/saiku/api/ai/query/preview", body);
    }

    /** GET {@code /rest/saiku/api/ai/query/{queryId}/drillthrough}. */
    JsonNode drillthrough(String queryId, Integer maxrows, String returns) throws IOException, InterruptedException {
        StringBuilder p = new StringBuilder("/rest/saiku/api/ai/query/").append(enc(queryId)).append("/drillthrough");
        boolean first = true;
        if (maxrows != null) {
            p.append(first ? '?' : '&').append("maxrows=").append(maxrows);
            first = false;
        }
        if (returns != null && !returns.isBlank()) {
            p.append(first ? '?' : '&').append("returns=").append(enc(returns));
        }
        return getJson(p.toString());
    }

    /* ------------------------------------------------------------------ */

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        ensureLoggedIn();
        HttpResponse<String> resp = send(HttpRequest.newBuilder(uri(path)).GET().build());
        if (resp.statusCode() == 401) {
            // Session expired between calls. Re-login once and retry.
            loggedIn = false;
            ensureLoggedIn();
            resp = send(HttpRequest.newBuilder(uri(path)).GET().build());
        }
        return parseOrError(resp);
    }

    private JsonNode postJson(String path, JsonNode body) throws IOException, InterruptedException {
        ensureLoggedIn();
        String json = MAPPER.writeValueAsString(body == null ? MAPPER.createObjectNode() : body);
        HttpRequest req = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 401) {
            loggedIn = false;
            ensureLoggedIn();
            resp = send(req);
        }
        return parseOrError(resp);
    }

    /** Validation errors (400) come back with a useful body — return them
     *  to the caller verbatim so the agent can self-correct. Other 5xx
     *  errors with a parsable JSON body also pass through. We only fall
     *  back to a synthetic error envelope on transport failure or non-JSON. */
    private JsonNode parseOrError(HttpResponse<String> resp) {
        String body = resp.body() == null ? "" : resp.body();
        if (body.isEmpty()) {
            return MAPPER.createObjectNode()
                    .put("status", "ERROR")
                    .put("error", "Empty response from saiku (HTTP " + resp.statusCode() + ")");
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return MAPPER.createObjectNode()
                    .put("status", "ERROR")
                    .put(
                            "error",
                            "Non-JSON response from saiku (HTTP " + resp.statusCode() + "): "
                                    + body.substring(0, Math.min(200, body.length())));
        }
    }

    private void ensureLoggedIn() throws IOException, InterruptedException {
        if (loggedIn) return;
        // The launcher's /login endpoint expects form-encoded credentials
        // and returns a 302 on success with a JSESSIONID cookie.
        String form = "username=" + enc(user) + "&password=" + enc(pass);
        HttpRequest req = HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        int code = resp.statusCode();
        // 302 = redirect on success; 200 = some configs return success
        // directly. Anything else means credentials or URL are wrong —
        // surface the status so the operator can fix it.
        if (code != 302 && code != 200) {
            throw new IOException("Saiku login failed: HTTP " + code + " (check SAIKU_URL/USER/PASS)");
        }
        loggedIn = true;
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Read SAIKU_URL / SAIKU_USER / SAIKU_PASS from the supplied env
     *  map, falling back to sensible defaults for a local launcher. */
    static SaikuRestClient fromEnv(Map<String, String> env) {
        String url = env.getOrDefault("SAIKU_URL", "http://localhost:8080");
        String user = env.getOrDefault("SAIKU_USER", "admin");
        String pass = env.getOrDefault("SAIKU_PASS", "admin");
        return new SaikuRestClient(url, user, pass);
    }
}
