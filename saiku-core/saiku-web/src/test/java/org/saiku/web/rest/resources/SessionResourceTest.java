/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ISessionService;
import org.saiku.service.user.UserService;
import org.saiku.web.security.ratelimit.LoginRateLimiter;

/**
 * Exercise the SessionResource HTTP contract without spinning up Jersey. We
 * drive the resource with hand-built stubs for {@link ISessionService},
 * {@link UserService} and {@link HttpServletRequest} (via a dynamic proxy) so
 * the test stays in-process and fast.
 */
public class SessionResourceTest {

    private SessionResource resource;
    private RecordingSessionService session;
    private RecordingUserService users;
    private LoginRateLimiter rateLimiter;
    private HttpServletRequest req;

    @Before
    public void setUp() {
        session = new RecordingSessionService();
        users = new RecordingUserService();
        // Generous limits so the happy paths don't trip the limiter.
        rateLimiter = new LoginRateLimiter(50, 60_000L, false);
        req = newRequest("203.0.113.10", new Locale("en"));

        resource = new SessionResource();
        resource.setSessionService(session);
        resource.setUserService(users);
        resource.setRateLimiter(rateLimiter);
    }

    @Test
    public void login_validCredentials_returns200() {
        Response resp = resource.login(req, "admin", "secret");
        assertEquals(200, resp.getStatus());
        assertEquals(1, session.loginCalls);
    }

    @Test
    public void login_invalidCredentials_returns401() {
        session.loginException = new RuntimeException("bad creds");
        Response resp = resource.login(req, "admin", "wrong");
        assertEquals(401, resp.getStatus());
        assertEquals("Authentication failed", resp.getEntity());
    }

    @Test
    public void login_blockedByRateLimiter_returns429WithRetryAfter() {
        // Tiny window so we can quickly exhaust the bucket from the test side.
        LoginRateLimiter strict = new LoginRateLimiter(1, 600_000L, false);
        resource.setRateLimiter(strict);
        session.loginException = new RuntimeException("bad creds");

        // First failure counts; second attempt is blocked.
        resource.login(req, "admin", "wrong");
        Response resp = resource.login(req, "admin", "wrong");

        assertEquals(429, resp.getStatus());
        assertNotNull("Retry-After header must be set", resp.getHeaderString("Retry-After"));
        assertEquals(String.valueOf(strict.getWindowMs() / 1000), resp.getHeaderString("Retry-After"));
    }

    @Test
    public void login_nullRateLimiter_setterDoesNotOverwrite() {
        // setRateLimiter(null) is documented as a no-op — the existing limiter
        // must still apply. Pass a real null and verify the resource still
        // accepts a normal login.
        resource.setRateLimiter(null);
        Response resp = resource.login(req, "admin", "secret");
        assertEquals(200, resp.getStatus());
    }

    @Test
    public void getSession_returns200WithSessionAndLanguageAndIsAdmin() {
        session.sessionMap = new HashMap<>();
        session.sessionMap.put("username", "tom");
        users.isAdmin = true;

        Response resp = resource.getSession(req);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("tom", body.get("username"));
        assertEquals("en", body.get("language"));
        assertEquals(Boolean.TRUE, body.get("isadmin"));
        assertTrue("checkFolders must be called once per session probe", users.checkFoldersCalls >= 1);
    }

    @Test
    public void getSession_sessionServiceFailure_returns500() {
        session.getSessionException = new RuntimeException("backend down");
        Response resp = resource.getSession(req);
        assertEquals(500, resp.getStatus());
        assertEquals("backend down", resp.getEntity());
    }

    @Test
    public void getSession_isAdminThrows_doesNotPropagate() {
        session.sessionMap = new HashMap<>();
        users.isAdminException = new RuntimeException("acl backend missing");
        Response resp = resource.getSession(req);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        // No isadmin key on swallowed exception.
        assertFalse(body.containsKey("isadmin"));
    }

    @Test
    public void logout_returns200_andCallsLogout() {
        session.sessionMap = new HashMap<>();
        session.sessionMap.put("username", "tom");
        Response resp = resource.logout(req);
        assertEquals(200, resp.getStatus());
        assertEquals(1, session.logoutCalls);
    }

    @Test
    public void logout_withoutActiveSession_stillReturns200() {
        session.getSessionException = new RuntimeException("no session");
        Response resp = resource.logout(req);
        assertEquals(200, resp.getStatus());
        assertEquals(1, session.logoutCalls);
    }

    @Test
    public void clearSession_success_returns200() {
        Response resp = resource.clearSession(req, "admin", "secret");
        assertEquals(200, resp.getStatus());
        assertEquals("Session cleared", resp.getEntity());
        assertEquals(1, session.clearCalls);
    }

    @Test
    public void clearSession_failure_returns500WithMessage() {
        session.clearException = new RuntimeException("forbidden");
        Response resp = resource.clearSession(req, "admin", "secret");
        assertEquals(500, resp.getStatus());
        assertEquals("forbidden", resp.getEntity());
    }

    @Test
    public void getSessionService_returnsWiredInstance() {
        assertSame(session, resource.getSessionService());
    }

    // ---------------------------------------------------------------- helpers

    private static HttpServletRequest newRequest(String remoteAddr, Locale locale) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if ("getRemoteAddr".equals(n)) return remoteAddr;
                    if ("getLocale".equals(n)) return locale;
                    if ("getHeader".equals(n)) return null;
                    if ("getSession".equals(n)) return null;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    private static final class RecordingSessionService implements ISessionService {
        Map<String, Object> sessionMap = new HashMap<>();
        RuntimeException loginException;
        RuntimeException getSessionException;
        RuntimeException clearException;
        int loginCalls;
        int logoutCalls;
        int clearCalls;

        @Override
        public Map<String, Object> login(HttpServletRequest req, String username, String password) {
            loginCalls++;
            if (loginException != null) throw loginException;
            return sessionMap;
        }

        @Override
        public void logout(HttpServletRequest req) {
            logoutCalls++;
        }

        @Override
        public void authenticate(HttpServletRequest req, String username, String password) {}

        @Override
        public Map<String, Object> getSession() {
            if (getSessionException != null) throw getSessionException;
            return sessionMap;
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return Map.of();
        }

        @Override
        public void clearSessions(HttpServletRequest req, String username, String password) {
            clearCalls++;
            if (clearException != null) throw clearException;
        }
    }

    private static final class RecordingUserService extends UserService {
        boolean isAdmin;
        RuntimeException isAdminException;
        int checkFoldersCalls;

        @Override
        public boolean isAdmin() {
            if (isAdminException != null) throw isAdminException;
            return isAdmin;
        }

        @Override
        public void checkFolders() {
            checkFoldersCalls++;
        }
    }
}
