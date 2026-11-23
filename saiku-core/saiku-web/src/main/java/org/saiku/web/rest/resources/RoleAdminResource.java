/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.olap4j.OlapConnection;
import org.saiku.database.dto.SaikuUser;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.cache.CubeMetadataVersions;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.security.MondrianRolePolicy;
import org.saiku.service.util.security.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku#779 — admin surface for Mondrian role-based security.
 *
 * <p>Answers "which role grants what, on which datasource, to whom", previews what a user (or an
 * arbitrary set of roles) would get on every datasource, and edits the Spring-role → Mondrian-role
 * grants of {@code lookup}-mode datasources. The roles themselves (and their cube / hierarchy /
 * member grants) are the {@code <Role>} elements of the Mondrian schema; this resource maps callers
 * onto them.
 *
 * <p>Every preview goes through {@link MondrianRolePolicy}, the same code {@code
 * SecurityAwareConnectionManager.applySecurity} enforces with, including the saiku#1968 fail-closed
 * rule — so "test as this role" can't disagree with what the user will actually get.
 *
 * <p>Path is {@code /saiku/admin/roles} — admin-gated (@RolesAllowed + the {@code
 * /rest/saiku/admin/**} intercept), same posture as the other admin resources.
 */
@Path("/saiku/admin/roles")
@RolesAllowed("ROLE_ADMIN")
public class RoleAdminResource {

    private static final Logger log = LoggerFactory.getLogger(RoleAdminResource.class);

    private UserService userService;
    private DatasourceService datasourceService;

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    public void setDatasourceService(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    /** Role inventory: every known Spring role, who holds it, and what it grants per datasource. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RoleOverviewDto overview() {
        List<String> adminRoles = adminRoles();
        List<SaikuUser> users = users();
        List<DatasourceSecurityDto> datasources = new ArrayList<>();
        Map<String, List<String>> availableByDs = new LinkedHashMap<>();
        Map<String, SaikuDatasource> dsByName = new LinkedHashMap<>();
        for (SaikuDatasource ds : datasources()) {
            DatasourceSecurityDto dto = describe(ds);
            datasources.add(dto);
            availableByDs.put(ds.getName(), dto.mondrianRoles);
            dsByName.put(ds.getName(), ds);
        }

        TreeSet<String> universe = new TreeSet<>(adminRoles);
        for (SaikuUser u : users) {
            if (u.getRoles() != null) {
                universe.addAll(Arrays.asList(u.getRoles()));
            }
        }
        for (DatasourceSecurityDto d : datasources) {
            if (MondrianRolePolicy.Mode.LOOKUP.name().equals(d.mode)) {
                universe.addAll(d.mapping.keySet());
            } else if (MondrianRolePolicy.Mode.ONE2ONE.name().equals(d.mode) && d.mondrianRoles != null) {
                // one2one: an authority named like a schema role is granted that role.
                universe.addAll(d.mondrianRoles);
            }
        }
        universe.removeIf(r -> r == null || r.isBlank());

        RoleOverviewDto out = new RoleOverviewDto();
        out.adminRoles = adminRoles;
        out.datasources = datasources;
        out.roles = new ArrayList<>();
        for (String role : universe) {
            RoleDto r = new RoleDto();
            r.name = role;
            r.admin = adminRoles.contains(role);
            r.users = new ArrayList<>();
            for (SaikuUser u : users) {
                if (u.getRoles() != null && Arrays.asList(u.getRoles()).contains(role)) {
                    r.users.add(u.getUsername());
                }
            }
            r.grants = new ArrayList<>();
            for (Map.Entry<String, SaikuDatasource> e : dsByName.entrySet()) {
                MondrianRolePolicy.Mode mode = MondrianRolePolicy.modeOf(e.getValue());
                List<String> granted = MondrianRolePolicy.resolveMondrianRoles(
                        mode, List.of(role), availableByDs.get(e.getKey()), MondrianRolePolicy.mappingOf(e.getValue()));
                if (!granted.isEmpty()) {
                    GrantDto g = new GrantDto();
                    g.datasource = e.getKey();
                    g.mondrianRoles = granted;
                    r.grants.add(g);
                }
            }
            out.roles.add(r);
        }
        return out;
    }

    /**
     * "Test as" preview: what a user, or an explicit set of Spring roles, would get on every
     * datasource. Body is {@code {"username": "..."}} or {@code {"roles": [...]}}.
     */
    @POST
    @Path("/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response preview(PreviewRequest body) {
        boolean byUser = body != null && !blank(body.username);
        boolean byRoles = body != null && body.roles != null;
        if (byUser == byRoles) {
            return bad("give exactly one of username or roles");
        }
        List<String> roles;
        String username = null;
        if (byUser) {
            SaikuUser user = findUser(body.username);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "no such user: " + body.username))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            username = user.getUsername();
            roles = user.getRoles() == null ? List.of() : Arrays.asList(user.getRoles());
        } else {
            roles = new ArrayList<>();
            for (String r : body.roles) {
                if (!blank(r)) {
                    roles.add(r.trim());
                }
            }
        }

        List<String> adminRoles = adminRoles();
        boolean admin = roles.stream().anyMatch(adminRoles::contains);

        PreviewDto out = new PreviewDto();
        out.username = username;
        out.roles = roles;
        out.admin = admin;
        out.datasources = new ArrayList<>();
        for (SaikuDatasource ds : datasources()) {
            MondrianRolePolicy.Mode mode = MondrianRolePolicy.modeOf(ds);
            List<String> available = null;
            if (mode == MondrianRolePolicy.Mode.ONE2ONE || mode == MondrianRolePolicy.Mode.LOOKUP) {
                available = availableMondrianRoles(ds);
            }
            MondrianRolePolicy.Resolution res = MondrianRolePolicy.preview(ds, roles, available, admin);
            DatasourceAccessDto d = new DatasourceAccessDto();
            d.datasource = ds.getName();
            d.mode = mode.name();
            d.access = res.getAccess().name();
            d.mondrianRoles = res.getMondrianRoles();
            out.datasources.add(d);
        }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Replace the Mondrian roles a Spring role is granted on a {@code lookup}-mode datasource. Body
     * is {@code {"mondrianRoles": [...]}}; an empty list revokes the Spring role's grants.
     */
    @PUT
    @Path("/{role}/grants/{datasource}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setGrants(
            @PathParam("role") String role, @PathParam("datasource") String datasource, GrantRequest body) {
        List<String> requested = body == null || body.mondrianRoles == null ? List.of() : body.mondrianRoles;
        return writeGrants(role, datasource, requested);
    }

    /** Revoke every Mondrian role a Spring role is granted on a {@code lookup}-mode datasource. */
    @DELETE
    @Path("/{role}/grants/{datasource}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response revokeGrants(@PathParam("role") String role, @PathParam("datasource") String datasource) {
        return writeGrants(role, datasource, List.of());
    }

    private Response writeGrants(String role, String datasourceName, List<String> mondrianRoles) {
        if (!MondrianRolePolicy.isValidRoleName(role)) {
            return bad("invalid role name: it must be non-empty and contain no ';', '=' or ','");
        }
        for (String m : mondrianRoles) {
            if (!MondrianRolePolicy.isValidRoleName(m)) {
                return bad("invalid Mondrian role name: " + m);
            }
        }
        SaikuDatasource ds = datasourceService.getDatasourceByIdOrName(datasourceName);
        if (ds == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "no such datasource: " + datasourceName))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        MondrianRolePolicy.Mode mode = MondrianRolePolicy.modeOf(ds);
        if (mode != MondrianRolePolicy.Mode.LOOKUP) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                            "status",
                            "NOT_LOOKUP_MODE",
                            "error",
                            "datasource \"" + ds.getName() + "\" uses security mode " + mode
                                    + "; role grants can only be edited when security.enabled=true and "
                                    + "security.type=lookup"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        if (!mondrianRoles.isEmpty()) {
            // Grants are only saved once checked against the schema. A role the schema doesn't
            // declare can't be applied at connection time, so saving one would lock its users out.
            // Revoking (an empty list) needs no check: removing access is always safe.
            List<String> available = availableMondrianRoles(ds);
            if (available == null) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of(
                                "status",
                                "ROLES_UNVERIFIABLE",
                                "error",
                                "could not read the Mondrian roles declared by datasource \"" + ds.getName()
                                        + "\"'s schema, so the grant can't be checked; fix the connection "
                                        + "and try again"))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            List<String> unknown = new ArrayList<>();
            for (String m : mondrianRoles) {
                if (!available.contains(m)) {
                    unknown.add(m);
                }
            }
            if (!unknown.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "status",
                                "VALIDATION_ERROR",
                                "error",
                                "the schema declares no Mondrian role named " + unknown,
                                "available",
                                available))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
        }

        Map<String, List<String>> updated =
                MondrianRolePolicy.withGrants(MondrianRolePolicy.mappingOf(ds), role, mondrianRoles);
        SaikuDatasource copy = ds.clone();
        String formatted = MondrianRolePolicy.formatMapping(updated);
        if (formatted.isEmpty()) {
            copy.getProperties().remove(ISaikuConnection.SECURITY_LOOKUP_KEY);
        } else {
            copy.getProperties().setProperty(ISaikuConnection.SECURITY_LOOKUP_KEY, formatted);
        }
        try {
            datasourceService.addDatasource(copy, true, userService.getCurrentUserRoles());
        } catch (Exception e) {
            log.error("saiku#779: could not save role grants on datasource {}", ds.getName(), e);
            return Response.serverError()
                    .entity(Map.of("error", "could not save role grants; see the server log"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        // The cellset cache keys on the caller's Spring roles, not the Mondrian role they resolve
        // to, so a grant change must invalidate this datasource's cached results.
        CubeMetadataVersions.bump(ds.getName());
        log.info(
                "saiku#779: admin \"{}\" set role grants on datasource \"{}\": {} -> {}",
                userService.getActiveUsername(),
                ds.getName(),
                role,
                mondrianRoles);
        return Response.ok(describe(copy)).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * The Mondrian roles the datasource's schema declares, or {@code null} if they can't be read
     * (non-OLAP datasource, pass-through credentials, or a connection failure). Protected so tests
     * can stub the live connection.
     */
    protected List<String> availableMondrianRoles(SaikuDatasource ds) {
        if (ds.getType() != SaikuDatasource.Type.OLAP
                || MondrianRolePolicy.modeOf(ds) == MondrianRolePolicy.Mode.PASSTHROUGH) {
            return null;
        }
        try {
            OlapConnection con = datasourceService.getConnectionManager().getOlapConnection(ds.getName());
            if (con == null) {
                return null;
            }
            List<String> roles = con.getAvailableRoleNames();
            return roles == null ? List.of() : new ArrayList<>(new TreeSet<>(roles));
        } catch (Exception e) {
            log.warn("saiku#779: could not read Mondrian roles of datasource {}", ds.getName(), e);
            return null;
        }
    }

    private DatasourceSecurityDto describe(SaikuDatasource ds) {
        DatasourceSecurityDto d = new DatasourceSecurityDto();
        d.name = ds.getName();
        d.id = ds.getProperties() == null ? null : ds.getProperties().getProperty("id");
        d.type = ds.getType() == null ? null : ds.getType().name();
        MondrianRolePolicy.Mode mode = MondrianRolePolicy.modeOf(ds);
        d.mode = mode.name();
        d.securityEnabled = mode != MondrianRolePolicy.Mode.DISABLED;
        d.mondrianRoles = availableMondrianRoles(ds);
        d.mapping = new TreeMap<>(MondrianRolePolicy.mappingOf(ds));
        return d;
    }

    private List<String> adminRoles() {
        List<String> roles = userService.getAdminRoles();
        return roles == null ? List.of() : roles;
    }

    private List<SaikuUser> users() {
        try {
            List<SaikuUser> users = userService.getUsers();
            return users == null ? List.of() : users;
        } catch (Exception e) {
            log.warn("saiku#779: could not list users", e);
            return List.of();
        }
    }

    private SaikuUser findUser(String username) {
        for (SaikuUser u : users()) {
            if (u != null && Usernames.sameUser(username, u.getUsername())) {
                return u;
            }
        }
        return null;
    }

    private Collection<SaikuDatasource> datasources() {
        Map<String, SaikuDatasource> all = datasourceService.getDatasources(userService.getCurrentUserRoles());
        return all == null ? List.of() : new TreeMap<>(all).values();
    }

    private static Response bad(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Public fields for Jackson. */
    public static final class RoleOverviewDto {
        public List<String> adminRoles;
        public List<RoleDto> roles;
        public List<DatasourceSecurityDto> datasources;
    }

    public static final class RoleDto {
        public String name;
        public boolean admin;
        /** Users in the Saiku user store holding this role. */
        public List<String> users;
        /** Mondrian roles this role alone resolves to, per datasource. */
        public List<GrantDto> grants;
    }

    public static final class GrantDto {
        public String datasource;
        public List<String> mondrianRoles;
    }

    public static final class DatasourceSecurityDto {
        public String name;
        public String id;
        public String type;
        public boolean securityEnabled;
        /** A {@link MondrianRolePolicy.Mode} name. */
        public String mode;
        /** Roles the schema declares; {@code null} when they couldn't be read. */
        public List<String> mondrianRoles;
        /** Parsed {@code security.mapping} (Spring role → Mondrian roles). */
        public Map<String, List<String>> mapping;
    }

    public static final class PreviewRequest {
        public String username;
        public List<String> roles;
    }

    public static final class PreviewDto {
        public String username;
        public List<String> roles;
        public boolean admin;
        public List<DatasourceAccessDto> datasources;
    }

    public static final class DatasourceAccessDto {
        public String datasource;
        public String mode;
        /** A {@link MondrianRolePolicy.Access} name. */
        public String access;

        public List<String> mondrianRoles;
    }

    public static final class GrantRequest {
        public List<String> mondrianRoles;
    }
}
