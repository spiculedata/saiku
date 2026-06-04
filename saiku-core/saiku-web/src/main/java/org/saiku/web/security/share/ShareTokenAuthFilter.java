/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.share;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a short-lived, locked-down guest identity for valid dashboard
 * share links (issue #941). It acts ONLY on the {@code /rest/saiku/share/view/}
 * prefix and ONLY when a share token is presented; for every other request it
 * is a transparent pass-through.
 *
 * <p>On a valid (existing, non-revoked, unexpired) token it sets a
 * <b>request-scoped</b> {@link PreAuthenticatedAuthenticationToken} whose only
 * authority is {@code ROLE_SHARE_GUEST} and whose details pin the single
 * dashboard path the token authorises. The Spring Security rules grant that
 * role access to {@code /share/view/**} and nothing else, so a guest can never
 * reach {@code /ai/query}, drill-through, other dashboards, the repository, or
 * the mint endpoints — escalation is structurally impossible.
 *
 * <p>The context is cleared in a {@code finally} so it is never written to the
 * HttpSession: each guest request re-presents its token and is re-validated
 * from disk, so revocation and expiry take effect on the very next request and
 * there is no guest "session" to hijack.
 */
public class ShareTokenAuthFilter extends OncePerRequestFilter {

    /** Path (relative to context) the guest role is scoped to. */
    static final String VIEW_PREFIX = "/rest/saiku/share/view/";

    public static final String GUEST_ROLE = "ROLE_SHARE_GUEST";
    static final String TOKEN_HEADER = "X-Saiku-Share-Token";
    static final String TOKEN_PARAM = "token";

    private final ShareTokenStore store;

    public ShareTokenAuthFilter(ShareTokenStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = pathWithinApp(req);
        if (!path.startsWith(VIEW_PREFIX)) {
            chain.doFilter(req, resp);
            return;
        }

        String tokenId = extractToken(req);
        if (tokenId == null || tokenId.isEmpty()) {
            // No token on a guest path — could be an authenticated admin
            // previewing the link; let the chain's auth rules decide.
            chain.doFilter(req, resp);
            return;
        }

        ShareToken token = store.load(tokenId);
        if (token == null || !token.isValid(System.currentTimeMillis())) {
            // Expired and revoked both collapse to SHARE_INVALID so we never
            // reveal whether a given dashboard / token ever existed.
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"status\":\"SHARE_INVALID\",\"error\":\"Share link is invalid or expired.\"}");
            return;
        }

        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "share-guest", token.token, List.of(new SimpleGrantedAuthority(GUEST_ROLE)));
        // Pin the authorised dashboard to the principal — ShareViewResource
        // reads it from here, never from client input.
        auth.setDetails(new ShareGuestDetails(token.token, token.dashboardPath, token.ownerRolesSnapshot));
        try {
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, resp);
        } finally {
            // Never persist a guest context to the session.
            SecurityContextHolder.clearContext();
        }
    }

    /** Token from the dedicated header, falling back to a query param for the
     *  initial bootstrap GET (the SPA otherwise sends it as a header). */
    private static String extractToken(HttpServletRequest req) {
        String h = req.getHeader(TOKEN_HEADER);
        if (h != null && !h.isBlank()) {
            return h.trim();
        }
        String q = req.getParameter(TOKEN_PARAM);
        return q == null ? null : q.trim();
    }

    /** Request URI minus the context path, so matching is independent of the
     *  deployment context (the launcher serves at root, a WAR may not). */
    private static String pathWithinApp(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    /** Immutable carrier for the token-pinned dashboard + the owner's data scope. */
    public static final class ShareGuestDetails {
        public final String token;
        public final String dashboardPath;
        public final List<String> ownerRoles;

        public ShareGuestDetails(String token, String dashboardPath, List<String> ownerRoles) {
            this.token = token;
            this.dashboardPath = dashboardPath;
            this.ownerRoles = ownerRoles == null ? List.of() : List.copyOf(ownerRoles);
        }
    }
}
