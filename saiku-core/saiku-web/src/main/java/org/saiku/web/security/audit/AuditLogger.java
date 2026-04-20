/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured JSON audit log for auth-related events.
 *
 * <p>Writes one JSON line per event to the {@code org.saiku.audit} logger at INFO.
 * Operators can route that to a separate file / SIEM with a standard logback
 * appender.
 *
 * <p><strong>What is never logged:</strong> passwords, session tokens, request
 * bodies, or any Authorization header. Username is logged because it's needed
 * to investigate failed logins and abuse — that's the whole point.
 */
public final class AuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger("org.saiku.audit");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditLogger() {}

    public static void loginSuccess(HttpServletRequest req, String username) {
        emit("login_success", req, username, null);
    }

    public static void loginFailure(HttpServletRequest req, String username, String reason) {
        emit("login_failure", req, username, reason);
    }

    public static void logout(HttpServletRequest req, String username) {
        emit("logout", req, username, null);
    }

    public static void accessDenied(HttpServletRequest req, String username, String path) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("path", path);
        emit("access_denied", req, username, null, extra);
    }

    public static void rateLimitTriggered(HttpServletRequest req, String username) {
        emit("rate_limit_triggered", req, username, null);
    }

    private static void emit(String event, HttpServletRequest req, String username, String reason) {
        emit(event, req, username, reason, null);
    }

    private static void emit(
            String event,
            HttpServletRequest req,
            String username,
            String reason,
            Map<String, Object> extra) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("event", event);
        if (username != null) line.put("user", username);
        if (req != null) {
            line.put("ip", clientIp(req));
            String ua = req.getHeader("User-Agent");
            if (ua != null) line.put("ua", truncate(ua, 200));
            line.put("method", req.getMethod());
            line.put("path", req.getRequestURI());
        }
        if (reason != null) line.put("reason", reason);
        if (extra != null) line.putAll(extra);
        try {
            LOG.info(MAPPER.writeValueAsString(line));
        } catch (JsonProcessingException e) {
            // Should be unreachable for a plain String/Instant map; fall back.
            LOG.info("{{\"event\":\"{}\",\"err\":\"json_serialize\"}}", event);
        }
    }

    private static String clientIp(HttpServletRequest req) {
        // Audit IP mirrors rate-limiter choice: only honour X-Forwarded-For when
        // explicitly trusted. Default to remote-addr so forged headers don't
        // appear in the audit trail.
        if (Boolean.parseBoolean(System.getProperty("saiku.auth.trustForwardedFor", "false"))) {
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isEmpty()) {
                int comma = fwd.indexOf(',');
                return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
            }
        }
        return req.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
