/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.ISessionService;

/**
 * saiku#1907 F3 (CWE-178) WIRING test: {@link SecurityAwareConnectionManager#handlePassThrough} is
 * the actual call site that decides which session identity is forwarded to a pass-through
 * datasource as warehouse credentials. Per the "security fixes need a call-site test" gate, this
 * exercises that site directly rather than only the shared {@code SessionService} helper that
 * populates the session map ({@code SessionServiceCanonicalUsernameTest} already covers that half)
 * — the bug this closes lives at the READ site, not just the write site.
 *
 * <p>It must read {@code "principal"} (the ORIGINAL, as-typed spelling) — NOT {@code "username"}
 * (the canonicalised/lower-cased ACL identity) — because a case-sensitive warehouse login (e.g.
 * {@code JSmith}) must see exactly what the user typed. It falls back to {@code "username"} only
 * for a session minted before the F3 split (or a delegated {@code runAs} session, which mirrors
 * both keys to the same value).
 *
 * <p>{@code handlePassThrough} is private, and IS the call site under test (not a shared helper),
 * so invoking it via reflection is the correct wiring test here — saiku-web has no Mockito; hand
 * fakes/reflection only, matching the convention already used elsewhere in this fix (e.g.
 * {@code FilesystemRepositoryManagerCaseOwnerTest} reflects into a package-private constructor).
 */
public class SecurityAwareConnectionManagerPassThroughTest {

    @Test
    public void passThrough_uses_principal_not_canonical_username() throws Exception {
        Map<String, Object> session = new HashMap<>();
        session.put("username", "jsmith"); // canonicalised ACL/home identity
        session.put("principal", "JSmith"); // original, case-sensitive warehouse login
        session.put("password", "s3cret");

        SaikuDatasource result = invokeHandlePassThrough(session);

        assertEquals(
                "pass-through must forward the typed principal spelling, not the canonical username",
                "JSmith",
                result.getProperties().getProperty("username"));
        assertEquals("s3cret", result.getProperties().getProperty("password"));
    }

    /**
     * A session minted before the F3 split (or a delegated {@code runAs} session) carries only
     * {@code "username"} — pass-through must still resolve a credential from it rather than
     * silently forwarding {@code null} (which would return a null datasource and break the
     * connection instead of degrading to a working — if less precise — credential).
     */
    @Test
    public void passThrough_falls_back_to_username_when_principal_absent() throws Exception {
        Map<String, Object> session = new HashMap<>();
        session.put("username", "legacyuser");

        SaikuDatasource result = invokeHandlePassThrough(session);

        assertEquals("legacyuser", result.getProperties().getProperty("username"));
    }

    @Test
    public void passThrough_returns_null_when_neither_identity_present() throws Exception {
        SaikuDatasource result = invokeHandlePassThrough(new HashMap<>());
        assertNull("no identity in session -> no datasource to connect with", result);
    }

    private static SaikuDatasource invokeHandlePassThrough(Map<String, Object> session) throws Exception {
        SecurityAwareConnectionManager mgr = new SecurityAwareConnectionManager();
        mgr.setSessionService(new FakeSessionService(session));

        SaikuDatasource datasource = new SaikuDatasource("ds", SaikuDatasource.Type.OLAP, new Properties());

        Method m = SecurityAwareConnectionManager.class.getDeclaredMethod("handlePassThrough", SaikuDatasource.class);
        m.setAccessible(true);
        return (SaikuDatasource) m.invoke(mgr, datasource);
    }

    /** Minimal ISessionService fake — handlePassThrough only reads getAllSessionObjects(). */
    private static final class FakeSessionService implements ISessionService {
        private final Map<String, Object> session;

        FakeSessionService(Map<String, Object> session) {
            this.session = session;
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return session;
        }

        @Override
        public Map<String, Object> login(HttpServletRequest req, String username, String password) {
            return null;
        }

        @Override
        public void logout(HttpServletRequest req) {}

        @Override
        public void authenticate(HttpServletRequest req, String username, String password) {}

        @Override
        public Map<String, Object> getSession() {
            return session;
        }

        @Override
        public void clearSessions(HttpServletRequest req, String username, String password) {}
    }
}
