/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.web.demo.DemoGate;
import org.saiku.web.demo.DemoGateCookie;
import org.saiku.web.demo.DemoGateException;
import org.saiku.web.demo.DemoGateProvider;

public class DemoGateResourceTest {

    private static final String SECRET = "resource-test-secret-cccccccccccccccccc";

    /** Configurable in-memory provider — no network, no Mockito. */
    private static final class FakeProvider implements DemoGateProvider {
        boolean sendCalled;
        String lastEmail;
        String lastCode;
        String lastFirstName;
        String lastLastName;
        boolean verifyResult = true;
        boolean throwOnSend;
        boolean throwOnVerify;

        @Override
        public void sendCode(String email) throws DemoGateException {
            if (throwOnSend) throw new DemoGateException("boom");
            sendCalled = true;
            lastEmail = email;
        }

        @Override
        public boolean verifyCode(String email, String code, String firstName, String lastName)
                throws DemoGateException {
            if (throwOnVerify) throw new DemoGateException("boom");
            lastEmail = email;
            lastCode = code;
            lastFirstName = firstName;
            lastLastName = lastName;
            return verifyResult;
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    /** Minimal HttpServletRequest via dynamic proxy — only the methods the resource touches. */
    private static HttpServletRequest req(Cookie[] cookies) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class[] {HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRemoteAddr":
                            return "10.0.0.1";
                        case "getCookies":
                            return cookies;
                        case "getHeader":
                            return null;
                        default:
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    private DemoGateResource resource(FakeProvider p, boolean enabled) {
        DemoGate gate = new DemoGate(enabled, p, new DemoGateCookie(SECRET, 1000));
        DemoGateResource r = new DemoGateResource();
        r.setGate(gate);
        return r;
    }

    @Test
    public void request_disabledGateReturns503() {
        Response resp = resource(new FakeProvider(), false).request(req(null), Map.of("email", "u@e.com"));
        assertEquals(503, resp.getStatus());
    }

    @Test
    public void request_rejectsBadEmail() {
        FakeProvider p = new FakeProvider();
        Response resp = resource(p, true).request(req(null), Map.of("email", "notanemail"));
        assertEquals(400, resp.getStatus());
        assertFalse(p.sendCalled);
    }

    @Test
    public void request_sendsCodeAndNormalizesEmail() {
        FakeProvider p = new FakeProvider();
        Response resp = resource(p, true).request(req(null), Map.of("email", "User@Example.com"));
        assertEquals(204, resp.getStatus());
        assertTrue(p.sendCalled);
        assertEquals("user@example.com", p.lastEmail);
    }

    @Test
    public void verify_wrongCodeReturns400AndNoCookie() {
        FakeProvider p = new FakeProvider();
        p.verifyResult = false;
        Map<String, String> body = new HashMap<>();
        body.put("email", "u@e.com");
        body.put("code", "000000");
        Response resp = resource(p, true).verify(req(null), body);
        assertEquals(400, resp.getStatus());
        assertNull(resp.getHeaderString("Set-Cookie"));
    }

    @Test
    public void verify_successSetsHardenedCookie() {
        FakeProvider p = new FakeProvider();
        p.verifyResult = true;
        Map<String, String> body = new HashMap<>();
        body.put("email", "u@e.com");
        body.put("code", "123456");
        Response resp = resource(p, true).verify(req(null), body);
        assertEquals(204, resp.getStatus());
        String setCookie = resp.getHeaderString("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith(DemoGateCookie.COOKIE_NAME + "="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
    }

    @Test
    public void verify_passesFirstAndLastNameToProvider() {
        FakeProvider p = new FakeProvider();
        p.verifyResult = true;
        Map<String, String> body = new HashMap<>();
        body.put("email", "u@e.com");
        body.put("code", "123456");
        body.put("firstName", "Juan");
        body.put("lastName", "Resendiz");
        Response resp = resource(p, true).verify(req(null), body);
        assertEquals(204, resp.getStatus());
        assertEquals("Juan", p.lastFirstName);
        assertEquals("Resendiz", p.lastLastName);
    }

    @Test
    public void verify_missingCodeReturns400() {
        Map<String, String> body = new HashMap<>();
        body.put("email", "u@e.com");
        Response resp = resource(new FakeProvider(), true).verify(req(null), body);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void verify_providerFailureReturns502() {
        FakeProvider p = new FakeProvider();
        p.throwOnVerify = true;
        Map<String, String> body = new HashMap<>();
        body.put("email", "u@e.com");
        body.put("code", "123456");
        Response resp = resource(p, true).verify(req(null), body);
        assertEquals(502, resp.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void status_reportsUnverifiedWithoutCookie() {
        Response resp = resource(new FakeProvider(), true).status(req(null));
        assertEquals(200, resp.getStatus());
        Map<String, Object> b = (Map<String, Object>) resp.getEntity();
        assertEquals(Boolean.TRUE, b.get("enabled"));
        assertEquals(Boolean.FALSE, b.get("verified"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void status_reportsVerifiedWithValidCookie() {
        String value = new DemoGateCookie(SECRET, 1000).sign("u@e.com");
        Cookie[] cookies = {new Cookie(DemoGateCookie.COOKIE_NAME, value)};
        Response resp = resource(new FakeProvider(), true).status(req(cookies));
        Map<String, Object> b = (Map<String, Object>) resp.getEntity();
        assertEquals(Boolean.TRUE, b.get("verified"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void status_disabledGateReportsDisabled() {
        Response resp = resource(new FakeProvider(), false).status(req(null));
        Map<String, Object> b = (Map<String, Object>) resp.getEntity();
        assertEquals(Boolean.FALSE, b.get("enabled"));
        assertEquals(Boolean.FALSE, b.get("verified"));
    }
}
