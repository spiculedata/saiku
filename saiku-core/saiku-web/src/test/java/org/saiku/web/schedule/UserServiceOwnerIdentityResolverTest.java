/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.schedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;

/**
 * Coverage for {@link UserServiceOwnerIdentityResolver} (saiku#1809, PR3). Verifies it re-reads the
 * owner's CURRENT roles live and fails closed on unknown/blank usernames or lookup errors — never a
 * best-effort grant.
 */
public class UserServiceOwnerIdentityResolverTest {

    /** A UserService whose user list + role lookup are scripted per test. */
    private static final class FakeUserService extends UserService {
        List<SaikuUser> users = new ArrayList<>();
        String[] rolesToReturn;
        boolean throwOnGetUsers;

        @Override
        public List<SaikuUser> getUsers() {
            if (throwOnGetUsers) {
                throw new RuntimeException("db down");
            }
            return users;
        }

        @Override
        public String[] getRoles(SaikuUser u) {
            return rolesToReturn;
        }
    }

    private static SaikuUser user(String name) {
        return user(name, true);
    }

    private static SaikuUser user(String name, boolean enabled) {
        SaikuUser u = new SaikuUser();
        u.setUsername(name);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    public void presentOwner_resolvesCurrentRoles() {
        FakeUserService svc = new FakeUserService();
        svc.users.add(user("alice"));
        svc.rolesToReturn = new String[] {"ROLE_USER", "ROLE_SALES"};

        OwnerIdentity id = new UserServiceOwnerIdentityResolver(svc).resolve("alice");

        assertTrue(id.present());
        assertEquals(List.of("ROLE_USER", "ROLE_SALES"), id.currentRoles());
    }

    /**
     * saiku#1907 F4 (CWE-178): a job owned by a mixed-case account name must still resolve to the
     * SAME account's current roles — the account store matches usernames case-insensitively, so a
     * case-sensitive lookup would fail-close a legitimately-owned job (skip + auto-disable). RED
     * pre-fix (case-sensitive equals never finds "alice" for a lookup of "Alice").
     */
    @Test
    public void presentOwner_resolvesCaseInsensitively() {
        FakeUserService svc = new FakeUserService();
        svc.users.add(user("alice"));
        svc.rolesToReturn = new String[] {"ROLE_USER"};

        OwnerIdentity id = new UserServiceOwnerIdentityResolver(svc).resolve("Alice");

        assertTrue("a case-variant owner name must still resolve present", id.present());
        assertEquals(List.of("ROLE_USER"), id.currentRoles());
    }

    @Test
    public void disabledOwner_failsClosed() {
        // saiku#1809 PR4: a present-but-DISABLED account (USERS.ENABLED = 0) must resolve absent, so its
        // jobs never run and the engine auto-disables them. present() == EXISTS *and* ENABLED.
        FakeUserService svc = new FakeUserService();
        svc.users.add(user("alice", false));
        svc.rolesToReturn = new String[] {"ROLE_USER"};

        OwnerIdentity id = new UserServiceOwnerIdentityResolver(svc).resolve("alice");

        assertFalse("a disabled owner must resolve absent (fail-closed)", id.present());
        assertTrue(id.currentRoles().isEmpty());
    }

    @Test
    public void unknownOwner_failsClosed() {
        FakeUserService svc = new FakeUserService();
        svc.users.add(user("bob"));

        OwnerIdentity id = new UserServiceOwnerIdentityResolver(svc).resolve("alice");

        assertFalse("an unknown owner must resolve absent (fail-closed)", id.present());
        assertTrue(id.currentRoles().isEmpty());
    }

    @Test
    public void blankOrNullOwner_failsClosed() {
        FakeUserService svc = new FakeUserService();
        UserServiceOwnerIdentityResolver r = new UserServiceOwnerIdentityResolver(svc);
        assertFalse(r.resolve("").present());
        assertFalse(r.resolve("   ").present());
        assertFalse(r.resolve(null).present());
    }

    @Test
    public void lookupError_failsClosed() {
        FakeUserService svc = new FakeUserService();
        svc.throwOnGetUsers = true;

        OwnerIdentity id = new UserServiceOwnerIdentityResolver(svc).resolve("alice");

        assertFalse("a lookup error must resolve absent (fail-closed), never a grant", id.present());
    }

    @Test
    public void nullRoles_presentWithEmptyRoles() {
        FakeUserService svc = new FakeUserService();
        svc.users.add(user("alice"));
        svc.rolesToReturn = null; // user with no roles row

        OwnerIdentity id = new UserServiceOwnerIdentityResolver(svc).resolve("alice");

        assertTrue(id.present());
        assertTrue(id.currentRoles().isEmpty());
    }

    @Test
    public void nullUserService_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new UserServiceOwnerIdentityResolver(null));
    }
}
