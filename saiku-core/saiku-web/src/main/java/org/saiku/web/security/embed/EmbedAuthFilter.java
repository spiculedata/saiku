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
import java.util.HashSet;
import java.util.List;
import org.saiku.web.embed.EmbedPublicGrant;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.saiku.web.schedule.OwnerIdentity;
import org.saiku.web.schedule.OwnerIdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a short-lived, locked-down guest identity for valid
 * {@code <saiku-embed>} requests. It acts ONLY on the
 * {@code /rest/saiku/api/embed/} prefixes (query + dashboard + ai + app +
 * authoring) and ONLY when the request presents a valid
 * token OR targets a publicly-granted resource; for every other request it is
 * a transparent pass-through. The mint endpoint
 * ({@code /rest/saiku/api/embed/tokens}) is intentionally NOT touched — it
 * stays behind the standard Spring authenticated rules.
 *
 * <p>On a valid token: a request-scoped {@link PreAuthenticatedAuthenticationToken}
 * with authority {@link #GUEST_ROLE} carrying {@link EmbedGuestDetails} that
 * pin the resource kind + path the token authorises.
 *
 * <p>saiku#1435 (Creator Mode): the {@code authoring/} prefix is the only
 * <em>writing</em> surface an embed identity can reach, and it is gated behind a
 * second role ({@link #AUTHOR_ROLE}) that a read token never carries. The tenant
 * those writes are confined to is derived server-side from the token's
 * {@code saiku.tenantId} claim (or the opaque record's {@code tenantId}) and is
 * validated here, so a request naming a tenant the token doesn't pin fails
 * closed with the same opaque 401 as a bad signature.
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
 *
 * <p><b>saiku#1920 — owner identity is re-resolved on EVERY read.</b> The
 * token / public-registry / JWT records all carry an owner-role <i>snapshot</i>
 * taken at mint time. A snapshot is stale the moment the owner is disabled or
 * demoted, and the guest read then runs under the old (possibly admin) scope.
 * Every request therefore resolves the asserted owner through
 * {@link OwnerIdentityResolver} — the same
 * {@code UserServiceOwnerIdentityResolver} the scheduler uses — and runs
 * under the owner's CURRENT roles. An unknown, disabled, or unresolvable
 * owner is <b>absent</b>, and an absent owner collapses to the same opaque
 * {@code EMBED_INVALID} response as a bad token (fail-closed). This also
 * covers the embed JWT: {@code saiku.owner} / {@code saiku.ownerRoles} are
 * assertions by the embedder, and are now treated as such — the roles are
 * resolved server-side, never read from the token.
 */
public class EmbedAuthFilter extends OncePerRequestFilter {

    public static final String GUEST_ROLE = "ROLE_EMBED_GUEST";
    /** saiku#1435 — second role, carried IN ADDITION to {@link #GUEST_ROLE} and
     *  granted only for a valid {@code authoring} token. It is what the Spring
     *  rule on {@code /rest/saiku/api/embed/authoring/**} demands, so a
     *  query / dashboard / app token can never reach a write endpoint. */
    public static final String AUTHOR_ROLE = "ROLE_EMBED_AUTHOR";

    public static final String TOKEN_HEADER = "X-Saiku-Embed-Token";

    private static final Logger LOG = LoggerFactory.getLogger(EmbedAuthFilter.class);

    /** saiku#1104 — embed JWT (RLS) config. Env wins over system property; the
     *  JWT path is inert until a secret is configured (a presented JWT is then
     *  rejected, not accepted unverified). */
    public static final String ENV_JWT_SECRET = "SAIKU_EMBED_JWT_SECRET";

    public static final String PROP_JWT_SECRET = "saiku.embed.jwt.secret";
    public static final String ENV_JWT_AUDIENCE = "SAIKU_EMBED_JWT_AUDIENCE";
    public static final String PROP_JWT_AUDIENCE = "saiku.embed.jwt.audience";
    /** saiku#1920 — optional {@code iss} pin. When set, the JWT's {@code iss}
     *  claim MUST equal it; the {@code iss} claim itself is always required. */
    public static final String ENV_JWT_ISSUER = "SAIKU_EMBED_JWT_ISSUER";

    public static final String PROP_JWT_ISSUER = "saiku.embed.jwt.issuer";

    /** Read surface — query + dashboard. The mint surface lives elsewhere
     *  and goes through the normal authenticated chain. */
    static final String EMBED_PREFIX = "/rest/saiku/api/embed/";

    static final String QUERY_SEGMENT = "query/";
    static final String DASHBOARD_SEGMENT = "dashboard/";
    static final String AI_SEGMENT = "ai/";
    static final String APP_SEGMENT = "app/";

    /** saiku#1435 (Creator Mode). The pinned resource is a CUBE, not a file, and
     *  the cube ref is followed by an operation segment
     *  ({@code context} / {@code preview} / {@code query} / {@code dashboard} /
     *  {@code objects} / {@code object}), so the target is pinned on the cube
     *  alone and the operation is left to the resource to route. */
    static final String AUTHORING_SEGMENT = "authoring/";

    /** The {@code resourceKind} / claim value an authoring token carries. */
    public static final String AUTHORING_KIND = "authoring";

    /** Cube refs are {@code connection/catalog/schema/cube} — exactly 4 segments. */
    static final int CUBE_REF_SEGMENTS = 4;

    /** HTTP methods the authoring prefix admits. Any other verb falls through to
     *  the Spring chain, which holds no rule for it under the author role and so
     *  refuses it — the embed identity is never the thing that decides. */
    private static final List<String> AUTHORING_METHODS = List.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");

    /** Mint surface — explicitly skipped so a real user's session auth still
     *  applies. */
    static final String MINT_SEGMENT = "tokens";

    private final EmbedTokenStore tokenStore;
    private final EmbedPublicRegistry publicRegistry;
    /** saiku#1920 — live owner identity. Mandatory: every read re-resolves. */
    private final OwnerIdentityResolver ownerResolver;

    public EmbedAuthFilter(
            EmbedTokenStore tokenStore, EmbedPublicRegistry publicRegistry, OwnerIdentityResolver ownerResolver) {
        this.tokenStore = tokenStore;
        this.publicRegistry = publicRegistry;
        this.ownerResolver = ownerResolver;
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

        // saiku#1435: the authoring prefix is the only embed surface that writes.
        // Refuse an unlisted verb here rather than letting the filter establish
        // an identity for it — the verb allowlist is one more thing an attacker
        // can't negotiate around.
        boolean authoring = AUTHORING_KIND.equals(target.kind);
        if (authoring && !AUTHORING_METHODS.contains(req.getMethod())) {
            LOG.debug("embed authoring: refusing method {}", req.getMethod());
            writeInvalid(resp);
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
            // saiku#1435: an authoring token additionally pins a tenant, which
            // is what the write path scopes to. No derivable tenant => unusable
            // (never a fallback scope).
            if (AUTHORING_KIND.equals(token.resourceKind)
                    && !org.saiku.web.embed.EmbedAuthoringScope.isValidTenantId(token.tenantId)) {
                LOG.warn("embed authoring token {} has no usable tenantId — refusing", token.token.length());
                writeInvalid(resp);
                return;
            }
            // saiku#1920: the mint-time role snapshot is NOT trusted — re-resolve
            // the owner now so a disabled/demoted owner loses guest access on
            // the very next request.
            OwnerIdentity owner = resolveOwner(token.createdBy);
            if (owner == null) {
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
                            owner.currentRoles(),
                            // saiku-cloud#948: carry the policy forward so
                            // the view resource can stamp the gateway-
                            // facing redaction-policy header.
                            token.redactionPolicy,
                            // An opaque token has no JWT subject or forced filters; it does carry
                            // the tenant an authoring token is pinned to (saiku#1435).
                            null,
                            null,
                            token.tenantId));
            return;
        }

        // 2. Public-grant path. No token; only resources listed in the public
        //    registry render anonymously — and ONLY when the deployment permits
        //    anonymous public embeds (saiku#1305, saiku.embed.allowPublic). When
        //    disabled, skip the lookup entirely so existing embed-public.json
        //    grants are IGNORED (fail closed) and the request falls through to
        //    the Spring 401, exactly as if no grant existed.
        if (!authoring && EmbedPublicRegistry.publicEmbedsEnabled()) {
            EmbedPublicGrant grant = publicRegistry.lookup(target.kind, target.path);
            if (grant != null) {
                // saiku#1920: same live re-resolution as the token path — a public
                // grant does not outlive the grantor's account.
                OwnerIdentity owner = resolveOwner(grant.grantedBy);
                if (owner != null) {
                    authenticate(
                            req,
                            resp,
                            chain,
                            new EmbedGuestDetails(
                                    null,
                                    grant.resourceKind,
                                    grant.resourcePath,
                                    grant.grantedBy,
                                    owner.currentRoles()));
                    return;
                }
                LOG.warn(
                        "Public embed grant {}{} granted by '{}' whose identity no longer resolves — refusing the"
                                + " anonymous read (fail-closed).",
                        target.kind,
                        target.path,
                        grant.grantedBy);
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
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(GUEST_ROLE));
        // saiku#1435: the author role is added ONLY for an authoring identity, and
        // only after the tenant pin above has been proven. It is what opens the
        // /embed/authoring/** Spring rule, so a read token structurally cannot
        // reach a write endpoint.
        if (details != null && AUTHORING_KIND.equals(details.resourceKind)) {
            authorities.add(new SimpleGrantedAuthority(AUTHOR_ROLE));
        }
        PreAuthenticatedAuthenticationToken auth =
                new PreAuthenticatedAuthenticationToken("embed-guest", details, authorities);
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
            claims = EmbedJwt.verify(compact, secret, jwtAudience(), jwtIssuer(), System.currentTimeMillis());
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
        // saiku#1435: an authoring JWT MUST pin a tenant. The claim is the only
        // thing that decides which folder its bearer may create objects in, so a
        // token without one is refused outright rather than being handed a
        // default scope. Anything unusable (traversal payloads, over-long ids)
        // fails isValidTenantId and lands here too.
        String tenantId = null;
        if (AUTHORING_KIND.equals(claimKind)) {
            tenantId = text(claims, "saiku.tenantId");
            if (!org.saiku.web.embed.EmbedAuthoringScope.isValidTenantId(tenantId)) {
                LOG.debug("embed authoring JWT has no usable saiku.tenantId claim");
                writeInvalid(resp);
                return null;
            }
        }
        JsonNode filters = claims.get("saiku.filters");
        String forcedFiltersJson =
                (filters != null && !filters.isNull() && !filters.isMissingNode()) ? filters.toString() : null;
        // saiku#1920: saiku.owner is an ASSERTION by the embedder; saiku.ownerRoles is
        // ignored entirely. Resolve the owner's live identity server-side and fail closed
        // when it no longer resolves (account deleted, disabled, or demoted since mint).
        OwnerIdentity owner = resolveOwner(text(claims, "saiku.owner"));
        if (owner == null) {
            LOG.warn(
                    "Embed JWT for {}{} names an owner that no longer resolves — refusing the read (fail-closed).",
                    claimKind,
                    claimPath);
            writeInvalid(resp);
            return null;
        }
        // The asserted role claim is never honoured, but a mismatch is worth an audit
        // line: it means the embedder's view of the owner's scope has drifted.
        List<String> asserted = stringArray(claims, "saiku.ownerRoles");
        if (!asserted.isEmpty() && !new HashSet<>(asserted).equals(new HashSet<>(owner.currentRoles()))) {
            LOG.info(
                    "Embed JWT asserted owner roles {} but the live identity of '{}' resolves to {} — using the"
                            + " live identity.",
                    asserted,
                    text(claims, "saiku.owner"),
                    owner.currentRoles());
        }
        return new EmbedGuestDetails(
                compact,
                claimKind,
                claimPath,
                text(claims, "saiku.owner"),
                owner.currentRoles(),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT,
                text(claims, "sub"),
                forcedFiltersJson,
                tenantId);
    }

    /**
     * saiku#1920 — re-resolve the asserted owner's CURRENT identity, or {@code null} when
     * the owner is absent (unknown / disabled / unresolvable) so callers fail closed. Never
     * falls back to the mint-time snapshot.
     */
    private OwnerIdentity resolveOwner(String ownerUser) {
        if (ownerResolver == null) {
            LOG.error("No OwnerIdentityResolver wired into EmbedAuthFilter — refusing the embed read (fail-closed).");
            return null;
        }
        try {
            OwnerIdentity id = ownerResolver.resolve(ownerUser);
            return (id != null && id.present()) ? id : null;
        } catch (RuntimeException e) {
            LOG.warn("Owner identity resolution threw for '{}' — refusing the embed read (fail-closed).", ownerUser, e);
            return null;
        }
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

    /** saiku#1920 — expected {@code iss}; null when the deployment does not pin one
     *  (the {@code iss} CLAIM is still required by {@link EmbedJwt#verify}). */
    private static String jwtIssuer() {
        String i = System.getenv(ENV_JWT_ISSUER);
        if (i == null || i.isBlank()) {
            i = System.getProperty(PROP_JWT_ISSUER);
        }
        return (i == null || i.isBlank()) ? null : i.trim();
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
        } else if (tail.startsWith(APP_SEGMENT)) {
            kind = "app";
            rest = tail.substring(APP_SEGMENT.length());
        } else if (tail.startsWith(AUTHORING_SEGMENT)) {
            // saiku#1435: pin the CUBE, ignore the trailing operation. Parsed
            // before the /page/ + /tile/ strips below so a cube named "page" or
            // "tile" can't have its ref truncated.
            return parseAuthoringTarget(tail.substring(AUTHORING_SEGMENT.length()));
        } else {
            return null;
        }
        // Strip the app-page trailing segment (app tile read/members URLs are
        // ".../app/<path>/page/<pageId>/tile/<tileId>/{query,members}") so the
        // read still maps to the parent app token. Must run BEFORE the /tile/
        // strip because "/page/" precedes "/tile/" in an app URL.
        int pageAt = rest.indexOf("/page/");
        if (pageAt > 0) {
            rest = rest.substring(0, pageAt);
        }
        // Strip the plugin-html trailing segment (saiku#1441) so the token-scoped
        // plugin-html read ".../app/<path>/plugin/<pluginId>/html" still maps to
        // the parent app token. Mirrors the /page/ + /tile/ strips above; without
        // it the resource path resolves to "<app>/plugin/<id>/html" and every
        // plugin fetch 403s before EmbedViewResource ever runs.
        int pluginAt = rest.indexOf("/plugin/");
        if (pluginAt > 0) {
            rest = rest.substring(0, pluginAt);
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

    /**
     * saiku#1435 — the Creator Mode URL shape is
     * {@code /embed/authoring/<connection>/<catalog>/<schema>/<cube>[/<op>]}.
     * The token is pinned on the 4-segment cube ref, exactly like the
     * {@code kind="ai"} cube tokens, so a token minted for cube A can't be
     * replayed against cube B or against a repository file. The optional
     * operation segment is left for the resource to route.
     *
     * @return null when the cube ref is malformed — the caller then falls through
     *     to the Spring chain, which has no author rule for a nameless target
     */
    private static ResourceTarget parseAuthoringTarget(String rest) {
        String decoded = URLDecoder.decode(rest, StandardCharsets.UTF_8);
        String[] segments = decoded.split("/");
        if (segments.length < CUBE_REF_SEGMENTS) {
            return null;
        }
        for (int i = 0; i < CUBE_REF_SEGMENTS; i++) {
            if (segments[i] == null || segments[i].isBlank() || segments[i].contains("..")) {
                return null;
            }
        }
        String cubeRef = String.join("/", segments[0], segments[1], segments[2], segments[3]);
        return new ResourceTarget(AUTHORING_KIND, "/" + cubeRef);
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
        /** saiku#1435 — the tenant an {@code authoring} identity writes for; it
         *  derives the single folder the write path may touch. Always null for
         *  the read-only kinds, so those identities carry no write scope at all. */
        public final String tenantId;

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
            this(token, resourceKind, resourcePath, ownerUser, ownerRoles, redactionPolicy, null, null, null);
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
            this(
                    token,
                    resourceKind,
                    resourcePath,
                    ownerUser,
                    ownerRoles,
                    redactionPolicy,
                    jwtSub,
                    forcedFiltersJson,
                    null);
        }

        /** saiku#1435 — full constructor including the pinned authoring tenant. */
        public EmbedGuestDetails(
                String token,
                String resourceKind,
                String resourcePath,
                String ownerUser,
                List<String> ownerRoles,
                org.saiku.web.embed.EmbedToken.RedactionPolicy redactionPolicy,
                String jwtSub,
                String forcedFiltersJson,
                String tenantId) {
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
            this.tenantId = tenantId;
        }

        /** saiku#1435 — true for a Creator Mode identity (the only kind that may
         *  reach a write endpoint). */
        public boolean isAuthoring() {
            return AUTHORING_KIND.equals(resourceKind);
        }

        /** True when this request reached the resource via a public grant
         *  rather than an opaque token. */
        public boolean isAnonymous() {
            return token == null;
        }
    }
}
