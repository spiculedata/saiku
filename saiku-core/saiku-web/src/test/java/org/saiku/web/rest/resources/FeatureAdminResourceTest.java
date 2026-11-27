/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.features.FeatureCatalogue;
import org.saiku.service.features.FeatureToggleAuditEntry;
import org.saiku.service.features.FeatureToggles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2618 — unit cover for {@link FeatureAdminResource}: the catalogue read,
 * the per-feature grant rewrite (with audit of actor + before/after), the
 * unknown-key rejection, the reset-to-unrestricted, and the admin gate posture.
 *
 * <p>No mocking framework (saiku-web has none); userService is left unset —
 * {@code knownRoles()} then yields only the always-meaningful ROLE_SQL_EXEC.
 */
public class FeatureAdminResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path togglesFile;
    private Path auditFile;
    private FeatureAdminResource resource;

    @Before
    public void setUp() throws Exception {
        togglesFile = tmp.getRoot().toPath().resolve("feature-toggles.json");
        auditFile = tmp.getRoot().toPath().resolve("audit").resolve("feature-toggle-audit.jsonl");
        Files.createDirectories(auditFile.getParent());
        resource = new FeatureAdminResource();
        resource.setToggles(new FeatureToggles(togglesFile));
        resource.setAuditLog(new org.saiku.service.features.FeatureToggleAuditLog(auditFile, true));
    }

    @After
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String name, String... authorities) {
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(name, "p", granted));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void overviewListsEveryCatalogueFeatureUnrestrictedByDefault() {
        Response res = resource.overview();
        assertEquals(200, res.getStatus());
        FeatureAdminResource.FeatureOverviewDto dto = (FeatureAdminResource.FeatureOverviewDto) res.getEntity();
        Map<String, FeatureAdminResource.FeatureDto> byKey = new java.util.LinkedHashMap<>();
        for (FeatureAdminResource.FeatureDto f : dto.features) {
            byKey.put(f.key, f);
        }
        for (FeatureCatalogue feature : FeatureCatalogue.values()) {
            FeatureAdminResource.FeatureDto f = byKey.get(feature.key);
            assertNotNull("the catalogue is fully listed", f);
            assertNull("no config yet -> unrestricted", f.roles);
            assertEquals(feature.serverEnforced, f.serverEnforced);
        }
        assertTrue("the role picker always offers ROLE_SQL_EXEC", dto.knownRoles.contains("ROLE_SQL_EXEC"));
    }

    @Test
    public void unknownKeyIsRejected() {
        authenticateAs("admin", "ROLE_ADMIN");
        FeatureAdminResource.GrantBody body = new FeatureAdminResource.GrantBody();
        body.roles = new String[] {"ROLE_USER"};
        Response res = resource.update("not-a-feature", body);
        assertEquals(400, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) res.getEntity();
        assertEquals("UNKNOWN_FEATURE", entity.get("error"));
    }

    @Test
    public void updateWritesTheGrantAndAuditsBeforeAndAfter() {
        authenticateAs("admin", "ROLE_ADMIN");
        FeatureAdminResource.GrantBody body = new FeatureAdminResource.GrantBody();
        body.roles = new String[] {"ROLE_NOTEBOOKS", "ROLE_USER"};
        Response res = resource.update("notebooks", body);
        assertEquals(200, res.getStatus());

        // The grant took effect.
        FeatureToggles toggles = new FeatureToggles(togglesFile);
        assertEquals(List.of("ROLE_NOTEBOOKS", "ROLE_USER"), toggles.grantOf("notebooks"));

        // The audit entry names the actor and the (null) previous grant.
        List<FeatureToggleAuditEntry> audit = resource.audit();
        assertEquals(1, audit.size());
        assertEquals("admin", audit.get(0).actor);
        assertEquals("notebooks", audit.get(0).feature);
        assertNull("the first write had no previous grant", audit.get(0).before);
        assertEquals(List.of("ROLE_NOTEBOOKS", "ROLE_USER"), audit.get(0).after);

        // A second rewrite sees the first grant as its "before".
        FeatureAdminResource.GrantBody second = new FeatureAdminResource.GrantBody();
        second.roles = new String[] {};
        resource.update("notebooks", second);
        List<FeatureToggleAuditEntry> audit2 = resource.audit();
        assertEquals(2, audit2.size());
        assertEquals(List.of("ROLE_NOTEBOOKS", "ROLE_USER"), audit2.get(1).before);
        assertEquals("an empty rewrite is an update to admins-only", List.of(), audit2.get(1).after);
        assertEquals(FeatureToggleAuditEntry.ACTION_UPDATE, audit2.get(1).action);
        assertTrue(Files.exists(togglesFile));
    }

    @Test
    public void resetRemovesTheGrantAndAuditsAReset() throws IOException {
        authenticateAs("admin", "ROLE_ADMIN");
        FeatureAdminResource.GrantBody grant = new FeatureAdminResource.GrantBody();
        grant.roles = new String[] {"ROLE_NOTEBOOKS"};
        resource.update("notebooks", grant);

        Response res = resource.update("notebooks", new FeatureAdminResource.GrantBody());
        assertEquals(200, res.getStatus());

        FeatureToggles fresh = new FeatureToggles(togglesFile);
        assertNull("the reset restores unrestricted", fresh.grantOf("notebooks"));
        List<FeatureToggleAuditEntry> audit = resource.audit();
        assertEquals(2, audit.size());
        assertEquals(FeatureToggleAuditEntry.ACTION_RESET, audit.get(1).action);
        assertEquals(List.of("ROLE_NOTEBOOKS"), audit.get(1).before);
        assertNull(audit.get(1).after);
    }

    @Test
    public void blankRolesReadAsAdminsOnly() {
        authenticateAs("admin", "ROLE_ADMIN");
        FeatureAdminResource.GrantBody grant = new FeatureAdminResource.GrantBody();
        grant.roles = new String[] {"  ", ""};
        resource.update("notebooks", grant);
        // A grant of only blanks normalises to the empty list = admins only.
        FeatureToggles toggles = new FeatureToggles(togglesFile);
        assertEquals(List.of(), toggles.grantOf("notebooks"));
    }
}
