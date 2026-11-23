/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The launcher's Jetty connector takes the client address from the TCP peer only: neither
 * {@code X-Forwarded-For} nor the RFC 7239 {@code Forwarded} header replaces
 * {@code getRemoteAddr()}. The {@code X-Forwarded-Proto} handling a TLS-terminating proxy relies
 * on keeps working.
 *
 * <p>The address test observes the per-address login limit, so it runs from its own loopback
 * source address ({@code 127.0.0.2}) and leaves the {@code 127.0.0.1} every other IT uses
 * untouched (see the note in {@link XmlaAuthIT}). Linux routes all of {@code 127/8} to loopback;
 * where {@code 127.0.0.2} is not configured (stock macOS) the test is skipped, except on CI,
 * where it fails.
 */
public class ForwardedHeaderIT {

    private static final int MAX_ATTEMPTS = 5;

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void clientAddressDoesNotFollowTheForwardedHeader() throws Exception {
        HttpClient client = clientBoundTo("127.0.0.2");

        // Fail until the limiter answers 429. The budget is MAX_ATTEMPTS failures, but how many
        // failures one rejected form login records is not this test's concern, so stop at the
        // first 429 instead of counting.
        int status = 0;
        for (int i = 0; i <= MAX_ATTEMPTS && status != 429; i++) {
            status = formLogin(client, "Forwarded", "for=198.51.100.50").statusCode();
            assertTrue(
                    "a failed login is 401 until the lockout, then 429 (was " + status + ")",
                    status == 401 || status == 429);
        }
        assertEquals("the per-address limit must engage", 429, status);

        // Same client, different Forwarded value: the server must still see the same client address.
        HttpResponse<String> other = formLogin(client, "Forwarded", "for=198.51.100.51");
        assertEquals(
                "the client address must not follow the Forwarded header (body: " + other.body() + ")",
                429,
                other.statusCode());

        HttpResponse<String> xff = formLogin(client, "X-Forwarded-For", "198.51.100.52");
        assertEquals("the client address must not follow X-Forwarded-For", 429, xff.statusCode());

        HttpResponse<String> none = formLogin(client, null, null);
        assertEquals("a request without the header comes from the same client address", 429, none.statusCode());
    }

    @Test
    public void forwardedProtoStillMarksTheRequestSecure() throws Exception {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        String plain = xsrfCookie(client, null);
        assertNotNull("XSRF-TOKEN cookie must be written on a direct request", plain);
        assertFalse("direct HTTP must not get a Secure cookie: " + plain, hasSecure(plain));

        String proxied = xsrfCookie(client, "https");
        assertNotNull("XSRF-TOKEN cookie must be written behind a proxy", proxied);
        assertTrue("X-Forwarded-Proto: https must still give a Secure cookie: " + proxied, hasSecure(proxied));
    }

    private static HttpClient clientBoundTo(String source) throws Exception {
        InetAddress local = InetAddress.getByName(source);
        // Probe the source address first so an unconfigured loopback alias skips (fails on CI).
        URI base = URI.create(harness.baseUrl());
        try (Socket probe = new Socket()) {
            probe.bind(new InetSocketAddress(local, 0));
            probe.connect(new InetSocketAddress(base.getHost(), base.getPort()), 5000);
        } catch (IOException e) {
            if (System.getenv("CI") != null) {
                throw new AssertionError("loopback source " + source + " must be usable on CI", e);
            }
            assumeNoException("loopback source " + source + " is not usable here", e);
        }
        return HttpClient.newBuilder()
                .localAddress(local)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static HttpResponse<String> formLogin(HttpClient client, String headerName, String headerValue)
            throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/rest/saiku/session"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "username=forwarded-it&password=wrong", StandardCharsets.UTF_8));
        if (headerName != null) {
            req.header(headerName, headerValue);
        }
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String xsrfCookie(HttpClient client, String forwardedProto) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/rest/saiku/info"))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (forwardedProto != null) {
            req.header("X-Forwarded-Proto", forwardedProto);
        }
        HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return resp.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasSecure(String setCookie) {
        for (String attr : setCookie.split(";")) {
            if (attr.trim().toLowerCase(Locale.ROOT).equals("secure")) {
                return true;
            }
        }
        return false;
    }
}
