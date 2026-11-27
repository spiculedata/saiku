/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.saiku.service.features.FeatureCatalogue;
import org.saiku.service.features.FeatureToggleAuditEntry;
import org.saiku.service.features.FeatureToggleAuditLog;
import org.saiku.service.features.FeatureToggles;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku#2618 — admin surface for the per-role feature toggles.
 *
 * <p>{@code GET /saiku/admin/features} returns the catalogue (the fixed list of
 * toggleable features) with the stored grants; {@code PUT
 * /saiku/admin/features/{key}} rewrites one feature's grant. Every accepted
 * write lands in the {@link FeatureToggleAuditLog} with the acting admin and
 * the before/after grants, so "who turned what off for whom, when" is answerable
 * from {@code GET /saiku/admin/features/audit}.
 *
 * <p>Grant semantics (restriction-only, enforced by {@code FeatureToggles}):
 * absent feature or null grant = unrestricted; empty list = admins only; listed
 * roles = those roles plus admins. The admin panel itself is never feature-
 * gated — this resource is gated on {@code ROLE_ADMIN}, and ROLE_ADMIN always
 * passes every feature check.
 *
 * <p>Path is {@code /saiku/admin/features} — admin-gated ({@code @RolesAllowed}
 * + the {@code /rest/saiku/admin/**} intercept), same posture as the other
 * admin resources.
 */
@Path("/saiku/admin/features")
@RolesAllowed("ROLE_ADMIN")
public class FeatureAdminResource {

    private static final Logger log = LoggerFactory.getLogger(FeatureAdminResource.class);

    private FeatureToggles toggles;
    private FeatureToggleAuditLog auditLog;
    private UserService userService;

    public void setToggles(FeatureToggles toggles) {
        this.toggles = toggles;
    }

    public void setAuditLog(FeatureToggleAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** The catalogue with the current grants, plus the roles known to the user directory. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response overview() {
        FeatureOverviewDto out = new FeatureOverviewDto();
        out.features = new ArrayList<>();
        for (FeatureCatalogue feature : FeatureCatalogue.values()) {
            FeatureDto dto = new FeatureDto();
            dto.key = feature.key;
            dto.label = feature.label;
            dto.description = feature.description;
            dto.uiRoute = feature.uiRoute;
            dto.restPrefixes = feature.restPrefixes;
            dto.serverEnforced = feature.serverEnforced;
            dto.roles = toggles.grantOf(feature.key);
            out.features.add(dto);
        }
        out.knownRoles = new ArrayList<>(new TreeSet<>(knownRoles()));
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /** Rewrite one feature's grant. A null {@code roles} resets the feature to unrestricted. */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{key}")
    public Response update(@PathParam("key") String key, GrantBody body) {
        FeatureCatalogue feature = FeatureCatalogue.of(key);
        if (feature == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of(
                            "status", "ERROR",
                            "error", "UNKNOWN_FEATURE",
                            "message", "Unknown feature key '" + key + "'"))
                    .build();
        }
        List<String> grant = body == null ? null : normalise(body.roles);
        List<String> before = toggles.grantOf(key);
        try {
            Map<String, List<String>> current = new LinkedHashMap<>(toggles.allGrants());
            if (grant == null) {
                current.remove(key);
            } else {
                current.put(key, grant);
            }
            toggles.save(current);
        } catch (IOException e) {
            log.error("Feature toggle write failed for {} (actor {}): {}", key, actor(), e.toString());
            return Response.serverError()
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of(
                            "status", "ERROR",
                            "error", "WRITE_FAILED",
                            "message", "The feature toggles file could not be written; nothing changed."))
                    .build();
        }
        FeatureToggleAuditEntry entry = new FeatureToggleAuditEntry();
        entry.actor = actor();
        entry.feature = key;
        entry.action = grant == null ? FeatureToggleAuditEntry.ACTION_RESET : FeatureToggleAuditEntry.ACTION_UPDATE;
        entry.before = before;
        entry.after = grant;
        auditLog.record(entry);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "OK");
        ok.put("feature", key);
        ok.put("roles", grant);
        return Response.ok(ok).type(MediaType.APPLICATION_JSON).build();
    }

    /** The audit trail (most recent entries, oldest first). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/audit")
    public Response audit() {
        return Response.ok(auditLog.readRecent()).type(MediaType.APPLICATION_JSON).build();
    }

    /* ------------------------------- helpers ------------------------------ */

    private String actor() {
        // The principal name is what the audit trail means by "who": read it
        // from Spring's holder — the same authoritative source UserService uses.
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext() == null
                        ? null
                        : org.springframework.security.core.context.SecurityContextHolder
                                .getContext()
                                .getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                && auth.getName() != null) {
            return auth.getName();
        }
        return "unknown";
    }

    /** Trim, drop blanks, de-duplicate; an all-blank list reads as admins-only. */
    private static List<String> normalise(String[] roles) {
        if (roles == null) {
            return null;
        }
        TreeSet<String> clean = new TreeSet<>();
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                clean.add(role.trim());
            }
        }
        return new ArrayList<>(clean);
    }

    /** Every role the directory can grant, for the admin panel's role picker. */
    private List<String> knownRoles() {
        List<String> out = new ArrayList<>();
        try {
            if (userService != null) {
                for (org.saiku.database.dto.SaikuUser user : userService.getUsers()) {
                    if (user != null && user.getRoles() != null) {
                        out.addAll(Arrays.asList(user.getRoles()));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not enumerate directory roles for the feature overview: {}", e.toString());
        }
        // The SQL workbench's own gate role is meaningful even if nobody holds it yet.
        out.add("ROLE_SQL_EXEC");
        return out;
    }

    /* -------------------------------- DTOs -------------------------------- */

    /** PUT body: the new grant. Null/absent {@code roles} resets to unrestricted. */
    public static final class GrantBody {
        public String[] roles;
    }

    /** Catalogue + current grants. Public fields for Jackson. */
    public static final class FeatureOverviewDto {
        public List<FeatureDto> features;
        public List<String> knownRoles;
    }

    public static final class FeatureDto {
        public String key;
        public String label;
        public String description;
        public String uiRoute;
        public List<String> restPrefixes;
        public boolean serverEnforced;
        /** Null = unrestricted; empty = admins only; listed = those roles + admins. */
        public List<String> roles;
    }
}
