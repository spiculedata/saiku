/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Alternative authentication mechanism for the native MCP endpoint (saiku#879), coexisting with the
 * HTTP Basic passthrough #878 already wired into {@code applicationContext-saiku.xml}: this filter
 * runs BEFORE {@code BASIC_AUTH_FILTER} and acts only when it sees an {@code Authorization: Bearer}
 * header on an MCP-prefixed request; every other request (Basic, session cookie, no credentials at
 * all) passes through untouched, so an existing #878-only deployment is byte-for-byte unchanged.
 *
 * <p>On a valid token: decodes + validates it (issuer, signature, expiry, and — if configured —
 * audience) via the injected {@link JwtDecoder}, maps its claims to Saiku {@link GrantedAuthority
 * authorities} via {@link OAuthRoleMapper}, and installs a {@link JwtAuthenticationToken} into the
 * {@link SecurityContextHolder} for the request's duration — the same hand-off point #878's Basic
 * provider uses, so {@code SecurityAwareConnectionManager}'s Mondrian role propagation (and the
 * {@code isFullyAuthenticated()} URL gate) need no changes at all.
 *
 * <p>On an invalid/expired token, or a Bearer header presented when this deployment hasn't configured
 * an issuer ({@link OAuthResourceServerProperties#isEnabled()} false): the request is rejected here
 * with {@code 401} + {@code WWW-Authenticate: Bearer error="invalid_token"} (RFC 6750 §3) rather than
 * falling through to Basic, which would never understand the header and would 401 with the wrong
 * challenge (or, worse, silently ignore a token the caller believed authenticated the call).
 *
 * <p>Context is cleared in a {@code finally} — never persisted to the {@code HttpSession} — mirroring
 * the stateless-per-request posture Basic already has on this endpoint.
 */
public class OAuthBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OAuthBearerAuthenticationFilter.class);

    /** Scope: only the streamable-http MCP endpoint (saiku#878's {@code McpResource} @Path). Every
     *  other REST path is untouched by this filter regardless of what headers it carries. */
    static final String MCP_PATH_PREFIX = "/rest/saiku/api/mcp";

    private static final String BEARER_PREFIX = "Bearer ";

    private final OAuthResourceServerProperties properties;
    private final JwtDecoder jwtDecoder;
    private final OAuthRoleMapper roleMapper;

    public OAuthBearerAuthenticationFilter(
            OAuthResourceServerProperties properties, JwtDecoder jwtDecoder, OAuthRoleMapper roleMapper) {
        this.properties = properties;
        this.jwtDecoder = jwtDecoder;
        this.roleMapper = roleMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!pathWithinApp(request).startsWith(MCP_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            // No bearer token on this request — fall through to Basic / session auth, unchanged.
            chain.doFilter(request, response);
            return;
        }

        if (!properties.isEnabled() || jwtDecoder == null) {
            unauthorized(response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            log.debug("MCP bearer token rejected: {}", e.getMessage());
            unauthorized(response);
            return;
        }

        List<GrantedAuthority> authorities = roleMapper.mapAuthorities(jwt);
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        try {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"invalid_token\"}");
    }

    /** Request URI minus the context path — independent of deployment context, mirrors {@code
     *  EmbedAuthFilter}'s helper of the same name. */
    private static String pathWithinApp(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }
}
