/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link SaikuRestClient}. Spins up an embedded JDK
 * {@link HttpServer} that mimics the saiku launcher's auth + AI
 * endpoints, then exercises each of the client's public methods plus
 * the cookie + 401-retry plumbing.
 *
 * <p>Embedded server pattern (vs Mockito on HttpClient) because the JDK
 * HttpClient surface is final-class-heavy and awkward to mock — and
 * running a real socket exercises the cookie jar + redirect handling
 * for free.
 */
public class SaikuRestClientTest {

    private HttpServer server;
    private String baseUrl;
    private SaikuRestClient client;

    /** Number of /login calls — verifies re-login on 401 fires once. */
    private final AtomicInteger loginCalls = new AtomicInteger();
    /** Mutable per-test toggle: when true, the next /api call returns 401. */
    private volatile boolean nextCallReturn401 = false;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // /login handles both the GET (browser would land here first) and
        // the POST. POST returns 302 on correct credentials.
        server.createContext("/login", ex -> {
            if ("POST".equals(ex.getRequestMethod())) {
                loginCalls.incrementAndGet();
                String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (form.contains("username=admin") && form.contains("password=secret")) {
                    ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=abc123; Path=/");
                    ex.sendResponseHeaders(302, -1);
                } else {
                    ex.sendResponseHeaders(401, -1);
                }
            } else {
                ex.sendResponseHeaders(200, 0);
                ex.getResponseBody().close();
            }
        });

        server.createContext("/rest/saiku/api/ai/cubes", ex -> {
            if (nextCallReturn401) {
                nextCallReturn401 = false;
                ex.sendResponseHeaders(401, -1);
                return;
            }
            respondJson(ex, 200, "[{\"cubeName\":\"Sales\"},{\"cubeName\":\"HR\"}]");
        });

        server.createContext("/rest/saiku/api/ai/query", ex -> {
            // Mimic the validation-error envelope.
            respondJson(
                    ex,
                    400,
                    "{\"status\":\"VALIDATION_ERROR\",\"field\":\"measures[].name\","
                            + "\"error\":\"Unknown measure 'X'\",\"available\":[\"Unit Sales\"]}");
        });

        server.createContext("/rest/saiku/api/ai/members/search", ex -> {
            // Echo the query string so the test can verify URL building.
            String qs = ex.getRequestURI().getRawQuery();
            respondJson(ex, 200, "{\"hits\":[],\"_query\":\"" + (qs == null ? "" : qs.replace("\"", "\\\"")) + "\"}");
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new SaikuRestClient(baseUrl, "admin", "secret");
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    public void listCubesAuthenticatesThenReturnsBody() throws Exception {
        JsonNode body = client.listCubes();
        assertNotNull(body);
        assertTrue("expected a JSON array — got: " + body, body.isArray());
        assertEquals(2, body.size());
        assertEquals("Sales", body.get(0).get("cubeName").asText());
        assertEquals("login fired exactly once", 1, loginCalls.get());
    }

    @Test
    public void cookieJarRemembersSessionAcrossCalls() throws Exception {
        client.listCubes();
        client.listCubes();
        // Second call would re-trigger login if the cookie weren't reused.
        assertEquals("login fired once across two calls", 1, loginCalls.get());
    }

    @Test
    public void reLoginsOnSession401AndRetries() throws Exception {
        client.listCubes(); // primes the cookie (login=1)
        nextCallReturn401 = true; // next /cubes call returns 401
        JsonNode body = client.listCubes(); // should re-login then succeed
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("re-login fired once on 401", 2, loginCalls.get());
    }

    @Test
    public void postValidationErrorBodyIsReturnedVerbatim() throws Exception {
        JsonNode body = client.runQuery(null, null);
        assertEquals("VALIDATION_ERROR", body.get("status").asText());
        assertEquals("measures[].name", body.get("field").asText());
        assertTrue(body.get("available").isArray());
    }

    @Test
    public void searchMembersEncodesQueryParams() throws Exception {
        JsonNode body = client.searchMembers("c", "Customer", "Customers", "Country", "U S A", 5);
        String echoed = body.get("_query").asText();
        assertTrue("dimension propagated — got: " + echoed, echoed.contains("dimension=Customer"));
        // " " must be %20 (or +); both are legal RFC 3986 — assert the
        // server saw the right value either way by checking the raw form.
        assertTrue(
                "space-bearing q propagated and URL-encoded — got: " + echoed,
                echoed.contains("q=U+S+A") || echoed.contains("q=U%20S%20A"));
        assertTrue("limit propagated — got: " + echoed, echoed.contains("limit=5"));
    }

    @Test
    public void loginFailureSurfacesAsIOException() {
        SaikuRestClient bad = new SaikuRestClient(baseUrl, "admin", "wrong-password");
        try {
            bad.listCubes();
            fail("expected IOException from failed login");
        } catch (IOException e) {
            assertTrue(
                    "error names the HTTP status — got: " + e.getMessage(),
                    e.getMessage().contains("HTTP 401"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("unexpected InterruptedException");
        }
    }

    @Test
    public void readPasswordPrefersFileOverEnv() throws Exception {
        Path tmp = Files.createTempFile("saiku-mcp-pass", ".txt");
        try {
            Files.writeString(tmp, "from-file\n");
            Map<String, String> env = new HashMap<>();
            env.put("SAIKU_PASS_FILE", tmp.toString());
            env.put("SAIKU_PASS", "from-env"); // should be ignored
            String pass = SaikuRestClient.readPassword(env);
            assertEquals("from-file", pass); // trailing newline trimmed
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void readPasswordFallsBackToEnvWhenFileMissing() {
        Map<String, String> env = new HashMap<>();
        env.put("SAIKU_PASS", "secret-from-env");
        assertEquals("secret-from-env", SaikuRestClient.readPassword(env));
    }

    @Test
    public void readPasswordDefaultsToAdmin() {
        assertEquals("admin", SaikuRestClient.readPassword(new HashMap<>()));
    }
}
