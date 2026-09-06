/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ISessionService;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1903 — call-site coverage for the write half of the datasource RCE chain. A ROLE_USER
 * could POST an {@code .sds} descriptor to {@code /repository/resource} (into their own home, or
 * the then world-writable {@code /datasources}) and have {@code /discover/refresh} connect its
 * JDBC URL. The resource now refuses descriptor writes from non-admins with a 403 before the
 * service layer is touched; admins and ordinary files are unaffected.
 */
public class BasicRepositoryResource2DatasourceWriteTest {

    private static final String PG_GADGET_SDS = "<dataSource><location>jdbc:postgresql://evil/db"
            + "?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext</location></dataSource>";

    private BasicRepositoryResource2 resource;
    private RecordingDatasourceService files;
    private UserService users;

    @Before
    public void setUp() {
        files = new RecordingDatasourceService();
        users = new UserService();
        users.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        resource = new BasicRepositoryResource2();
        resource.setDatasourceService(files);
        resource.setSessionService(new StubSessionService("bob"));
        resource.setUserService(users);
    }

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------------------------------------------------------- saveResource */

    @Test
    public void nonAdmin_cannotSaveSdsIntoOwnHome() {
        loginAs("bob", "ROLE_USER");
        Response r = resource.saveResource("/homes/bob/evil.sds", PG_GADGET_SDS);
        assertEquals(403, r.getStatus());
        assertEquals("the service layer must not be reached", 0, files.saves);
        assertTrue(String.valueOf(r.getEntity()).contains("administrator"));
    }

    @Test
    public void nonAdmin_cannotSaveSdsWithUppercaseExtension() {
        loginAs("bob", "ROLE_USER");
        assertEquals(
                403, resource.saveResource("/homes/bob/evil.SDS", PG_GADGET_SDS).getStatus());
        assertEquals(0, files.saves);
    }

    @Test
    public void nonAdmin_cannotSaveSdsWithWindowsFilenameEvasion() {
        // saiku#1903 SEC follow-up: Win32 drops trailing dots/spaces and NTFS ADS suffixes, so
        // each of these lands on disk as evil.sds. All must 403.
        loginAs("bob", "ROLE_USER");
        assertEquals(
                403,
                resource.saveResource("/homes/bob/evil.sds.", PG_GADGET_SDS).getStatus());
        assertEquals(
                403,
                resource.saveResource("/homes/bob/evil.sds ", PG_GADGET_SDS).getStatus());
        assertEquals(
                403,
                resource.saveResource("/homes/bob/EVIL.SDS.", PG_GADGET_SDS).getStatus());
        assertEquals(
                403,
                resource.saveResource("/homes/bob/evil.sds::$DATA", PG_GADGET_SDS)
                        .getStatus());
        assertEquals(0, files.saves);
    }

    @Test
    public void nonAdmin_cannotSaveAnythingUnderDatasources() {
        loginAs("bob", "ROLE_USER");
        assertEquals(
                403,
                resource.saveResource("/datasources/schema.xml", "<Schema/>").getStatus());
        assertEquals(
                403,
                resource.saveResource("datasources/schema.xml", "<Schema/>").getStatus());
        assertEquals(
                403,
                resource.saveResource("\\datasources\\schema.xml", "<Schema/>").getStatus());
        assertEquals(0, files.saves);
    }

    @Test
    public void nonAdmin_canStillSaveOrdinaryFiles() {
        loginAs("bob", "ROLE_USER");
        Response r = resource.saveResource("/homes/bob/report.saiku", "{}");
        assertEquals(200, r.getStatus());
        assertEquals(1, files.saves);
        assertEquals("/homes/bob/report.saiku", files.lastSavePath);
        // A name that merely CONTAINS the word is not a descriptor.
        assertEquals(
                200,
                resource.saveResource("/homes/bob/my-datasources-notes.txt", "x")
                        .getStatus());
        assertEquals(2, files.saves);
    }

    @Test
    public void admin_canSaveSds() {
        loginAs("root", "ROLE_USER", "ROLE_ADMIN");
        Response r = resource.saveResource("/datasources/warehouse.sds", "<dataSource/>");
        assertEquals(200, r.getStatus());
        assertEquals(1, files.saves);
        assertEquals("/datasources/warehouse.sds", files.lastSavePath);
    }

