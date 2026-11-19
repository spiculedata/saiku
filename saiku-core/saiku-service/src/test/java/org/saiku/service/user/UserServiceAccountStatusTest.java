/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;

/** saiku#2345: the H2 enabled flag and roles, as the web layer needs them to revoke access. */
public class UserServiceAccountStatusTest {

    @Test
    public void unknownUser_isUnmanaged() {
        AccountStatus status = usersOf(user("alice", true, "ROLE_USER")).accountStatus("bob");

        assertFalse(status.managed());
    }

    @Test
    public void disabledUser_isManagedAndNotEnabled() {
        AccountStatus status = usersOf(user("alice", false, "ROLE_USER")).accountStatus("alice");

        assertTrue(status.managed());
        assertFalse(status.enabled());
    }

    @Test
    public void rolesComeFromTheUserRolesRow_andUsernameIsCaseInsensitive() {
        AccountStatus status =
                usersOf(user("alice", true, "ROLE_USER", "ROLE_ADMIN")).accountStatus("ALICE");

        assertTrue(status.managed());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), status.roles());
    }

    @Test
    public void rowWithoutRoles_hasEmptyRoleSet() {
        AccountStatus status = usersOf(user("alice", true)).accountStatus("alice");

        assertTrue(status.roles().isEmpty());
    }

    @Test
    public void lookupError_isUnmanagedNotAnException() {
        UserService us = new UserService() {
            @Override
            public List<SaikuUser> getUsers() {
                throw new RuntimeException("db down");
            }
        };

        assertFalse(us.accountStatus("alice").managed());
    }

    @Test
    public void blankUsername_isUnmanaged() {
        assertFalse(usersOf(user("alice", true)).accountStatus("  ").managed());
        assertFalse(usersOf(user("alice", true)).accountStatus(null).managed());
    }

    private static SaikuUser user(String name, boolean enabled, String... roles) {
        SaikuUser u = new SaikuUser();
        u.setUsername(name);
        u.setEnabled(enabled);
        u.setRoles(roles.length == 0 ? null : roles);
        return u;
    }

    private static UserService usersOf(SaikuUser... users) {
        final List<SaikuUser> list = new ArrayList<>(Arrays.asList(users));
        return new UserService() {
            @Override
            public List<SaikuUser> getUsers() {
                return list;
            }
        };
    }
}
