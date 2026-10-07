/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.scim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.saiku.web.scim.ScimError;
import org.saiku.web.scim.ScimRateLimiter;
import org.saiku.web.scim.ScimToken;
import org.saiku.web.scim.ScimTokenStore;
import org.saiku.web.security.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SCIM 2.0 bearer authentication + per-token rate limiting (issue #1438).
 *
 * <p>SCIM has its own auth conventions and is deliberately kept <b>isolated</b> from the Saiku
 * session/Basic surface: it acts only on {@code /rest/scim/v2/**}, and no other surface accepts a
 * SCIM bearer. Conversely a SCIM token buys <b>only</b> the SCIM surface — the principal it
 * establishes carries a single authority, {@code ROLE_SCIM}, which no other URL rule grants.
 *
 * <p>On success it sets a <b>request-scoped</b> {@link PreAuthenticatedAuthenticationToken} and
 * clears the context in a {@code finally}, so it is never written to the HttpSession: a SCIM
 * connector re-presents its bearer on every call, and revocation takes effect on the very next
 * request with no session to invalidate.
 *
 * <p>Everything about a call is audit-logged ({@code org.saiku.audit}) with the token label, the
 * IdP it is labelled for, the operation, and the outcome — the acceptance criteria require
 * token label + IdP + operation type on every call. The bearer itself is never logged.
 */
public class ScimAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScimAuthFilter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Context-relative prefix this filter claims. Jersey is prefix-mapped at {@code /rest/*}, so
     * the SCIM URLs an IdP is configured with are {@code https://host/rest/scim/v2/...}.
     */
    public static final String SCIM_PREFIX = "/rest/scim/v2";

    public static final String SCIM_ROLE = "ROLE_SCIM";

    private final ScimTokenStore tokenStore;
    private final ScimRateLimiter rateLimiter;

    public ScimAuthFilter(ScimTokenStore tokenStore, ScimRateLimiter rateLimiter) {
        this.tokenStore = tokenStore;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = pathWithinApp(req);
        if (!isScimPath(path)) {
            chain.doFilter(req, resp);
            return;
        }

        String secret = bearerToken(req);
        ScimToken token = secret == null ? null : tokenStore.load(secret);
        if (token == null) {
            // One answer for "no bearer", "unknown bearer" and "revoked bearer": an IdP operator
            // must not be able to probe which token ids exist.
            auditFailure(req, secret == null ? "missing_bearer" : "invalid_bearer");
            writeError(resp, 401, null, "A valid SCIM bearer token is required");
            return;
        }

        if (!rateLimiter.tryAcquire(token.id)) {
            auditCall(req, token, "rate_limited", 429);
            resp.setHeader("Retry-After", String.valueOf(rateLimiter.retryAfterSeconds()));
            writeError(resp, 429, null, "SCIM request rate limit exceeded");
            return;
        }

        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "scim:" + token.id, null, List.of(new SimpleGrantedAuthority(SCIM_ROLE)));
        auth.setDetails(new ScimPrincipal(token));
        try {
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, resp);
        } finally {
            SecurityContextHolder.clearContext();
        }
        // Last-use is stamped after the chain so a failed request still counts as "the IdP used
        // this credential" — that is what an operator reviewing access logs is looking for.
        tokenStore.touch(token);
        auditCall(req, token, "ok", responseStatus(resp));
    }

    /** True for the SCIM base path and everything under it. */
    public static boolean isScimPath(String pathWithinApp) {
        return pathWithinApp != null
                && (pathWithinApp.equals(SCIM_PREFIX) || pathWithinApp.startsWith(SCIM_PREFIX + "/"));
    }

    /**
     * The current request's SCIM token metadata, or {@code null} when the caller is not a SCIM
     * principal. Resources read the label/IdP from here for their own audit lines.
     */
    public static ScimToken currentToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof ScimPrincipal p) {
            return p.token;
        }
        return null;
    }

    private static String bearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() < 8 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String secret = trimmed.substring(7).trim();
        return secret.isEmpty() ? null : secret;
    }

    private static String pathWithinApp(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    private static int responseStatus(HttpServletResponse resp) {
        return resp.getStatus() >= 200 ? resp.getStatus() : 200;
    }

    private static void auditFailure(HttpServletRequest req, String reason) {
        AuditLogger.scim(req, null, null, null, "auth", reason, 401);
    }

    private static void auditCall(HttpServletRequest req, ScimToken token, String outcome, int status) {
        AuditLogger.scim(
                req,
                token == null ? null : token.label,
                token == null ? null : token.id,
                token == null ? null : token.idp,
                req.getMethod(),
                outcome,
                status);
    }

    private static void writeError(HttpServletResponse resp, int status, String scimType, String detail)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/scim+json;charset=UTF-8");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Cache-Control", "no-store");
        ScimError body = new ScimError(String.valueOf(status), scimType, detail);
        try {
            resp.getOutputStream().write(MAPPER.writeValueAsBytes(body));
        } catch (JsonProcessingException e) {
            // Unreachable for a String-only DTO; keep the status honest rather than 500-ing.
            log.debug("Could not serialise SCIM error body", e);
        }
    }

    /** Immutable principal detail: the token record behind the current request. */
    public static final class ScimPrincipal {
        public final ScimToken token;

        ScimPrincipal(ScimToken token) {
            this.token = token;
        }
    }
}
