/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.repository.AclEntry;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.web.service.SessionService;
import org.saiku.web.share.ShareTokenStore;

/**
 * Resource-level coverage of {@link ShareTokenResource#revoke} — the F4 (saiku#1907, CWE-178)
 * creator-equality site for dashboard share links (one of the 8 sites across 7 files SEC
 * enumerated; this one had NO prior test coverage at all — only {@link ShareTokenStore} did).
 * Hand-rolled stubs in lieu of a mock library (saiku-web has no Mockito).
 */
public class ShareTokenResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ShareTokenStore store;
    private StubSessionService session;
    private StubDatasourceService ds;
    private StubUserService users;
    private ShareTokenResource resource;

    @Before
    public void setUp() throws Exception {
        store = new ShareTokenStore(tmp.newFolder("share").getAbsolutePath());
        session = new StubSessionService();
        ds = new StubDatasourceService();
        users = new StubUserService();
        resource = new ShareTokenResource();
        resource.setStore(store);
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setUserService(users);
    }

    @After
    public void tearDown() {
        org.saiku.web.rest.resources.RoleTestSupport.clear();
    }

    @Test
    public void revoke_by_creator_succeeds() {
        session.username = "admin";
        ds.allow("/q.saikudash");
        ShareTokenResource.MintRequest req = new ShareTokenResource.MintRequest();
        req.dashboardPath = "/q.saikudash";
        Response mint = resource.mint(req);
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) mint.getEntity()).get("token");

        Response r = resource.revoke(token);
        assertEquals(200, r.getStatus());
        assertTrue(store.load(token).revoked);
    }

    /**
     * saiku#1907 F4 (CWE-178): a share link created under one case spelling of an account must
     * remain revocable by the SAME account presenting under a different case — the account store
     * matches usernames case-insensitively. {@code ds.allow} is scoped only to the minting
     * spelling, so {@code stillCanGrant} is false at revoke time and only the creator-equality
     * check can permit this. RED pre-fix (case-sensitive equals denies the creator; 403).
     */
    @Test
    public void revoke_by_creator_succeeds_when_caller_case_differs() {
        session.username = "Admin"; // minted under this spelling
        ds.allow("/q.saikudash");
        ShareTokenResource.MintRequest req = new ShareTokenResource.MintRequest();
        req.dashboardPath = "/q.saikudash";
        Response mint = resource.mint(req);
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) mint.getEntity()).get("token");

        session.username = "admin"; // same account, canonical spelling
        Response r = resource.revoke(token);
        assertEquals(200, r.getStatus());
        assertTrue(store.load(token).revoked);
    }

    @Test
    public void revoke_by_admin_role_succeeds() {
        session.username = "bob";
        ds.allow("/q.saikudash");
        ShareTokenResource.MintRequest req = new ShareTokenResource.MintRequest();
        req.dashboardPath = "/q.saikudash";
        Response mint = resource.mint(req);
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) mint.getEntity()).get("token");

        session.username = "admin";
        session.roles = List.of("ROLE_ADMIN");
        users.adminRoles = List.of("ROLE_ADMIN");

        Response r = resource.revoke(token);
        assertEquals(200, r.getStatus());
    }

    @Test
    public void revoke_by_unrelated_user_is_forbidden() {
        session.username = "admin";
        ds.allow("/q.saikudash");
        ShareTokenResource.MintRequest req = new ShareTokenResource.MintRequest();
        req.dashboardPath = "/q.saikudash";
        Response mint = resource.mint(req);
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) mint.getEntity()).get("token");

        session.username = "bob";
        session.roles = List.of("ROLE_USER");
        Response r = resource.revoke(token);
        assertEquals(403, r.getStatus());
        assertFalse(store.load(token).revoked);
    }

    @Test
    public void revoke_unknown_id_is_404() {
        session.username = "admin";
        Response r = resource.revoke("definitelynotastoredtoken00");
        assertEquals(404, r.getStatus());
    }

    /* --------------------------- stubs ---------------------------- */

    private static class StubSessionService extends SessionService {
        String username;
        List<String> roles = List.of();

        @Override
        public Map<String, Object> getAllSessionObjects() {
            org.saiku.web.rest.resources.RoleTestSupport.authenticate(username, roles);
            Map<String, Object> m = new HashMap<>();
            m.put("username", username);
            m.put("roles", roles);
            return m;
        }
    }

    /** DatasourceService stub. {@code allow(path)} grants whoever the current
     *  {@code session.username} is at the moment of the call — mirrors the real ACL semantics
     *  ("this user can grant THIS path") and, crucially, keeps the grant scoped so a case-variant
     *  revoke can't be masked by the {@code stillCanGrant} fallback. */
    private class StubDatasourceService extends DatasourceService {
        private final Map<String, Set<String>> userToPaths = new HashMap<>();

        void allow(String path) {
            userToPaths.computeIfAbsent(session.username, k -> new HashSet<>()).add(path);
        }

        @Override
        public AclEntry getResourceACL(String file, String username, List<String> roles) {
            Set<String> paths = userToPaths.get(username);
            return paths != null && paths.contains(file) ? new AclEntry() : null;
        }
    }

    private static class StubUserService extends UserService {
        List<String> adminRoles = List.of();

        @Override
        public List<String> getAdminRoles() {
            return adminRoles;
        }
    }
}
