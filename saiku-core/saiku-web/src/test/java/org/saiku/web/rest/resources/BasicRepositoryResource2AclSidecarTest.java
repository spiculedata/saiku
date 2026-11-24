/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.repository.FilesystemRepositoryManager;
import org.saiku.service.user.UserService;
import org.saiku.web.rest.resources.BasicRepositoryResource2DatasourceWriteTest.RecordingDatasourceService;
import org.saiku.web.rest.resources.BasicRepositoryResource2DatasourceWriteTest.StubSessionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2525 — the REST repository surface answers the contracted 403 for the reserved ACL
 * sidecar ({@code acl.json}) before the service layer is touched: GET/POST/DELETE of the sidecar
 * as ordinary content, and either end of a move. The manager enforces the reservation everywhere
 * (the hard stop for dashboards, apps, notebooks, history, embed-authoring and zip-upload); this
 * is the clean 403 on the top door. Ordinary files are unaffected, and the owner's own calls go
 * through — the sidecar refusal is name-based, not identity-based.
 */
public class BasicRepositoryResource2AclSidecarTest {

    private static final String SIDECAR = "/homes/admin/shared/acl.json";

    private BasicRepositoryResource2 resource;
    private RecordingDatasourceService files;

    @Before
    public void setUp() {
        files = new RecordingDatasourceService();
        UserService users = new UserService();
        users.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        resource = new BasicRepositoryResource2();
        resource.setDatasourceService(files);
        resource.setSessionService(new StubSessionService("admin"));
        resource.setUserService(users);
    }

    /* ---------------------------------------------------------------- refusals */

    @Test
    public void sidecar_read_answers_403() {
        loginAs("admin", "ROLE_ADMIN");
        assertEquals(403, resource.getResource(SIDECAR).getStatus());
    }

    @Test
    public void sidecar_write_answers_403_even_for_the_owner() {
        loginAs("admin", "ROLE_ADMIN");
        Response r = resource.saveResource(SIDECAR, "{\"taken\":true}");
        assertEquals(403, r.getStatus());
        assertEquals("the service layer must not be reached", 0, files.saves);
        assertTrue(String.valueOf(r.getEntity()).contains("reserved"));
    }

    @Test
    public void sidecar_delete_answers_403() {
        loginAs("admin", "ROLE_ADMIN");
        assertEquals(403, resource.deleteResource(SIDECAR).getStatus());
    }

    @Test
    public void sidecar_cannot_be_move_source_or_target() {
        loginAs("admin", "ROLE_ADMIN");
        assertEquals(403, resource.moveResource(SIDECAR, "/homes/admin/x.json").getStatus());
        assertEquals(403, resource.moveResource("/homes/admin/x.json", SIDECAR).getStatus());
        assertEquals("the service layer must not be reached", 0, files.moves);
    }

    @Test
    public void sidecar_refusal_is_case_and_spelling_insensitive() {
        assertTrue(FilesystemRepositoryManager.isAclSidecarPath("/homes/admin/shared/ACL.JSON"));
        assertTrue(FilesystemRepositoryManager.isAclSidecarPath("/homes/admin/shared/acl.json/"));
        assertTrue(FilesystemRepositoryManager.isAclSidecarPath("/homes/admin/shared/acl.json::$DATA"));
        assertTrue(FilesystemRepositoryManager.isAclSidecarPath("./homes/admin/shared/ACL.json"));
    }

    /* ---------------------------------------------------------------- positive controls */

    @Test
    public void ordinary_save_still_reaches_the_service_layer() {
        loginAs("admin", "ROLE_ADMIN");
        assertEquals(
                200,
                resource.saveResource("/homes/admin/shared/report.saikudash", "{}")
                        .getStatus());
        assertEquals(1, files.saves);
        assertEquals("/homes/admin/shared/report.saikudash", files.lastSavePath);
    }

    @Test
    public void ordinary_move_still_reaches_the_service_layer() {
        loginAs("admin", "ROLE_ADMIN");
        assertEquals(
                200,
                resource.moveResource("/homes/admin/a.txt", "/homes/admin/b.txt")
                        .getStatus());
        assertEquals(1, files.moves);
    }

    /* ---------------------------------------------------------------- fixtures */

    private static void loginAs(String user, String... roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String r : roles) {
            authorities.add(new SimpleGrantedAuthority(r));
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "pw", authorities));
    }
}
