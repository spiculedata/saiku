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
import org.saiku.web.schedule.OwnerIdentity;
import org.saiku.web.schedule.OwnerIdentityResolver;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><b>saiku#1920 — owner identity is re-resolved on every read.</b> The
 * share token's {@code ownerRolesSnapshot} is captured at mint time and goes
 * stale the moment the owner is disabled or demoted; the guest would then keep
 * reading the dashboard under the old (possibly admin) data scope. Each
 * request therefore resolves {@link ShareToken#createdBy} through
 * {@link OwnerIdentityResolver} and runs under the owner's CURRENT roles. An
 * unknown / disabled / unresolvable owner is absent, and absent collapses to
 * the same opaque {@code SHARE_INVALID} response (fail-closed).
 */
public class ShareTokenAuthFilter extends OncePerRequestFilter {

    /** Path (relative to context) the guest role is scoped to. */
    static final String VIEW_PREFIX = "/rest/saiku/share/view/";

    public static final String GUEST_ROLE = "ROLE_SHARE_GUEST";
    static final String TOKEN_HEADER = "X-Saiku-Share-Token";

    private static final Logger log = LoggerFactory.getLogger(ShareTokenAuthFilter.class);

    private final ShareTokenStore store;
    /** saiku#1920 — live owner identity; mandatory, every read re-resolves. */
    private final OwnerIdentityResolver ownerResolver;

    public ShareTokenAuthFilter(ShareTokenStore store, OwnerIdentityResolver ownerResolver) {
        this.store = store;
        this.ownerResolver = ownerResolver;
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
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.setHeader("Cache-Control", "no-store");
            resp.setHeader("Referrer-Policy", "no-referrer");
            resp.getWriter().write("{\"status\":\"SHARE_INVALID\",\"error\":\"Share link is invalid or expired.\"}");
            return;
        }

        // saiku#1920: the mint-time role snapshot is not trusted — a share link does not
        // outlive its owner's account. Resolve the owner's live identity or fail closed.
        OwnerIdentity owner = resolveOwner(token.createdBy);
        if (owner == null) {
            writeInvalid(resp);
            return;
        }

        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "share-guest", token.token, List.of(new SimpleGrantedAuthority(GUEST_ROLE)));
        // Pin the authorised dashboard to the principal — ShareViewResource
        // reads it from here, never from client input.
        auth.setDetails(new ShareGuestDetails(token.token, token.dashboardPath, token.createdBy, owner.currentRoles()));
        try {
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, resp);
        } finally {
            // Never persist a guest context to the session.
            SecurityContextHolder.clearContext();
        }
    }

    /** The same opaque response as a bad/expired token — a probe must not be able to
     *  tell "this share link is stale" from "this token does not exist". */
    private static void writeInvalid(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Referrer-Policy", "no-referrer");
        resp.getWriter().write("{\"status\":\"SHARE_INVALID\",\"error\":\"Share link is invalid or expired.\"}");
    }

    /** saiku#1920 — re-resolve the owner, or null when absent (fail-closed). */
    private OwnerIdentity resolveOwner(String ownerUser) {
        if (ownerResolver == null) {
            log.error("No OwnerIdentityResolver wired into ShareTokenAuthFilter — refusing the share read"
                    + " (fail-closed).");
            return null;
        }
        try {
            OwnerIdentity id = ownerResolver.resolve(ownerUser);
            return (id != null && id.present()) ? id : null;
        } catch (RuntimeException e) {
            log.warn("Owner identity resolution threw for '{}' — refusing the share read (fail-closed).", ownerUser, e);
            return null;
        }
    }

    /** Token from the dedicated header ONLY. We deliberately do NOT accept a
     *  {@code ?token=} query param: the token is the sole view-authority secret,
     *  and a URL param would leak it into the servlet container's access log,
     *  any fronting proxy's logs, browser history, and the {@code Referer} of
     *  outbound links/images. The SPA reads the token from the share-link URL
     *  (fragment) and replays it as this header on every API call (#941). */
    private static String extractToken(HttpServletRequest req) {
        String h = req.getHeader(TOKEN_HEADER);
        return (h == null || h.isBlank()) ? null : h.trim();
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
        public final String ownerUser;
        public final List<String> ownerRoles;

        public ShareGuestDetails(String token, String dashboardPath, String ownerUser, List<String> ownerRoles) {
            this.token = token;
            this.dashboardPath = dashboardPath;
            this.ownerUser = ownerUser;
            this.ownerRoles = ownerRoles == null ? List.of() : List.copyOf(ownerRoles);
        }
    }
}
