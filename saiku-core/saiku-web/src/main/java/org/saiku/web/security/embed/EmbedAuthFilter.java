/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.embed;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.saiku.web.embed.EmbedPublicGrant;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a short-lived, locked-down guest identity for valid
 * {@code <saiku-embed>} reads. It acts ONLY on the {@code /rest/saiku/api/embed/}
 * read prefixes (query + dashboard) and ONLY when the request presents a valid
 * token OR targets a publicly-granted resource; for every other request it is
 * a transparent pass-through. The mint endpoint
 * ({@code /rest/saiku/api/embed/tokens}) is intentionally NOT touched — it
 * stays behind the standard Spring authenticated rules.
 *
 * <p>On a valid token: a request-scoped {@link PreAuthenticatedAuthenticationToken}
 * with authority {@link #GUEST_ROLE} carrying {@link EmbedGuestDetails} that
 * pin the resource kind + path the token authorises.
 *
 * <p>On a public-grant match: same role + details, but with {@code token=null}
 * to mark the request as having used the public path. View endpoints can use
 * this to refuse mutation surfaces (e.g. drillthrough) on public reads even if
 * a future code change accidentally widens the role's permissions.
 *
 * <p>The context is cleared in a {@code finally} so the guest identity is
 * never persisted to the HttpSession — each request re-presents its token /
 * re-checks public state from disk, so revocation takes effect on the very
 * next request and there is no guest "session" to hijack.
 */
public class EmbedAuthFilter extends OncePerRequestFilter {

    public static final String GUEST_ROLE = "ROLE_EMBED_GUEST";
    public static final String TOKEN_HEADER = "X-Saiku-Embed-Token";

    /** Read surface — query + dashboard. The mint surface lives elsewhere
     *  and goes through the normal authenticated chain. */
    static final String EMBED_PREFIX = "/rest/saiku/api/embed/";

    static final String QUERY_SEGMENT = "query/";
    static final String DASHBOARD_SEGMENT = "dashboard/";

    /** Mint surface — explicitly skipped so a real user's session auth still
     *  applies. */
    static final String MINT_SEGMENT = "tokens";

    private final EmbedTokenStore tokenStore;
    private final EmbedPublicRegistry publicRegistry;

    public EmbedAuthFilter(EmbedTokenStore tokenStore, EmbedPublicRegistry publicRegistry) {
        this.tokenStore = tokenStore;
        this.publicRegistry = publicRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        String path = pathWithinApp(req);
        if (!path.startsWith(EMBED_PREFIX)) {
            chain.doFilter(req, resp);
            return;
        }
        String tail = path.substring(EMBED_PREFIX.length());

        // Mint endpoint: pass through; real user's auth applies. Tokens/* is
        // both the mint POST and the revoke DELETE — same authenticated rule.
        if (tail.equals(MINT_SEGMENT) || tail.startsWith(MINT_SEGMENT + "/")) {
            chain.doFilter(req, resp);
            return;
        }

        ResourceTarget target = parseTarget(tail);
        if (target == null) {
            // Unknown sub-path under /embed/ — let the Spring chain decide;
            // it'll 404 or 401 depending on configured rules.
            chain.doFilter(req, resp);
            return;
        }

        // 1. Token path.
        String tokenId = extractToken(req);
        if (tokenId != null && !tokenId.isEmpty()) {
            EmbedToken token = tokenStore.load(tokenId);
            if (token == null || !token.isValid(System.currentTimeMillis())) {
                writeInvalid(resp);
                return;
            }
            // Pin: token must match the requested resource. Otherwise a
            // token minted for dashboard A could be replayed against query B.
            if (!target.kind.equals(token.resourceKind) || !target.path.equals(token.resourcePath)) {
                writeInvalid(resp);
                return;
            }
            authenticate(
                    req,
                    resp,
                    chain,
                    new EmbedGuestDetails(
                            token.token,
                            token.resourceKind,
                            token.resourcePath,
                            token.createdBy,
                            token.ownerRolesSnapshot));
            return;
        }

        // 2. Public-grant path. No token; only resources listed in the
        //    public registry render anonymously.
        EmbedPublicGrant grant = publicRegistry.lookup(target.kind, target.path);
        if (grant != null) {
            authenticate(
                    req,
                    resp,
                    chain,
                    new EmbedGuestDetails(
                            null, grant.resourceKind, grant.resourcePath, grant.grantedBy, grant.ownerRolesSnapshot));
            return;
        }

        // Neither — fall through. The Spring rules will 401 (the embed read
        // intercept-url is hasRole(EMBED_GUEST)), preserving the
        // /info-style "credentials required" semantics of the rest of the
        // surface.
        chain.doFilter(req, resp);
    }

    private void authenticate(
            HttpServletRequest req, HttpServletResponse resp, FilterChain chain, EmbedGuestDetails details)
            throws IOException, ServletException {
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "embed-guest", details, List.of(new SimpleGrantedAuthority(GUEST_ROLE)));
        auth.setDetails(details);
        try {
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, resp);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Parse a sub-path like {@code "query/homes/admin/q.saiku"} into the
     *  kind + resource path. Returns null if the leading segment isn't
     *  recognised or the path is empty. */
    static ResourceTarget parseTarget(String tail) {
        if (tail == null || tail.isEmpty()) {
            return null;
        }
        String kind;
        String rest;
        if (tail.startsWith(QUERY_SEGMENT)) {
            kind = "query";
            rest = tail.substring(QUERY_SEGMENT.length());
        } else if (tail.startsWith(DASHBOARD_SEGMENT)) {
            kind = "dashboard";
            rest = tail.substring(DASHBOARD_SEGMENT.length());
        } else {
            return null;
        }
        // Strip the tile-query trailing segment so a tile read still maps to
        // the parent dashboard token. JAX-RS leaves the path as-is in the URI.
        int tileAt = rest.indexOf("/tile/");
        if (tileAt > 0) {
            rest = rest.substring(0, tileAt);
        }
        if (rest.isEmpty()) {
            return null;
        }
        // The embed resource path is URL-encoded in the URI; decode for
        // comparison against the stored canonical path.
        String decoded = URLDecoder.decode(rest, StandardCharsets.UTF_8);
        // Stored resource paths follow the repository convention of a leading
        // "/" (mirrors ShareToken.dashboardPath); the URI segment after
        // "/embed/query/" or "/embed/dashboard/" doesn't have one, so prepend
        // it to normalize the comparison.
        if (!decoded.startsWith("/")) {
            decoded = "/" + decoded;
        }
        return new ResourceTarget(kind, decoded);
    }

    private static void writeInvalid(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Referrer-Policy", "no-referrer");
        // Collapse "expired", "revoked", "wrong-resource" into one opaque
        // response so a probe can't learn anything about which tokens or
        // resources exist.
        resp.getWriter().write("{\"status\":\"EMBED_INVALID\",\"error\":\"Embed token is invalid or expired.\"}");
    }

    /** Token from the dedicated header ONLY. As with the share flow, we don't
     *  accept {@code ?token=}: it leaks into access logs, proxy logs, browser
     *  history, and the {@code Referer} of outbound assets. The embed JS
     *  reads the host page's attribute and sends it as this header. */
    private static String extractToken(HttpServletRequest req) {
        String h = req.getHeader(TOKEN_HEADER);
        return (h == null || h.isBlank()) ? null : h.trim();
    }

    /** Request URI minus the context path — independent of deployment context
     *  (the launcher serves at root, a WAR install may not). */
    private static String pathWithinApp(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    /** Resource the URL targets — what the filter pins on the
     *  Authentication. */
    static final class ResourceTarget {
        final String kind;
        final String path;

        ResourceTarget(String kind, String path) {
            this.kind = kind;
            this.path = path;
        }
    }

    /** Immutable carrier for the resource the token / public-grant authorises.
     *  {@link #token} is null on a public-grant request — view endpoints can
     *  branch on that to refuse mutation surfaces if a future change widens
     *  the role. */
    public static final class EmbedGuestDetails {
        public final String token;
        public final String resourceKind;
        public final String resourcePath;
        public final String ownerUser;
        public final List<String> ownerRoles;

        public EmbedGuestDetails(
                String token, String resourceKind, String resourcePath, String ownerUser, List<String> ownerRoles) {
            this.token = token;
            this.resourceKind = resourceKind;
            this.resourcePath = resourcePath;
            this.ownerUser = ownerUser;
            this.ownerRoles = ownerRoles == null ? List.of() : List.copyOf(ownerRoles);
        }

        /** True when this request reached the resource via a public grant
         *  rather than an opaque token. */
        public boolean isAnonymous() {
            return token == null;
        }
    }
}
