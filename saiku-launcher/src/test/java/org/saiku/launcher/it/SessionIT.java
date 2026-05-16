/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Coverage for {@code /rest/saiku/session/*} — the REST-form-encoded session
 * surface that the SPA uses for login/logout.
 *
 * <p>Form-login goes through Spring Security's filter chain which also
 * accepts Basic auth, so the REST {@code /session} endpoint just verifies the
 * already-authenticated context (or returns 401 if anon).
 */
public class SessionIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void getSession_withBasicAuth_returns200WithJsonBody() throws Exception {
        // Basic auth doesn't establish an HTTP session; the SessionService's
        // session-objects map is empty for stateless requests so the body
        // surfaces just the language injected by the resource. Pin that 200
        // is still returned so the SPA's pre-login probe doesn't break.
        HttpResponse<String> resp = harness.getAuth("/rest/saiku/session");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertNotNull("session body must be JSON, got: " + resp.body(), body);
        // Resource attempts to inject language; for stateless calls
        // it lands on the empty map and is still returned as a JSON object.
        assertTrue("response must be a JSON object", body.isObject());
    }

    @Test
    public void postLogin_validCredentials_returns200() throws Exception {
        HttpResponse<String> resp = formLogin("admin", "admin");
        assertEquals(200, resp.statusCode());
    }

    @Test
    public void postLogin_invalidCredentials_returns401() throws Exception {
        HttpResponse<String> resp = formLogin("admin", "wrongpass");
        assertEquals(401, resp.statusCode());
        assertTrue(
                "401 body should mention authentication: " + resp.body(),
                resp.body().toLowerCase().contains("authentication"));
    }

    @Test
    public void logout_withAuth_returns200() throws Exception {
        HttpResponse<String> resp = harness.deleteAuth("/rest/saiku/session");
        assertEquals(200, resp.statusCode());
    }

    private static HttpResponse<String> formLogin(String user, String pass) throws Exception {
        String form = "username="
                + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&password="
                + URLEncoder.encode(pass, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/rest/saiku/session"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return harness.send(req);
    }
}
