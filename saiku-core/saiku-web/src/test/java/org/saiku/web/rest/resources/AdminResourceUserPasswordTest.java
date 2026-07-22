/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;

/**
 * saiku#1514: the admin panel used to accept a password change, write it to H2, and return 200 —
 * while authentication kept reading users.properties, so the superseded credential stayed live.
 * Drives {@link AdminResource} against a stubbed {@link UserService} to pin the honest refusal.
 *
 * <p>The load-bearing assertion is {@link #passwordChange_neverReachesTheUserService()}: a 501 that
 * still wrote to H2 would be a cosmetic fix. The write must not happen at all.
 */
public class AdminResourceUserPasswordTest {

    private AdminResource resource;
    private StubUserService users;

    @Before
    public void setUp() {
        users = new StubUserService();
        resource = new AdminResource();
        resource.setUserService(users);
    }

    private static SaikuUser user(String username, String password) {
        SaikuUser u = new SaikuUser();
        u.setUsername(username);
        u.setPassword(password);
        u.setEmail(username + "@example.com");
        u.setRoles(new String[] {"ROLE_USER"});
        return u;
    }

    /* ------------------------- the refusal ------------------------- */

    @Test
    public void passwordChange_isRefusedWith501() {
        Response resp = resource.updateUserDetails(user("admin", "a-new-password"), "admin");

        assertEquals(
                "a password change must be refused, not acknowledged — 501: the request is"
                        + " well-formed and the caller is a legitimate admin, the capability is absent",
                501,
                resp.getStatus());
    }

    @Test
    public void passwordChange_neverReachesTheUserService() {
        resource.updateUserDetails(user("admin", "a-new-password"), "admin");

        assertEquals(
                "the refusal must happen before the write — a 501 that still hashed into H2 would"
                        + " leave the operator's password lying around in a store nothing reads",
                0,
                users.updateCalls);
        assertNull(users.lastUpdatedUser);
    }

    /**
     * The refusal is only useful if it teaches. An operator who lands here must leave knowing how
     * to actually rotate — the wording is the launcher's boot banner (SaikuLauncher.java:426-431).
     */
    @Test
    public void refusal_tellsTheOperatorHowToRotate() {
        Response resp = resource.updateUserDetails(user("admin", "a-new-password"), "admin");
        String body = String.valueOf(resp.getEntity());

        assertTrue("must name the recommended mechanism", body.contains("SAIKU_ADMIN_PASSWORD"));
        assertTrue("must name the file that auth actually reads", body.contains("users.properties"));
        assertTrue("must give the bcrypt recipe for the by-hand path", body.contains("htpasswd -nbBC 12"));
        assertTrue("must offer the LDAP / OAuth / SAML escape hatch", body.contains("LDAP / OAuth / SAML"));
        assertTrue(
                "must link the issue so the reasoning is reachable",
                body.contains("github.com/spiculedata/saiku/issues/1514"));
    }

    /**
     * Every password written by this panel is inert for authentication, not just the admin row —
     * no AuthenticationProvider reads H2 at all. Scoping the refusal to "admin" would silently
     * assert that non-admin rotations work, which is false.
     */
    @Test
    public void refusal_appliesToEveryUserNotJustAdmin() {
        Response resp = resource.updateUserDetails(user("analyst", "a-new-password"), "analyst");

        assertEquals(501, resp.getStatus());
        assertEquals(0, users.updateCalls);
    }

    /* ------------- what must keep working (regression guard) ------------- */

    @Test
    public void emptyPassword_stillUpdatesProfileAndRoles() {
        Response resp = resource.updateUserDetails(user("admin", ""), "admin");

        assertEquals("an email/roles edit must be unaffected by the password refusal", 200, resp.getStatus());
        assertEquals(1, users.updateCalls);
        assertFalse("an empty password must not be treated as a password change", users.lastUpdatePasswordFlag);
    }

    @Test
    public void nullPassword_stillUpdatesProfileAndRoles() {
        Response resp = resource.updateUserDetails(user("admin", null), "admin");

        assertEquals(200, resp.getStatus());
        assertEquals(1, users.updateCalls);
        assertFalse(users.lastUpdatePasswordFlag);
    }

    /**
     * saiku#1514 scope boundary. Creating a user is NOT refused: the H2 row is what
     * {@code /saiku/api/users} lists, which is the @-mention directory for dashboard comments.
     * A blanket refusal here would remove the only way to add someone to that directory — and it
     * would have to be blanket, because {@code UserService.addUser} validates the password policy
     * unconditionally, so there is no password-free create to fall back on. If this test ever
     * fails, that capability is being removed — make it a deliberate decision, not a tidy-up.
     */
    @Test
    public void createUser_isDeliberatelyNotRefused() {
        Response resp = resource.createUserDetails(user("newbie", "a-valid-password"));

        assertEquals(200, resp.getStatus());
        assertEquals(1, users.addCalls);
    }

    /** The authz gate must still precede the refusal — a non-admin learns nothing from it. */
    @Test
    public void nonAdmin_isForbidden_andIsNotToldHowToRotate() {
        users.admin = false;

        Response resp = resource.updateUserDetails(user("admin", "a-new-password"), "admin");

        assertEquals(403, resp.getStatus());
        assertNull("the rotation guidance must not leak to a non-admin", resp.getEntity());
        assertEquals(0, users.updateCalls);
    }

    /* ------------------------------ stub ------------------------------ */

    private static final class StubUserService extends UserService {
        boolean admin = true;
        int updateCalls = 0;
        int addCalls = 0;
        SaikuUser lastUpdatedUser = null;
        boolean lastUpdatePasswordFlag = false;

        @Override
        public boolean isAdmin() {
            return admin;
        }

        @Override
        public SaikuUser updateUser(SaikuUser u, boolean updatepassword) {
            updateCalls++;
            lastUpdatedUser = u;
            lastUpdatePasswordFlag = updatepassword;
            return u;
        }

        @Override
        public SaikuUser addUser(SaikuUser u) {
            addCalls++;
            return u;
        }
    }
}