    @Test
    public void unauthenticatedCaller_isNotAdmin() {
        // No authentication at all: fail closed (SessionRoles yields no roles, isAdmin() is false).
        assertEquals(
                403, resource.saveResource("/homes/bob/evil.sds", PG_GADGET_SDS).getStatus());
        assertEquals(0, files.saves);
    }

    /* ---------------------------------------------------------------- moveResource */

    @Test
    public void nonAdmin_cannotRenameIntoSds() {
        loginAs("bob", "ROLE_USER");
        Response r = resource.moveResource("/homes/bob/innocent.txt", "/homes/bob/evil.sds");
        assertEquals(403, r.getStatus());
        assertEquals(0, files.moves);
    }

    @Test
    public void nonAdmin_cannotMoveIntoDatasources() {
        loginAs("bob", "ROLE_USER");
        assertEquals(
                403,
                resource.moveResource("/homes/bob/x.xml", "/datasources/x.xml").getStatus());
        assertEquals(0, files.moves);
    }

    @Test
    public void nonAdmin_canStillMoveOrdinaryFiles() {
        loginAs("bob", "ROLE_USER");
        assertEquals(
                200,
                resource.moveResource("/homes/bob/a.saiku", "/homes/bob/b.saiku")
                        .getStatus());
        assertEquals(1, files.moves);
    }

    @Test
    public void admin_canMoveSds() {
        loginAs("root", "ROLE_ADMIN");
        assertEquals(
                200,
                resource.moveResource("/datasources/a.sds", "/datasources/b.sds")
                        .getStatus());
        assertEquals(1, files.moves);
    }

    /* ---------------------------------------------------------------- fallback without a UserService */

    @Test
    public void withoutUserService_fallsBackToRoleAdminAuthority() {
        resource.setUserService(null);
        loginAs("bob", "ROLE_USER");
        assertEquals(
                403, resource.saveResource("/homes/bob/evil.sds", PG_GADGET_SDS).getStatus());
        assertEquals(0, files.saves);

        loginAs("root", "ROLE_ADMIN");
        assertEquals(
                200,
                resource.saveResource("/datasources/ok.sds", "<dataSource/>").getStatus());
        assertEquals(1, files.saves);
    }

    @Test
    public void descriptorPathPredicate() {
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/x.sds"));
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("x.SDS"));
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("./datasources/x.xml"));
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("/tenant/datasources/x.xml"));
        assertFalse(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/x.sds.bak"));
        assertFalse(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/report.saiku"));
        assertFalse(BasicRepositoryResource2.isDatasourceDescriptorPath(null));
        // Windows filename-normalisation evasions (saiku#1903 SEC follow-up).
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/evil.sds."));
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/evil.sds "));
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/EVIL.SDS."));
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/evil.sds::$DATA"));
        assertTrue(BasicRepositoryResource2.isDatasourceDescriptorPath("/homes/bob/evil.sds:stream"));
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

    /** Records what reaches the service layer; never touches a filesystem. */
    static final class RecordingDatasourceService extends DatasourceService {
        int saves;
        String lastSavePath;
        int moves;

        @Override
        public String saveFile(String content, String path, String name, List<String> roles) {
            saves++;
            lastSavePath = path;
            return "Save Okay";
        }

        @Override
        public String moveFile(String source, String target, String name, List<String> roles) {
            moves++;
            return "Move Okay";
        }
    }

    static final class StubSessionService implements ISessionService {
        private final String username;

        StubSessionService(String username) {
            this.username = username;
        }

        @Override
        public Map<String, Object> login(HttpServletRequest req, String username, String password) {
            return Collections.emptyMap();
        }

        @Override
        public void logout(HttpServletRequest req) {}

        @Override
        public void authenticate(HttpServletRequest req, String username, String password) {}

        @Override
        public Map<String, Object> getSession() {
            return getAllSessionObjects();
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return Collections.singletonMap("username", username);
        }

        @Override
        public void clearSessions(HttpServletRequest req, String username, String password) {}
    }
}
