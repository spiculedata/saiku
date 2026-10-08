/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.scim;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.user.UserService;
import org.saiku.web.scim.ScimToken;
import org.saiku.web.scim.ScimTokenStore;

/**
 * Admin surface for SCIM connector credentials (issue #1438) at
 * {@code /rest/saiku/admin/scim/tokens} — the "POST /admin/scim/tokens" of the issue, on the
 * existing {@code /saiku/admin/**} path so it inherits the URL-level
 * {@code hasRole('ADMIN')} gate and carries a redundant {@code @RolesAllowed} (saiku#1165
 * defence-in-depth).
 *
 * <p>The minted secret is returned exactly once, in the mint response. Only its SHA-256 handle is
 * persisted, so a lost secret means minting a replacement — the same posture as any API key, and
 * the only one that keeps a leaked {@code saiku-home} directory from yielding live credentials.
 */
@Path("/saiku/admin/scim/tokens")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN")
public class ScimTokenResource {

    private ScimTokenStore tokenStore;
    private UserService userService;

    public void setTokenStore(ScimTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** Mint a credential. Body is optional: {@code {"label":"Okta prod","idp":"Okta"}}. */
    @POST
    public Response mint(MintRequest request) {
        String label = request == null || isBlank(request.label) ? "scim connector" : request.label.trim();
        String idp = request == null || isBlank(request.idp) ? "unspecified" : request.idp.trim();
        String createdBy = activeUsername();
        ScimTokenStore.MintedToken minted = tokenStore.mint(label, idp, createdBy);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", minted.token.id);
        body.put("label", minted.token.label);
        body.put("idp", minted.token.idp);
        body.put("createdAt", minted.token.createdAt);
        body.put("token", minted.secret);
        body.put(
                "note",
                "Store this token now — it is not retrievable later. Configure it as the SCIM connector's bearer.");
        return Response.status(Response.Status.CREATED).entity(body).build();
    }

    @GET
    public Response list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScimToken t : tokenStore.listAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id);
            row.put("label", t.label);
            row.put("idp", t.idp);
            row.put("createdBy", t.createdBy);
            row.put("createdAt", t.createdAt);
            row.put("lastUsedAt", t.lastUsedAt == 0 ? null : t.lastUsedAt);
            row.put("revoked", t.revoked);
            out.add(row);
        }
        return Response.ok(out).build();
    }

    @DELETE
    @Path("{id}")
    public Response revoke(@PathParam("id") String id) {
        if (!tokenStore.revoke(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    private String activeUsername() {
        if (userService != null) {
            String username = userService.getActiveUsername();
            if (!isBlank(username)) {
                return username;
            }
        }
        return "unknown";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Mint request body — both fields optional, both operator-supplied labels. */
    public static class MintRequest {
        public String label;
        public String idp;
    }
}
