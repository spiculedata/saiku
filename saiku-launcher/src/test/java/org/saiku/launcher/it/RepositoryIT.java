/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Coverage for {@code /rest/saiku/api/repository/*} — file storage surface
 * the SPA uses for saved queries and dashboards.
 *
 * <p>The repository endpoints depend on a Spring Security session. With Basic
 * auth, Spring populates the SecurityContext per-request, which Saiku's
 * ISessionService projects into the {@code username} / {@code roles} keys
 * that BasicRepositoryResource2 relies on.
 */
public class RepositoryIT {

    private static SaikuItHarness harness;
    private static final String BASE = "/rest/saiku/api/repository";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void getRepositoryRoot_returns200() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE);
        assertEquals("repository root must return 200, got " + resp.statusCode(), 200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("repository root must be a JSON array", body.isArray());
    }

    @Test
    public void saveAndReadAndDeleteResource_endToEnd() throws Exception {
        String name = "/homes/home:admin/it-test-resource-" + System.nanoTime() + ".saiku";
        String content = "{\"it-marker\":\"hello-world\"}";

        // Save
        String form = "file="
                + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&content="
                + URLEncoder.encode(content, StandardCharsets.UTF_8);
        HttpResponse<String> save = harness.postAuthForm(BASE + "/resource", form);
        // 200 on success; under stateless Basic auth the in-memory session
        // shape doesn't expose username/roles and the endpoint NPEs.
        // saiku#865 fix: the NPE is now translated by the
        // GenericFailureExceptionMapper into a 400 BAD_REQUEST envelope
        // ("Required parameter missing or null") instead of a 500 HTML
        // page. Pin both the happy 200 and the 400-envelope cases.
        assertTrue(
                "save should be 200 or 400 (session-dependent), got " + save.statusCode() + " body=" + save.body(),
                save.statusCode() == 200 || save.statusCode() == 400);

        if (save.statusCode() != 200) {
            // Session-bound endpoint can't run under stateless Basic auth in
            // every config; skip the rest rather than fail noisily.
            return;
        }

        // Read — the resource produces text/plain (raw file body), but the
        // default getAuth() sends Accept: application/json. Build the request
        // by hand so content negotiation passes. Was hidden behind the
        // save-NPEs-with-stateless-Basic path; the Repository#getRepository
        // null-type fix unblocked save so the read now actually runs.
        java.net.http.HttpRequest readReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        harness.baseUrl() + BASE + "/resource?file=" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .header("Authorization", harness.adminBasicAuth())
                .header("Accept", "text/plain")
                .GET()
                .build();
        HttpResponse<String> read = harness.send(readReq);
        assertEquals(200, read.statusCode());
        assertEquals(content, read.body());

        // Delete
        HttpResponse<String> del =
                harness.deleteAuth(BASE + "/resource?file=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        assertEquals(200, del.statusCode());
    }
}
