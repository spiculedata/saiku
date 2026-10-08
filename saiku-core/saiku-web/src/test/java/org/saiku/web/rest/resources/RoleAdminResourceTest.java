/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.cache.CubeMetadataVersions;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;

/**
 * saiku#779 — the admin role API: inventory, "test as" preview, and lookup-mode grant editing.
 * Driven against hand stubs of {@link DatasourceService} / {@link UserService} (no Mockito), with
 * the live Mondrian-role read stubbed via {@link RoleAdminResource#availableMondrianRoles}.
 */
public class RoleAdminResourceTest {

    private static final List<String> SCHEMA_ROLES = List.of("California manager", "No HR Cube", "Sales");

    private final Map<String, SaikuDatasource> store = new LinkedHashMap<>();
    private final List<SaikuDatasource> saved = new ArrayList<>();
    private final List<SaikuUser> users = new ArrayList<>();
    private RoleAdminResource resource;
    private volatile RuntimeException saveFailure;

    @Before
    public void setUp() {
        store.put("lookupds", ds("lookupds", "lookup", "ROLE_SALES=Sales;ROLE_CA=California manager"));
        store.put("one2oneds", ds("one2oneds", "one2one", null));
        store.put("opends", ds("opends", null, null));
        users.add(user("alice", "ROLE_USER", "ROLE_SALES"));
        users.add(user("bob", "ROLE_USER"));
        users.add(user("root", "ROLE_ADMIN"));

        UserService us = new UserService() {
            @Override
            public List<SaikuUser> getUsers() {
                return users;
            }

            @Override
            public String[] getCurrentUserRoles() {
                return new String[] {"ROLE_ADMIN"};
            }

            @Override
            public String getActiveUsername() {
                return "root";
            }
        };
        us.setAdminRoles(List.of("ROLE_ADMIN"));

        DatasourceService dss = new DatasourceService() {
            @Override
            public Map<String, SaikuDatasource> getDatasources(String[] roles) {
                return new HashMap<>(store);
            }

            @Override
            public SaikuDatasource getDatasourceByIdOrName(String idOrName) {
                return store.get(idOrName);
            }

            @Override
            public void addDatasource(SaikuDatasource datasource, boolean overwrite, String[] roles) {
                assertTrue("grant writes must overwrite the existing datasource", overwrite);
                if (saveFailure != null) {
                    throw saveFailure;
                }
                saved.add(datasource);
                store.put(datasource.getName(), datasource);
            }
        };

        resource = new RoleAdminResource() {
            @Override
            protected List<String> availableMondrianRoles(SaikuDatasource ds) {
                // opends: no schema read; brokends: a lookup datasource whose connection fails.
                return ds.getName().equals("opends") || ds.getName().equals("brokends") ? null : SCHEMA_ROLES;
            }
        };
        resource.setUserService(us);
        resource.setDatasourceService(dss);
    }

    @Test
    public void resourceIsAdminGated() {
        RolesAllowed ra = RoleAdminResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(ra);
        assertEquals(List.of("ROLE_ADMIN"), List.of(ra.value()));
    }

    @Test
    public void overview_listsRolesHoldersAndPerDatasourceGrants() {
        RoleAdminResource.RoleOverviewDto o = resource.overview();

        assertEquals(List.of("ROLE_ADMIN"), o.adminRoles);
        assertEquals(3, o.datasources.size());

        RoleAdminResource.RoleDto sales = role(o, "ROLE_SALES");
        assertEquals(List.of("alice"), sales.users);
        assertFalse(sales.admin);
        assertEquals(1, sales.grants.size());
        assertEquals("lookupds", sales.grants.get(0).datasource);
        assertEquals(List.of("Sales"), sales.grants.get(0).mondrianRoles);

        // A role mapped on a datasource but held by nobody in the user store still shows up.
        assertTrue(role(o, "ROLE_CA").users.isEmpty());
        // one2one: a schema role name is itself a grantable Spring authority.
        RoleAdminResource.RoleDto one2one = role(o, "Sales");
        assertEquals("one2oneds", one2one.grants.get(0).datasource);

        assertTrue(role(o, "ROLE_ADMIN").admin);
    }

    @Test
    public void overview_describesDatasourceSecurity() {
        RoleAdminResource.DatasourceSecurityDto lookup = dsDto(resource.overview(), "lookupds");
        assertEquals("LOOKUP", lookup.mode);
        assertTrue(lookup.securityEnabled);
        assertEquals(SCHEMA_ROLES, lookup.mondrianRoles);
        assertEquals(List.of("Sales"), lookup.mapping.get("ROLE_SALES"));

        RoleAdminResource.DatasourceSecurityDto open = dsDto(resource.overview(), "opends");
        assertEquals("DISABLED", open.mode);
        assertFalse(open.securityEnabled);
        assertNull(open.mondrianRoles);
    }

