/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#2524 — a request that is refused before login must NOT create a server-side session.
 *
 * <p>Before the fix, every unauthenticated request to a protected {@code /rest/**} URL answered
 * {@code 401} <em>and</em> a fresh {@code JSESSIONID}: Spring's default
 * {@code HttpSessionRequestCache} saved the refused request (URL, query string, ALL headers) in a
 * new HTTP session ({@code SPRING_SECURITY_SAVED_REQUEST}), the launcher's {@code FileSessionDataStore}
 * wrote it to {@code <saiku-home>/sessions/}, and it stayed resident (Jetty's default NEVER_EVICT
 * cache) and persisted for seven days. The caller controls the size through its own headers, so
 * this was an unauthenticated way to fill the heap and the disk. A failed login (bad password at
 * {@code POST /rest/saiku/session}, which used to call {@code getSession(true)} before checking
 * credentials) minted the same sessions.
 *
 * <p>The fix has two halves, and this IT pins BOTH through the real Jetty + Spring Security chain
 * ({@link SaikuItHarness}):
 *
 * <ol>
 *   <li><b>The webapp saves nothing on refusal.</b> The main chain's request cache is
 *       {@code NullRequestCache} (applicationContext-saiku.xml) and
 *       {@code SessionService.login} creates the container session only after authentication
 *       succeeds — so every refusal below answers 401 with no {@code JSESSIONID} cookie and no
 *       new file under {@code sessions/}.</li>
 *   <li><b>Legitimate sessions are untouched.</b> A successful login still sets {@code JSESSIONID}
 *       (and still persists the session to {@code sessions/} — saiku#1859's restart survival), so
 *       the guard is not over-blocking.</li>
 * </ol>
 *
 * <p>The file-count assertions are deltas against a snapshot, because this suite shares one
 * harness (and one {@code saiku-home}) with every other IT: sessions created by earlier tests
 * must not make this test flaky.
 */
public class SessionRefusalIT {

    private static SaikuItHarness harness;
    private static File sessionsDir;

    /** A protected URL under the main chain's terminal catch-all — anonymous callers get 401. */
    private static final String PROTECTED = "/rest/saiku/api/repository";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
        sessionsDir = harness.home().resolve("sessions").toFile();
        assertTrue(
                "the launcher must have created <home>/sessions (FileSessionDataStore store dir)",
                sessionsDir.isDirectory());
    }

    @Test
    public void anonymousRequestToProtectedUrl_answers401_withoutACookieAndWithoutASessionFile() throws Exception {
        Set<String> before = sessionFiles();
        HttpResponse<String> resp = harness.getAnon(PROTECTED);
        assertEquals("an unauthenticated request must be refused", 401, resp.statusCode());
        assertNoJsessionid("anonymous 401", resp);
        assertEquals(
                "a refused request must not create a session file",
                before,
                sessionFiles());
    }

    @Test
    public void paddedAnonymousRequest_answers401_withoutACookieAndWithoutASessionFile() throws Exception {
        // The size vector from the issue: the caller controlled how much got stored through
        // its own headers (the saved request kept ALL of them).
        Set<String> before = sessionFiles();
        HttpResponse<String> resp = harness.send(HttpRequest.newBuilder(URI.create(harness.baseUrl() + PROTECTED))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("X-Pad", "A".repeat(6000))
                .GET()
                .build());
        assertEquals("a padded unauthenticated request must still be refused", 401, resp.statusCode());
        assertNoJsessionid("padded anonymous 401", resp);
        assertEquals("a padded refused request must not create a session file", before, sessionFiles());
    }

    @Test
    public void wrongBasicCredentials_answers401_withoutACookieAndWithoutASessionFile() throws Exception {
        Set<String> before = sessionFiles();
        HttpResponse<String> resp = harness.getAuth("admin", "definitely-not-the-password-2524", PROTECTED);
        assertEquals("wrong Basic credentials must be refused", 401, resp.statusCode());
        assertNoJsessionid("wrong-Basic 401", resp);
        assertEquals("a failed Basic attempt must not create a session file", before, sessionFiles());
    }

    @Test
    public void failedSessionLogin_answers401_withoutACookieAndWithoutASessionFile() throws Exception {
        Set<String> before = sessionFiles();
        HttpResponse<String> resp =
                harness.send(HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/rest/saiku/session"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=admin&password=definitely-not-the-password-2524", StandardCharsets.UTF_8))
                        .build());
        assertEquals("a bad-password login must be refused", 401, resp.statusCode());
        assertNoJsessionid("failed login 401", resp);
        assertEquals("a failed login must not create a session file", before, sessionFiles());
    }

    @Test
    public void successfulLogin_stillSetsJsessionid_andPersistsTheSession() throws Exception {
        HttpResponse<String> resp = harness.postAuthForm("/rest/saiku/session", "username=admin&password=admin");
        assertEquals("a correct login must still succeed", 200, resp.statusCode());
        List<String> setCookies = resp.headers().allValues("set-cookie");
        assertFalse(
                "a successful login must still set JSESSIONID; got: " + setCookies,
                pickJsessionid(setCookies).isEmpty());
        // saiku#1859 unchanged: the session the login created is on disk under sessions/.
        // On a shared harness an earlier IT's admin session may already be in the store (the
        // id is rotated in place), so this is a "the store is in use" guard — it is the strict
        // file-count proof only when this suite runs in isolation on a fresh home.
        assertFalse(
                "a successful login must persist a session file (saiku#1859)",
                sessionFiles().isEmpty());
    }

    /** Every file name currently in the sessions store (recursive — flat in practice). */
    private static Set<String> sessionFiles() {
        File[] files = sessionsDir.listFiles();
        if (files == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (File f : files) {
            if (f.isDirectory()) {
                Arrays.stream(f.listFiles()).map(File::getName).forEach(names::add);
            } else {
                names.add(f.getName());
            }
        }
        return names;
    }

    /** Assert no Set-Cookie on the response carries a JSESSIONID cookie. */
    private static void assertNoJsessionid(String what, HttpResponse<String> resp) {
        List<String> hits = pickJsessionid(resp.headers().allValues("set-cookie"));
        assertTrue(
                what + " must not create a session (no JSESSIONID cookie); got: "
                        + resp.headers().allValues("set-cookie"),
                hits.isEmpty());
    }

    /** The Set-Cookie values that carry a JSESSIONID cookie. */
    private static List<String> pickJsessionid(List<String> setCookies) {
        return setCookies.stream()
                .filter(c -> c.trim().toUpperCase().startsWith("JSESSIONID="))
                .collect(Collectors.toList());
    }
}
