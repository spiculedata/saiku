/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DemoGateProvider} backed by WorkOS Magic Auth (6-digit email OTP).
 *
 * <p>WorkOS publishes no Java SDK, so this calls the REST API directly with the
 * JDK 21 {@link HttpClient} — no new dependency. Two endpoints:
 * <ul>
 *   <li>{@code POST /user_management/magic_auth} — send the code (Bearer API key).</li>
 *   <li>{@code POST /user_management/authenticate} — verify it ({@code client_id}
 *       + {@code client_secret} + the magic-auth grant). A 2xx means the email
 *       is validated; we discard the returned tokens — the gate only needs the
 *       yes/no.</li>
 * </ul>
 *
 * <p>Gotchas baked in (learned from WorkOS's own SDK issues, see the
 * {@code workos-magic-auth} memory): the send endpoint has historically
 * returned an empty/non-JSON body (workos-node#968) even on success, so we
 * never parse it — any 2xx is "sent". And the {@code code} echoed in the send
 * response only appears on test keys, so we never rely on it.
 */
public class WorkOsMagicAuthProvider implements DemoGateProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkOsMagicAuthProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GRANT_TYPE = "urn:workos:oauth:grant-type:magic-auth:code";
    private static final String DEFAULT_BASE = "https://api.workos.com";

    private final String apiKey;
    private final String clientId;
    private final String baseUri;
    private final HttpClient http;

    public WorkOsMagicAuthProvider(String apiKey, String clientId) {
        this(
                apiKey,
                clientId,
                DEFAULT_BASE,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Test seam: inject a base URI (a local stub server) and an HttpClient. */
    WorkOsMagicAuthProvider(String apiKey, String clientId, String baseUri, HttpClient http) {
        this.apiKey = apiKey;
        this.clientId = clientId;
        this.baseUri = baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
        this.http = http;
    }

    @Override
    public String name() {
        return "workos";
    }

    @Override
    public void sendCode(String email) throws DemoGateException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUri + "/user_management/magic_auth"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("email", email)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        int sc = resp.statusCode();
        // Deliberately do NOT parse the body — WorkOS has returned an empty
        // body here even on success (workos-node#968). Any 2xx = code sent.
        if (sc / 100 != 2) {
            throw new DemoGateException("WorkOS magic_auth send failed (" + sc + "): " + safeBody(resp));
        }
    }

    @Override
    public boolean verifyCode(String email, String code, String firstName, String lastName) throws DemoGateException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("client_secret", apiKey);
        body.put("grant_type", GRANT_TYPE);
        body.put("code", code);
        body.put("email", email);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUri + "/user_management/authenticate"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        int sc = resp.statusCode();
        if (sc / 100 == 2) {
            // Valid code → email proven; tokens discarded. Best-effort: attach
            // the captured name to the WorkOS user so the operator sees who
            // signed in, not just an address (saiku#1029).
            attachName(resp.body(), firstName, lastName);
            return true;
        }
        // Bad/expired/used code → WorkOS returns a 4xx invalid_grant. Treat the
        // common ones as "wrong code" (false), not a hard failure.
        if (sc == 400 || sc == 401 || sc == 422) {
            return false;
        }
        throw new DemoGateException("WorkOS authenticate failed (" + sc + "): " + safeBody(resp));
    }

    /**
     * Set {@code first_name}/{@code last_name} on the WorkOS user that the
     * authenticate response identifies. Best-effort: any failure (parse, HTTP,
     * missing id) is logged and swallowed — it must never fail a verification
     * that already succeeded. No-ops when both names are blank.
     */
    private void attachName(String authBody, String firstName, String lastName) {
        boolean hasFirst = firstName != null && !firstName.isBlank();
        boolean hasLast = lastName != null && !lastName.isBlank();
        if (!hasFirst && !hasLast) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(authBody == null ? "{}" : authBody);
            String userId = root.path("user").path("id").asText(null);
            if (userId == null || userId.isBlank()) {
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            if (hasFirst) {
                body.put("first_name", firstName.trim());
            }
            if (hasLast) {
                body.put("last_name", lastName.trim());
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUri + "/user_management/users/" + userId))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = send(req);
            if (r.statusCode() / 100 != 2) {
                log.warn("WorkOS update-user name failed ({}) — verification still succeeded", r.statusCode());
            }
        } catch (Exception e) {
            log.warn("WorkOS update-user name skipped: {}", e.getMessage());
        }
    }

    private HttpResponse<String> send(HttpRequest req) throws DemoGateException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DemoGateException("WorkOS request I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DemoGateException("WorkOS request interrupted", e);
        }
    }

    private static String json(Map<String, ?> m) throws DemoGateException {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new DemoGateException("Failed to encode WorkOS request body", e);
        }
    }

    private static String safeBody(HttpResponse<String> r) {
        String b = r.body();
        if (b == null || b.isBlank()) return "<empty>";
        return b.length() > 300 ? b.substring(0, 300) + "…" : b;
    }
}