    @Test
    public void preview_byUsername_resolvesEveryDatasource() {
        RoleAdminResource.PreviewRequest req = new RoleAdminResource.PreviewRequest();
        req.username = "ALICE"; // case-insensitive identity (Usernames.sameUser)
        Response r = resource.preview(req);
        assertEquals(200, r.getStatus());
        RoleAdminResource.PreviewDto p = (RoleAdminResource.PreviewDto) r.getEntity();
        assertEquals("alice", p.username);
        assertFalse(p.admin);
        assertEquals("SCOPED", access(p, "lookupds").access);
        assertEquals(List.of("Sales"), access(p, "lookupds").mondrianRoles);
        // alice holds no authority named like a schema role -> saiku#1968 fail-closed.
        assertEquals("DENIED", access(p, "one2oneds").access);
        assertEquals("UNSECURED", access(p, "opends").access);
    }

    @Test
    public void preview_byRoles_adminWithNoRoleGetsRoot() {
        RoleAdminResource.PreviewRequest req = new RoleAdminResource.PreviewRequest();
        req.roles = List.of("ROLE_ADMIN");
        RoleAdminResource.PreviewDto p =
                (RoleAdminResource.PreviewDto) resource.preview(req).getEntity();
        assertTrue(p.admin);
        assertEquals("FULL_ADMIN", access(p, "lookupds").access);
        assertEquals("FULL_ADMIN", access(p, "one2oneds").access);
    }

    @Test
    public void preview_byRoles_one2oneMatchesSchemaRoleNames() {
        RoleAdminResource.PreviewRequest req = new RoleAdminResource.PreviewRequest();
        req.roles = List.of(" No HR Cube ", "");
        RoleAdminResource.PreviewDto p =
                (RoleAdminResource.PreviewDto) resource.preview(req).getEntity();
        assertEquals(List.of("No HR Cube"), p.roles);
        assertEquals("SCOPED", access(p, "one2oneds").access);
        assertEquals(List.of("No HR Cube"), access(p, "one2oneds").mondrianRoles);
    }

    @Test
    public void preview_requiresExactlyOneOfUsernameOrRoles() {
        assertEquals(400, resource.preview(null).getStatus());
        assertEquals(
                400, resource.preview(new RoleAdminResource.PreviewRequest()).getStatus());
        RoleAdminResource.PreviewRequest both = new RoleAdminResource.PreviewRequest();
        both.username = "alice";
        both.roles = List.of("ROLE_USER");
        assertEquals(400, resource.preview(both).getStatus());
    }

    @Test
    public void preview_unknownUser_is404() {
        RoleAdminResource.PreviewRequest req = new RoleAdminResource.PreviewRequest();
        req.username = "mallory";
        assertEquals(404, resource.preview(req).getStatus());
    }

    @Test
    public void setGrants_rewritesMappingAndBumpsCacheEpoch() {
        long before = CubeMetadataVersions.epoch("lookupds");
        Response r = resource.setGrants("ROLE_SALES", "lookupds", grants("Sales", "No HR Cube"));
        assertEquals(200, r.getStatus());

        assertEquals(1, saved.size());
        assertEquals(
                "ROLE_SALES=Sales;ROLE_SALES=No HR Cube;ROLE_CA=California manager",
                saved.get(0).getProperties().getProperty("security.mapping"));
        RoleAdminResource.DatasourceSecurityDto dto = (RoleAdminResource.DatasourceSecurityDto) r.getEntity();
        assertEquals(List.of("Sales", "No HR Cube"), dto.mapping.get("ROLE_SALES"));
        // The cellset cache keys on Spring roles, so a grant change must change the cube version.
        assertTrue(CubeMetadataVersions.epoch("lookupds") > before);
    }

    @Test
    public void setGrants_doesNotMutateTheLiveDatasourceBeforeSaving() {
        SaikuDatasource live = store.get("lookupds");
        resource.setGrants("ROLE_NEW", "lookupds", grants("Sales"));
        assertEquals(
                "ROLE_SALES=Sales;ROLE_CA=California manager",
                live.getProperties().getProperty("security.mapping"));
    }

    @Test
    public void revokeGrants_removesTheSpringRole() {
        assertEquals(200, resource.revokeGrants("ROLE_CA", "lookupds").getStatus());
        assertEquals("ROLE_SALES=Sales", saved.get(0).getProperties().getProperty("security.mapping"));

        assertEquals(200, resource.revokeGrants("ROLE_SALES", "lookupds").getStatus());
        assertFalse(saved.get(1).getProperties().containsKey("security.mapping"));
    }

    @Test
    public void setGrants_rejectsUnknownMondrianRoleWithCandidates() {
        Response r = resource.setGrants("ROLE_SALES", "lookupds", grants("Sails"));
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals(SCHEMA_ROLES, body.get("available"));
        assertTrue(saved.isEmpty());
    }

