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
import java.util.Optional;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#1713 — {@code OlapQueryService.createNewOlapQuery(name, xml)} has no
 * name-collision guard, and that is the CONTRACT: the registry is the
 * caller's own session workspace ({@code olapQueryBean} is session-scoped),
 * the legacy REST entry point is create-or-replace, and session restore
 * replays entries through the same method. This IT pins last-write-wins
 * end-to-end through {@code POST /saiku/{user}/query/{name}} so a future
 * "helpful" reject-on-collision can't land without tripping the pin.
 *
 * <p>The registry is session-scoped, so a real collision only happens inside
 * ONE session — the test captures the JSESSIONID from the first create and
 * replays it on every subsequent call (the harness's HttpClient has no cookie
 * jar; without this, each request gets a fresh workspace and nothing would
 * ever collide).
 */
public class QueryNameCollisionIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    private static String legacyMdxQueryXml(String name, String measure) {
        return "<Query name=\"" + name + "\" type=\"MDX\" connection=\"unknown_foodmart\""
                + " cube=\"[Sales]\" catalog=\"FoodMart\" schema=\"FoodMart\">"
                + "<MDX>SELECT {[Measures].[" + measure + "]} ON COLUMNS FROM [Sales]</MDX>"
                + "</Query>";
    }

    private HttpRequest.Builder authed(String path, String sessionCookie) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(harness.baseUrl() + path))
                .header("Authorization", harness.adminBasicAuth())
                .header("Accept", "application/json");
        if (sessionCookie != null) {
            b.header("Cookie", sessionCookie);
        }
        return b;
    }

    private HttpResponse<String> postForm(String path, String body, String sessionCookie) throws Exception {
        return harness.send(authed(path, sessionCookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    /** Extract "JSESSIONID=..." from a Set-Cookie response header, if present. */
    private static String sessionCookieOf(HttpResponse<String> resp) {
        Optional<String> setCookie = resp.headers().allValues("set-cookie").stream()
                .filter(v -> v.toUpperCase().startsWith("JSESSIONID="))
                .findFirst();
        return setCookie.map(v -> v.split(";", 2)[0]).orElse(null);
    }

    @Test
    public void sameName_secondCreateReplacesFirst_lastWriteWins_saiku1713() throws Exception {
        String name = "collision-1713-" + System.nanoTime();
        String base = "/rest/saiku/admin/query/" + name;

        String first = "xml=" + URLEncoder.encode(legacyMdxQueryXml(name, "Store Sales"), StandardCharsets.UTF_8);
        HttpResponse<String> create1 = postForm(base, first, null);
        assertEquals("first create must succeed, body=" + create1.body(), 200, create1.statusCode());
        String cookie = sessionCookieOf(create1);
        assertNotNull(
                "first create must establish a session (JSESSIONID cookie) — the query registry is"
                        + " session-scoped and the collision below is only real inside one session",
                cookie);

        String second = "xml=" + URLEncoder.encode(legacyMdxQueryXml(name, "Unit Sales"), StandardCharsets.UTF_8);
        HttpResponse<String> create2 = postForm(base, second, cookie);
        assertEquals(
                "second create under the SAME name in the SAME session must succeed"
                        + " (create-or-replace, not 409), body=" + create2.body(),
                200,
                create2.statusCode());

        // Read the query back from the REGISTRY (same session) — it must carry
        // the second body's MDX, proving the first registration was replaced.
        HttpResponse<String> fetched = harness.send(authed(base, cookie).GET().build());
        assertEquals("query must be fetchable after replace, body=" + fetched.body(), 200, fetched.statusCode());
        JsonNode q = harness.parse(fetched);
        assertEquals(name, q.path("name").asText());
        assertTrue(
                "registered query must carry the SECOND body's MDX (last-write-wins), got: "
                        + q.path("mdx").asText(),
                q.path("mdx").asText().contains("Unit Sales"));
        assertFalse(
                "first body's MDX must be fully replaced, got: " + q.path("mdx").asText(),
                q.path("mdx").asText().contains("Store Sales"));
    }
}
