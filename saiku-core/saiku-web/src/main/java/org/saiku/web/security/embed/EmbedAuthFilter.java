/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.embed;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.saiku.web.embed.EmbedPublicGrant;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(EmbedAuthFilter.class);

    /** saiku#1104 — embed JWT (RLS) config. Env wins over system property; the
     *  JWT path is inert until a secret is configured (a presented JWT is then
     *  rejected, not accepted unverified). */
    public static final String ENV_JWT_SECRET = "SAIKU_EMBED_JWT_SECRET";

    public static final String PROP_JWT_SECRET = "saiku.embed.jwt.secret";
    public static final String ENV_JWT_AUDIENCE = "SAIKU_EMBED_JWT_AUDIENCE";
    public static final String PROP_JWT_AUDIENCE = "saiku.embed.jwt.audience";

    /** Read surface — query + dashboard. The mint surface lives elsewhere
     *  and goes through the normal authenticated chain. */
    static final String EMBED_PREFIX = "/rest/saiku/api/embed/";

    static final String QUERY_SEGMENT = "query/";
    static final String DASHBOARD_SEGMENT = "dashboard/";
    static final String AI_SEGMENT = "ai/";

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
            // saiku#1104: an embed JWT (3 segments) carries its own RLS scope +
            // resource pin. Verified + mapped here; opaque tokens fall through.
            if (EmbedJwt.looksLikeJwt(tokenId)) {
                EmbedGuestDetails jwtGuest = authenticateJwt(tokenId, target, resp);
                if (jwtGuest == null) {
                    return; // writeInvalid already sent (fail-closed)
                }
                authenticate(req, resp, chain, jwtGuest);
                return;
            }
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
                            token.ownerRolesSnapshot,
                            // saiku-cloud#948: carry the policy forward so
                            // the view resource can stamp the gateway-
                            // facing redaction-policy header.
                            token.redactionPolicy));
            return;
        }

        // 2. Public-grant path. No token; only resources listed in the public
        //    registry render anonymously — and ONLY when the deployment permits
        //    anonymous public embeds (saiku#1305, saiku.embed.allowPublic). When
        //    disabled, skip the lookup entirely so existing embed-public.json
        //    grants are IGNORED (fail closed) and the request falls through to
        //    the Spring 401, exactly as if no grant existed.
        if (EmbedPublicRegistry.publicEmbedsEnabled()) {
            EmbedPublicGrant grant = publicRegistry.lookup(target.kind, target.path);
            if (grant != null) {
                authenticate(
                        req,
                        resp,
                        chain,
                        new EmbedGuestDetails(
                                null,
                                grant.resourceKind,
                                grant.resourcePath,
                                grant.grantedBy,
                                grant.ownerRolesSnapshot));
                return;
            }
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

    /**
     * saiku#1104 — verify an embed JWT and map its claims to a guest identity,
     * or send the opaque {@code EMBED_INVALID} response and return null on ANY
     * failure (fail-closed). The JWT must pin the same resource the URL targets
     * (replay protection), exactly like the opaque token.
     */
    private EmbedGuestDetails authenticateJwt(String compact, ResourceTarget target, HttpServletResponse resp)
            throws IOException {
        byte[] secret = jwtSecret();
        if (secret == null) {
            // A JWT was presented but no secret is configured — we cannot verify
            // it, so reject rather than accept unverified input.
            writeInvalid(resp);
            return null;
        }
        JsonNode claims;
        try {
            claims = EmbedJwt.verify(compact, secret, jwtAudience(), System.currentTimeMillis());
        } catch (EmbedJwt.EmbedJwtException e) {
            LOG.debug("embed JWT rejected: {}", e.getMessage());
            writeInvalid(resp);
            return null;
        }
        String claimKind = text(claims, "saiku.resourceKind");
        String claimPath = normalizePath(text(claims, "saiku.resourcePath"));
        if (claimKind == null
                || claimPath == null
                || !claimKind.equals(target.kind)
                || !claimPath.equals(target.path)) {
            // Token not minted for this resource — refuse (replay protection).
            writeInvalid(resp);
            return null;
        }
        JsonNode filters = claims.get("saiku.filters");
        String forcedFiltersJson =
                (filters != null && !filters.isNull() && !filters.isMissingNode()) ? filters.toString() : null;
        return new EmbedGuestDetails(
                compact,
                claimKind,
                claimPath,
                text(claims, "saiku.owner"),
                stringArray(claims, "saiku.ownerRoles"),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT,
                text(claims, "sub"),
                forcedFiltersJson);
    }

    /** Embed JWT secret (env &gt; system property); null when unset/blank so the
     *  JWT path stays inert until a deployment opts in. */
    private static byte[] jwtSecret() {
        String s = System.getenv(ENV_JWT_SECRET);
        if (s == null || s.isBlank()) {
            s = System.getProperty(PROP_JWT_SECRET);
        }
        return (s == null || s.isBlank()) ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String jwtAudience() {
        String a = System.getenv(ENV_JWT_AUDIENCE);
        if (a == null || a.isBlank()) {
            a = System.getProperty(PROP_JWT_AUDIENCE);
        }
        return (a == null || a.isBlank()) ? null : a.trim();
    }

    private static String text(JsonNode claims, String field) {
        JsonNode n = claims.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }

    private static List<String> stringArray(JsonNode claims, String field) {
        JsonNode n = claims.get(field);
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : n) {
            if (e.isTextual()) {
                out.add(e.asText());
            }
        }
        return out;
    }

    /** Mirror parseTarget's leading-slash normalization for claim paths. */
    private static String normalizePath(String p) {
        if (p == null) {
            return null;
        }
        return p.startsWith("/") ? p : "/" + p;
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
        } else if (tail.startsWith(AI_SEGMENT)) {
            kind = "ai";
            rest = tail.substring(AI_SEGMENT.length());
        } else {
            return null;
        }
        // Strip the tile-query trailing segment so a tile read still maps to
        // the parent dashboard token. JAX-RS leaves the path as-is in the URI.
        int tileAt = rest.indexOf("/tile/");
        if (tileAt > 0) {
            rest = rest.substring(0, tileAt);
        }
        // Strip the /ask suffix so an ask call still maps to the parent AI cube
        // token. Same pattern as tile-strip above.
        if (rest.endsWith("/ask")) {
            rest = rest.substring(0, rest.length() - "/ask".length());
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
        /** saiku-cloud#948. Carries the token's redaction policy forward so
         *  {@code EmbedViewResource} can stamp the
         *  {@code X-Saiku-Embed-Redaction-Policy} response header for the
         *  saiku-cloud gateway's PiiRedactor to honour. Defaults to
         *  {@link org.saiku.web.embed.EmbedToken.RedactionPolicy#TENANT_DEFAULT}
         *  for public-grant requests (no token to elevate from). */
        public final org.saiku.web.embed.EmbedToken.RedactionPolicy redactionPolicy;
        /** saiku#1104 — end-user identity from the embed JWT's {@code sub}
         *  claim; null for opaque-token / public-grant requests. Audited so the
         *  trail shows who the embedder asserted. */
        public final String jwtSub;
        /** saiku#1104 — the JWT's {@code saiku.filters} claim serialised as a
         *  JSON array (AiFilterSelection shape). The forced RLS slicer the view
         *  injects before execution. Null when no forced filters are present. */
        public final String forcedFiltersJson;

        public EmbedGuestDetails(
                String token, String resourceKind, String resourcePath, String ownerUser, List<String> ownerRoles) {
            this(
                    token,
                    resourceKind,
                    resourcePath,
                    ownerUser,
                    ownerRoles,
                    org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT);
        }

        /** saiku-cloud#948 — explicit-policy constructor. */
        public EmbedGuestDetails(
                String token,
                String resourceKind,
                String resourcePath,
                String ownerUser,
                List<String> ownerRoles,
                org.saiku.web.embed.EmbedToken.RedactionPolicy redactionPolicy) {
            this(token, resourceKind, resourcePath, ownerUser, ownerRoles, redactionPolicy, null, null);
        }

        /** saiku#1104 — full constructor including the embed-JWT claims. */
        public EmbedGuestDetails(
                String token,
                String resourceKind,
                String resourcePath,
                String ownerUser,
                List<String> ownerRoles,
                org.saiku.web.embed.EmbedToken.RedactionPolicy redactionPolicy,
                String jwtSub,
                String forcedFiltersJson) {
            this.token = token;
            this.resourceKind = resourceKind;
            this.resourcePath = resourcePath;
            this.ownerUser = ownerUser;
            this.ownerRoles = ownerRoles == null ? List.of() : List.copyOf(ownerRoles);
            this.redactionPolicy = redactionPolicy == null
                    ? org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT
                    : redactionPolicy;
            this.jwtSub = jwtSub;
            this.forcedFiltersJson = forcedFiltersJson;
        }

        /** True when this request reached the resource via a public grant
         *  rather than an opaque token. */
        public boolean isAnonymous() {
            return token == null;
        }
    }
}