    @Test
    public void setGrants_rejectsDelimiterInjection() {
        // "X=Sales" would forge an extra mapping entry in the ';'/'=' wire format.
        assertEquals(
                400, resource.setGrants("ROLE_A;X", "lookupds", grants("Sales")).getStatus());
        assertEquals(
                400,
                resource.setGrants("ROLE_A", "lookupds", grants("Sales;X=Sales"))
                        .getStatus());
        assertTrue(saved.isEmpty());
    }

    @Test
    public void setGrants_onlyForLookupDatasources() {
        assertEquals(
                409,
                resource.setGrants("ROLE_SALES", "one2oneds", grants("Sales")).getStatus());
        assertEquals(
                409, resource.setGrants("ROLE_SALES", "opends", grants("Sales")).getStatus());
        assertEquals(
                404, resource.setGrants("ROLE_SALES", "nope", grants("Sales")).getStatus());
        assertTrue(saved.isEmpty());
    }

    /**
     * If the schema's roles can't be read, a grant can't be checked against them. Saving it anyway
     * let a typo through, and enforcement then couldn't apply the role (saiku#779 review).
     */
    @Test
    public void setGrants_refusesWhenSchemaRolesCannotBeRead() {
        store.put("brokends", ds("brokends", "lookup", "ROLE_SALES=Sales"));

        Response r = resource.setGrants("ROLE_SALES", "brokends", grants("Sails"));

        assertEquals(409, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("ROLES_UNVERIFIABLE", body.get("status"));
        assertTrue(saved.isEmpty());
    }

    /** Revoking removes access, so it's always safe, even when the schema can't be read. */
    @Test
    public void revokeGrants_allowedWhenSchemaRolesCannotBeRead() {
        store.put("brokends", ds("brokends", "lookup", "ROLE_SALES=Sales"));

        assertEquals(200, resource.revokeGrants("ROLE_SALES", "brokends").getStatus());
        assertFalse(saved.get(0).getProperties().containsKey("security.mapping"));
    }

    /** A failed save is logged server-side; the response carries no exception detail (saiku#1282). */
    @Test
    public void setGrants_saveFailure_doesNotLeakExceptionDetail() {
        saveFailure = new IllegalStateException("/srv/saiku-home/repository/secret.sds is locked");

        Response r = resource.setGrants("ROLE_SALES", "lookupds", grants("Sales"));

        assertEquals(500, r.getStatus());
        assertFalse(String.valueOf(r.getEntity()).contains("secret.sds"));
    }

    /** Preview must agree with enforcement: a grant to an undeclared role is denied, not scoped. */
    @Test
    public void preview_lookupGrantToUndeclaredRole_isDenied() {
        store.put("lookupds", ds("lookupds", "lookup", "ROLE_SALES=Ghost"));

        RoleAdminResource.PreviewRequest req = new RoleAdminResource.PreviewRequest();
        req.roles = List.of("ROLE_SALES");
        RoleAdminResource.PreviewDto out =
                (RoleAdminResource.PreviewDto) resource.preview(req).getEntity();

        RoleAdminResource.DatasourceAccessDto lookup = out.datasources.stream()
                .filter(d -> d.datasource.equals("lookupds"))
                .findFirst()
                .orElseThrow();
        assertEquals("DENIED", lookup.access);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static SaikuDatasource ds(String name, String type, String mapping) {
        Properties p = new Properties();
        p.setProperty("id", name + "-id");
        if (type != null) {
            p.setProperty("security.enabled", "true");
            p.setProperty("security.type", type);
        }
        if (mapping != null) {
            p.setProperty("security.mapping", mapping);
        }
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, p);
    }

    private static SaikuUser user(String name, String... roles) {
        SaikuUser u = new SaikuUser();
        u.setUsername(name);
        u.setRoles(roles);
        return u;
    }

    private static RoleAdminResource.GrantRequest grants(String... roles) {
        RoleAdminResource.GrantRequest g = new RoleAdminResource.GrantRequest();
        g.mondrianRoles = List.of(roles);
        return g;
    }

    private static RoleAdminResource.RoleDto role(RoleAdminResource.RoleOverviewDto o, String name) {
        return o.roles.stream()
                .filter(r -> r.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no role " + name));
    }

    private static RoleAdminResource.DatasourceSecurityDto dsDto(RoleAdminResource.RoleOverviewDto o, String name) {
        return o.datasources.stream()
                .filter(d -> d.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no datasource " + name));
    }

    private static RoleAdminResource.DatasourceAccessDto access(RoleAdminResource.PreviewDto p, String ds) {
        return p.datasources.stream()
                .filter(d -> d.datasource.equals(ds))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no datasource " + ds));
    }
}
