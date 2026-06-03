/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.demo;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * Exercises the WorkOS HTTP contract against an in-process {@link HttpServer}
 * stub — no WireMock/Mockito (saiku-web has none) and no network. Pins the
 * request shape (path, Bearer auth, body), response handling (including the
 * empty-body quirk), and the best-effort name attachment to the WorkOS user.
 */
public class WorkOsMagicAuthProviderTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> sendPath = new AtomicReference<>();
    private final AtomicReference<String> sendAuth = new AtomicReference<>();
    private final AtomicReference<String> sendBody = new AtomicReference<>();
    private final AtomicReference<String> verifyBody = new AtomicReference<>();
    private final AtomicReference<String> updatePath = new AtomicReference<>();
    private final AtomicReference<String> updateBody = new AtomicReference<>();

    @After
    public void stop() {
        if (server != null) server.stop(0);
    }

    private void start(int sendStatus, String sendRespBody, int verifyStatus, String verifyRespBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user_management/magic_auth", ex -> {
            sendPath.set(ex.getRequestURI().getPath());
            sendAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            sendBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeResponse(ex, sendStatus, sendRespBody);
        });
        server.createContext("/user_management/authenticate", ex -> {
            verifyBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeResponse(ex, verifyStatus, verifyRespBody);
        });
        // Update-user endpoint (name attachment). Matches /user_management/users/{id}.
        server.createContext("/user_management/users", ex -> {
            updatePath.set(ex.getRequestURI().getPath());
            updateBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeResponse(ex, 200, "{}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws java.io.IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } else {
            ex.close();
        }
    }

    private WorkOsMagicAuthProvider provider() {
        return new WorkOsMagicAuthProvider("sk_test_123", "client_abc", base, HttpClient.newHttpClient());
    }

    @Test
    public void sendCode_postsEmailWithBearerAuth() throws Exception {
        start(200, "{\"id\":\"magic_auth_1\"}", 200, "{}");
        provider().sendCode("user@example.com");
        assertEquals("/user_management/magic_auth", sendPath.get());
        assertEquals("Bearer sk_test_123", sendAuth.get());
        assertTrue(sendBody.get().contains("user@example.com"));
    }

    @Test
    public void sendCode_toleratesEmptyBody() throws Exception {
        // WorkOS has returned an empty body here even on success (workos-node#968).
        start(200, null, 200, "{}");
        provider().sendCode("user@example.com"); // must not throw
    }

    @Test(expected = DemoGateException.class)
    public void sendCode_throwsOnServerError() throws Exception {
        start(500, "boom", 200, "{}");
        provider().sendCode("user@example.com");
    }

    @Test
    public void verifyCode_trueOn2xx() throws Exception {
        start(200, "{}", 200, "{\"access_token\":\"x\"}");
        assertTrue(provider().verifyCode("user@example.com", "123456", null, null));
    }

    @Test
    public void verifyCode_falseOnInvalidGrant() throws Exception {
        start(200, "{}", 401, "{\"error\":\"invalid_grant\"}");
        assertFalse(provider().verifyCode("user@example.com", "000000", null, null));
    }

    @Test
    public void verifyCode_sendsClientIdGrantAndCode() throws Exception {
        start(200, "{}", 200, "{}");
        provider().verifyCode("user@example.com", "123456", null, null);
        String b = verifyBody.get();
        assertTrue(b.contains("client_abc"));
        assertTrue(b.contains("urn:workos:oauth:grant-type:magic-auth:code"));
        assertTrue(b.contains("123456"));
    }

    @Test(expected = DemoGateException.class)
    public void verifyCode_throwsOnServerError() throws Exception {
        start(200, "{}", 500, "boom");
        provider().verifyCode("user@example.com", "123456", null, null);
    }

    @Test
    public void verifyCode_attachesNameToWorkOsUser() throws Exception {
        start(200, "{}", 200, "{\"user\":{\"id\":\"user_123\"}}");
        assertTrue(provider().verifyCode("user@example.com", "123456", "Juan", "Resendiz"));
        assertEquals("/user_management/users/user_123", updatePath.get());
        String b = updateBody.get();
        assertTrue(b.contains("\"first_name\":\"Juan\""));
        assertTrue(b.contains("\"last_name\":\"Resendiz\""));
    }

    @Test
    public void verifyCode_skipsNameUpdateWhenNoNames() throws Exception {
        start(200, "{}", 200, "{\"user\":{\"id\":\"user_123\"}}");
        provider().verifyCode("user@example.com", "123456", null, null);
        assertNull("no update call when names are absent", updatePath.get());
    }
}
