/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.embed;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.repository.AclEntry;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.web.embed.EmbedPublicGrant;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner-facing CRUD for the {@code <saiku-embed>} surface:
 *
 * <ul>
 *   <li>{@code POST /saiku/api/embed/tokens} — mint a token for one query or
 *       dashboard. Requires a logged-in user who can GRANT the target.</li>
 *   <li>{@code GET /saiku/api/embed/tokens} — list the caller's tokens (or
 *       all, if admin).</li>
 *   <li>{@code DELETE /saiku/api/embed/tokens/{id}} — revoke a token (creator
 *       / admin / current-GRANT holder may revoke).</li>
 *   <li>{@code POST /saiku/api/embed/public} — flip a resource publicly
 *       embeddable.</li>
 *   <li>{@code GET /saiku/api/embed/public} — list the caller's public
 *       grants (or all, if admin).</li>
 *   <li>{@code DELETE /saiku/api/embed/public} — revoke a public grant.</li>
 * </ul>
 *
 * <p>Everything here sits behind {@code isFullyAuthenticated()} — the guest
 * read surface ({@link EmbedViewResource}) is the only place tokens / public
 * grants are consumed. Mirrors {@code ShareTokenResource} (saiku#941) in
 * authorisation posture: only a user with GRANT on the resource may mint /
 * publicly-grant, only the creator / admin / current-GRANT holder may revoke.
 */
@Path("/saiku/api/embed")
public class EmbedTokenResource {

    private static final Logger log = LoggerFactory.getLogger(EmbedTokenResource.class);

    // Same conservative defaults as share: leaked links should expire on a
    // human timescale, and an account can't be allowed to mint forever.
    private static final long DEFAULT_TTL_HOURS = 72; // 3 days
    private static final long MAX_TTL_HOURS = 720; // 30 days
    private static final int MAX_ACTIVE_TOKENS_PER_USER = 200;
    private static final int MAX_PUBLIC_GRANTS_PER_USER = 200;

    private EmbedTokenStore tokenStore;
    private EmbedPublicRegistry publicRegistry;
    private DatasourceService datasourceService;
    private SessionService sessionService;
    private UserService userService;
    /** saiku-cloud#940. Resolves a resource's bound cubes + walks their
     *  AiSchemas for {@code saiku.semantic.pii=true} annotations. Optional —
     *  when null (Spring bean missing, stub context), the PII gate is a
     *  no-op and the surrounding GRANT check remains the load-bearing
     *  security check. */
    private org.saiku.web.embed.EmbedPiiInspector piiInspector;

    public void setTokenStore(EmbedTokenStore s) {
        this.tokenStore = s;
    }

    public void setPublicRegistry(EmbedPublicRegistry r) {
        this.publicRegistry = r;
    }

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setUserService(UserService s) {
        this.userService = s;
    }

    public void setPiiInspector(org.saiku.web.embed.EmbedPiiInspector i) {
        this.piiInspector = i;
    }

    /* ---------------------------- tokens ---------------------------- */

    public static class MintRequest {
        public String resourceKind;
        public String resourcePath;
        public Integer ttlHours;
        public String label;
    }

    @POST
    @Path("/tokens")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mint(MintRequest body) {
        if (body == null) {
            return badRequest("body", "request body required");
        }
        String kind = body.resourceKind;
        String path = body.resourcePath;
        Response kindError = validateKindAndPath(kind, path);
        if (kindError != null) return kindError;

        String username = currentUsername();
        List<String> roles = currentRoles();

        // AI-kind tokens pin a cube (not a repository file), so the file-ACL grant check
        // doesn't apply. Gate on admin only for v1 — a follow-up can add cube-level ACLs.
        if ("ai".equals(kind)) {
            if (!isAdmin(roles)) {
                return forbidden("AI embed tokens require admin privileges");
            }
        } else if (!hasGrant(path, username, roles)) {
            return forbidden("You don't have permission to embed this resource");
        }

        long ttlHours = body.ttlHours == null ? DEFAULT_TTL_HOURS : body.ttlHours;
        if (ttlHours <= 0) {
            return badRequest("ttlHours", "ttlHours must be positive");
        }
        ttlHours = Math.min(ttlHours, MAX_TTL_HOURS);

        long active = tokenStore.listByOwner(username).stream()
                .filter(t -> t.isValid(System.currentTimeMillis()))
                .count();
        if (active >= MAX_ACTIVE_TOKENS_PER_USER) {
            return Response.status(429)
                    .entity(Map.of("status", "LIMIT", "error", "Too many active embed tokens; revoke some first"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // saiku-cloud#940 mint-time PII gate. Determines the redaction
        // posture the read path will apply. PII-touching resources get
        // FORCE_ON so a token minted at a workspace's relaxed default tier
        // still gets max-strength redaction on individual sensitive
        // resources. Non-PII resources stay TENANT_DEFAULT — the surrounding
        // tier setting controls the policy as before.
        EmbedToken.RedactionPolicy redaction = EmbedToken.RedactionPolicy.TENANT_DEFAULT;
        if (piiInspector != null) {
            org.saiku.web.embed.EmbedPiiInspector.Result inspection = piiInspector.inspect(kind, path, username, roles);
            if (inspection.referencesPii) {
                redaction = EmbedToken.RedactionPolicy.FORCE_ON;
                log.info(
                        "embed-token mint elevated to FORCE_ON (PII on {}): kind={} path={} by={} cubes={}",
                        inspection.fellOpenForUnresolvable ? "unresolvable resource" : "annotated columns",
                        kind,
                        path,
                        username,
                        inspection.cubeIds);
            }
        }
        EmbedToken t = tokenStore.create(kind, path, username, roles, ttlHours * 3600_000L, body.label, redaction);
        log.info(
                "embed-token minted token=<redacted:{}> kind={} path={} by={} ttlHours={} redaction={}",
                t.token.length(),
                kind,
                path,
                username,
                ttlHours,
                t.redactionPolicy);
        return Response.ok(Map.of(
                        "status",
                        "OK",
                        "token",
                        t.token,
                        "resourceKind",
                        kind,
                        "resourcePath",
                        path,
                        "redactionPolicy",
                        t.redactionPolicy.name(),
                        "expiresAt",
                        t.expiresAt))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @GET
    @Path("/tokens")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTokens(@QueryParam("all") @DefaultValue("false") boolean all) {
        String username = currentUsername();
        List<EmbedToken> tokens =
                (all && isAdmin(currentRoles())) ? tokenStore.listAll() : tokenStore.listByOwner(username);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmbedToken t : tokens) {
            // Owner gets back the token id (they minted it). Never return the
            // owner role snapshot — internal data-scope state, not API data.
            // 10 entries — Map.of's max overload. New fields go through
            // Map.ofEntries below.
            out.add(Map.of(
                    "token", t.token,
                    "resourceKind", t.resourceKind,
                    "resourcePath", t.resourcePath,
                    "createdBy", t.createdBy,
                    "createdAt", t.createdAt,
                    "expiresAt", t.expiresAt,
                    "revoked", t.revoked,
                    "label", t.label == null ? "" : t.label,
                    "active", t.isValid(System.currentTimeMillis()),
                    // saiku-cloud#940. So admins can audit which tokens have
                    // FORCE_ON without re-running the inspector.
                    "redactionPolicy",
                            t.redactionPolicy == null
                                    ? EmbedToken.RedactionPolicy.TENANT_DEFAULT.name()
                                    : t.redactionPolicy.name()));
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/tokens/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response revokeToken(@PathParam("id") String id) {
        EmbedToken t = tokenStore.load(id);
        if (t == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "Unknown embed token"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        boolean canManage = (username != null && username.equals(t.createdBy))
                || isAdmin(roles)
                || hasGrant(t.resourcePath, username, roles);
        if (!canManage) {
            return forbidden("You can't revoke this embed token");
        }
        tokenStore.revoke(id);
        log.info("embed-token revoked kind={} path={} by={}", t.resourceKind, t.resourcePath, username);
        return Response.ok(Map.of("status", "OK"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /* --------------------------- public ----------------------------- */

    public static class GrantRequest {
        public String resourceKind;
        public String resourcePath;
        public String label;
    }

    @POST
    @Path("/public")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response grantPublic(GrantRequest body) {
        // saiku#1305 — fail closed: when the deployment disables anonymous public
        // embeds (saiku.embed.allowPublic=false), refuse to mint any new public
        // grant. Pairs with EmbedAuthFilter ignoring existing grants while off.
        if (!EmbedPublicRegistry.publicEmbedsEnabled()) {
            return forbidden("Public embed grants are disabled on this deployment (saiku.embed.allowPublic=false).");
        }
        if (body == null) {
            return badRequest("body", "request body required");
        }
        String kind = body.resourceKind;
        String path = body.resourcePath;
        Response kindError = validateKindAndPath(kind, path);
        if (kindError != null) return kindError;

        String username = currentUsername();
        List<String> roles = currentRoles();
        if (!hasGrant(path, username, roles)) {
            return forbidden("You don't have permission to make this resource public");
        }

        // saiku-cloud#940 — refuse public grants on PII-touching resources.
        // Anonymous-public read is the highest-blast-radius surface on the
        // embed: a public-grant means literally anyone with the URL can
        // read the cellset on an attacker-controlled host page. We
        // categorically refuse rather than try to redact post-hoc.
        if (piiInspector != null) {
            org.saiku.web.embed.EmbedPiiInspector.Result inspection = piiInspector.inspect(kind, path, username, roles);
            if (inspection.referencesPii) {
                log.info(
                        "embed-public REFUSED (PII on {}): kind={} path={} by={} cubes={}",
                        inspection.fellOpenForUnresolvable ? "unresolvable resource" : "annotated columns",
                        kind,
                        path,
                        username,
                        inspection.cubeIds);
                return badRequest(
                        "resourcePath",
                        "Resource references PII-annotated columns; public grants are forbidden. "
                                + "Mint a scoped token (POST /tokens) instead — its redaction policy "
                                + "is automatically elevated to FORCE_ON for PII resources.");
            }
        }

        // Cap public grants per user too — leaked anonymous read is the most
        // dangerous surface and we shouldn't let one account flood the
        // registry.
        long owned = publicRegistry.listByOwner(username).size();
        if (owned >= MAX_PUBLIC_GRANTS_PER_USER) {
            return Response.status(429)
                    .entity(Map.of("status", "LIMIT", "error", "Too many public grants; revoke some first"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        EmbedPublicGrant g = publicRegistry.grant(kind, path, username, roles, body.label);
        log.info("embed-public granted kind={} path={} by={}", kind, path, username);
        return Response.ok(Map.of(
                        "status", "OK",
                        "resourceKind", g.resourceKind,
                        "resourcePath", g.resourcePath,
                        "grantedAt", g.grantedAt))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @GET
    @Path("/public")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listPublic(@QueryParam("all") @DefaultValue("false") boolean all) {
        String username = currentUsername();
        List<EmbedPublicGrant> grants =
                (all && isAdmin(currentRoles())) ? publicRegistry.listAll() : publicRegistry.listByOwner(username);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmbedPublicGrant g : grants) {
            out.add(Map.of(
                    "resourceKind", g.resourceKind,
                    "resourcePath", g.resourcePath,
                    "grantedBy", g.grantedBy,
                    "grantedAt", g.grantedAt,
                    "label", g.label == null ? "" : g.label));
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/public")
    @Produces(MediaType.APPLICATION_JSON)
    public Response revokePublic(@QueryParam("kind") String kind, @QueryParam("path") String path) {
        Response kindError = validateKindAndPath(kind, path);
        if (kindError != null) return kindError;
        EmbedPublicGrant existing = publicRegistry.lookup(kind, path);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "No public grant for that resource"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        String username = currentUsername();
        List<String> roles = currentRoles();
        boolean canManage = (username != null && username.equals(existing.grantedBy))
                || isAdmin(roles)
                || hasGrant(path, username, roles);
        if (!canManage) {
            return forbidden("You can't revoke this public grant");
        }
        publicRegistry.revoke(kind, path);
        log.info("embed-public revoked kind={} path={} by={}", kind, path, username);
        return Response.ok(Map.of("status", "OK"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /* --------------------------- helpers ---------------------------- */

    /** Common kind+path validation for mint, grant, revoke. */
    private static Response validateKindAndPath(String kind, String path) {
        if (kind == null || kind.isBlank()) {
            return badRequest("resourceKind", "resourceKind required");
        }
        if (!"query".equals(kind) && !"dashboard".equals(kind) && !"ai".equals(kind)) {
            return badRequest("resourceKind", "resourceKind must be 'query', 'dashboard', or 'ai'");
        }
        if (path == null || path.isBlank()) {
            return badRequest("resourcePath", "resourcePath required");
        }
        // Defence-in-depth: a query token must end .saiku, a dashboard token
        // .saikudash. The view endpoint also checks this but the mint layer
        // is the right place to reject bad data before it ever lands in the
        // store.
        if ("query".equals(kind) && !path.endsWith(".saiku")) {
            return badRequest("resourcePath", "query resourcePath must end .saiku");
        }
        if ("dashboard".equals(kind) && !path.endsWith(".saikudash")) {
            return badRequest("resourcePath", "dashboard resourcePath must end .saikudash");
        }
        // For kind="ai" the resourcePath is a cube ID — connection/catalog/schema/cubeName.
        // Sanity-check the shape so the view endpoint's downstream parseCubeId can't be
        // handed something that would look like a file path (with slashes) but isn't a cube.
        if ("ai".equals(kind)) {
            String[] parts = path.split("/");
            if (parts.length != 4 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
                return badRequest("resourcePath", "ai resourcePath must be connection/catalog/schema/cubeName");
            }
            if (path.endsWith(".saiku") || path.endsWith(".saikudash")) {
                return badRequest("resourcePath", "ai resourcePath must not carry a file extension");
            }
        }
        return null;
    }

    private boolean hasGrant(String path, String username, List<String> roles) {
        // datasourceService.getResourceACL returns null unless the caller can
        // GRANT the resource — same gate the share-token mint uses (#940 +
        // #941). Treating the ACL surface uniformly here means a future
        // permission-system change ports automatically.
        if (datasourceService == null) return false;
        try {
            AclEntry acl = datasourceService.getResourceACL(path, username, roles);
            return acl != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isAdmin(List<String> roles) {
        if (roles == null || userService == null) return false;
        List<String> admin = userService.getAdminRoles();
        if (admin == null) return false;
        for (String r : roles) {
            if (admin.contains(r)) return true;
        }
        return false;
    }

    private String currentUsername() {
        if (sessionService == null) return null;
        Object u = sessionService.getAllSessionObjects().get("username");
        return u == null ? null : u.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> currentRoles() {
        if (sessionService == null) return List.of();
        Object r = sessionService.getAllSessionObjects().get("roles");
        if (r instanceof List<?>) return (List<String>) r;
        return List.of();
    }

    private static Response badRequest(String field, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "field", field, "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response forbidden(String message) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("status", "FORBIDDEN", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
