/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.saiku.web.demo.DemoGate;
import org.saiku.web.demo.DemoGateCookie;
import org.saiku.web.demo.DemoGateException;
import org.saiku.web.security.ratelimit.LoginRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo email-gate endpoints (saiku#1029), mounted at
 * {@code /rest/saiku/demo/gate}. Anonymous-accessible (allowlisted by
 * {@code DemoGateFilter} and permitAll in the Spring Security chain) — they run
 * <em>before</em> any login, since their whole job is to unlock the login form.
 *
 * <ul>
 *   <li>{@code POST /request} {@code {email}} — send a code.</li>
 *   <li>{@code POST /verify} {@code {email, code}} — on success set the signed
 *       {@code saiku_demo_verified} cookie (the marker the filter checks).</li>
 *   <li>{@code GET /status} — report {@code {enabled, verified}} so the SPA can
 *       decide whether to show the gate (the cookie is HttpOnly, so the client
 *       can't read it directly).</li>
 * </ul>
 *
 * <p>The cookie does not authenticate anyone — admin/admin is still required at
 * {@code POST /rest/saiku/session} afterwards.
 */
@Path("/saiku/demo/gate")
public class DemoGateResource {

    private static final Logger log = LoggerFactory.getLogger(DemoGateResource.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private DemoGate gate;
    private LoginRateLimiter rateLimiter = new LoginRateLimiter();
    private final boolean secureCookie =
            Boolean.parseBoolean(System.getProperty("saiku.session.cookie.secure", "false"));

    public void setGate(DemoGate gate) {
        this.gate = gate;
    }

    public void setRateLimiter(LoginRateLimiter rateLimiter) {
        if (rateLimiter != null) this.rateLimiter = rateLimiter;
    }

    @POST
    @Path("/request")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response request(@Context HttpServletRequest req, Map<String, String> body) {
        if (!enabled()) return disabled();
        // Rate-limit sends too, so a bot can't make us blast WorkOS (which would
        // bill us per send/verify) — same per-IP limiter as login + verify.
        if (rateLimiter.isBlocked(req)) return rateLimited();
        String email = email(body);
        if (email == null) return badEmail();
        try {
            gate.provider().sendCode(email);
            return Response.noContent().build();
        } catch (DemoGateException e) {
            rateLimiter.recordFailure(req);
            log.warn("demo gate send failed for {}: {}", email, e.getMessage());
            return providerError("Could not send the code. Please try again.");
        }
    }

    @POST
    @Path("/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verify(@Context HttpServletRequest req, Map<String, String> body) {
        if (!enabled()) return disabled();
        if (rateLimiter.isBlocked(req)) return rateLimited();
        String email = email(body);
        if (email == null) return badEmail();
        String code = body == null ? null : trim(body.get("code"));
        if (code == null || code.isBlank()) {
            return error(400, "VALIDATION_ERROR", "Code is required.", "code");
        }
        try {
            boolean ok = gate.provider().verifyCode(email, code);
            if (!ok) {
                rateLimiter.recordFailure(req);
                return error(400, "INVALID_CODE", "That code is invalid or has expired.", "code");
            }
            rateLimiter.recordSuccess(req);
            String setCookie = gate.cookie().setCookieHeader(email, secureCookie);
            return Response.noContent().header("Set-Cookie", setCookie).build();
        } catch (DemoGateException e) {
            log.warn("demo gate verify failed for {}: {}", email, e.getMessage());
            return providerError("Could not verify the code. Please try again.");
        }
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@Context HttpServletRequest req) {
        boolean enabled = enabled();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("verified", enabled && hasValidCookie(req));
        out.put("provider", enabled ? gate.providerName() : null);
        return Response.ok(out).build();
    }

    /* --------------------------- helpers --------------------------- */

    private boolean enabled() {
        return gate != null && gate.isEnabled();
    }

    private boolean hasValidCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return false;
        DemoGateCookie cv = gate.cookie();
        for (Cookie c : cookies) {
            if (DemoGateCookie.COOKIE_NAME.equals(c.getName()) && cv.verify(c.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static String email(Map<String, String> body) {
        if (body == null) return null;
        String e = trim(body.get("email"));
        if (e == null) return null;
        e = e.toLowerCase(java.util.Locale.ROOT);
        return EMAIL.matcher(e).matches() ? e : null;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Response disabled() {
        return error(503, "GATE_DISABLED", "The email gate is not enabled on this deployment.", null);
    }

    private static Response badEmail() {
        return error(400, "VALIDATION_ERROR", "A valid email address is required.", "email");
    }

    private Response rateLimited() {
        return Response.status(429)
                .header("Retry-After", String.valueOf(rateLimiter.getWindowMs() / 1000))
                .entity(Map.of("status", "RATE_LIMITED", "error", "Too many attempts. Please try again later."))
                .build();
    }

    private static Response providerError(String msg) {
        return error(502, "PROVIDER_ERROR", msg, null);
    }

    private static Response error(int status, String code, String message, String field) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", code);
        out.put("error", message);
        if (field != null) out.put("field", field);
        return Response.status(status).entity(out).build();
    }
}
