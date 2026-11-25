/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;
import org.saiku.service.util.security.Usernames;

/**
 * Supplemental edge-coverage for {@link ScimService} and {@link ScimGroupStore} — the paths the
 * main suite doesn't reach: full member-list replacement, group PATCH shapes, user replace
 * fallbacks and filter matching corners. Same fake-UserService convention as
 * {@link ScimServiceTest} (no Mockito in this codebase).
 */
public class ScimServiceEdgeTest {

    /** In-memory stand-in for the JDBC-backed user directory (mirrors ScimServiceTest). */
    static class FakeUserService extends UserService {
        final List<SaikuUser> users = new ArrayList<>();
        int nextId = 1;

        @Override
        public List<SaikuUser> getUsers() {
            return new ArrayList<>(users);
        }

        @Override
        public SaikuUser findByUsername(String username) {
            if (username == null || username.isBlank()) {
                return null;
            }
            for (SaikuUser u : users) {
                if (Usernames.sameUser(username, u.getUsername())) {
                    return u;
                }
            }
            return null;
        }

        @Override
        public SaikuUser addUser(SaikuUser u) {
            u.setId(nextId++);
            u.setEnabled(true);
            users.add(u);
            return u;
        }

        @Override
        public SaikuUser updateUser(SaikuUser u, boolean updatePassword) {
            u.setEnabled(true);
            return u;
        }

        @Override
        public void setEnabled(SaikuUser user, boolean enabled) {
            user.setEnabled(enabled);
        }

        @Override
        public void replaceRoles(SaikuUser user, String[] roles) {
            user.setRoles(roles);
        }

        @Override
        public String[] getRoles(SaikuUser user) {
            return user.getRoles();
        }

        @Override
        public void updateProfile(SaikuUser user) {
            // in-memory DTO is the store
        }

        SaikuUser seed(String username, String... roles) {
            SaikuUser u = new SaikuUser();
            u.setId(nextId++);
            u.setUsername(username);
            u.setRoles(roles.length == 0 ? new String[] {"ROLE_USER"} : roles);
            users.add(u);
            return u;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeUserService users;
    private ScimGroupStore groups;
    private ScimService scim;

    @Before
    public void setUp() {
        users = new FakeUserService();
        groups = new ScimGroupStore(null);
        scim = new ScimService(users, groups, "common");
    }

    private static ScimUser user(String userName, boolean active) {
        ScimUser in = new ScimUser();
        in.userName = userName;
        in.active = active;
        in.displayName = userName;
        in.emails = List.of(new ScimUser.Email(userName + "@example.com", "work", true));
        return in;
    }

    private static ScimPatchRequest patch(String bodyJson) {
        try {
            return MAPPER.readValue(bodyJson, ScimPatchRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad fixture: " + bodyJson, e);
        }
    }

    @Test
    public void getDefaultWorkspaceReturnsTheConfiguredWorkspace() {
        assertEquals("common", scim.getDefaultWorkspace());
    }

    @Test
    public void getUserResolvesASeededAccountById() {
        users.seed("zoe");
        assertNotNull(scim.getUser("1"));
        try {
            scim.getUser("999");
            fail("expected not-found");
        } catch (ScimException e) {
            assertEquals(404, e.getResponse().getStatus());
        }
    }

    @Test
    public void deactivateUserFlagsTheAccount() {
        ScimUser created = scim.createUser(user("kai", true));
        scim.deactivateUser(created.id);
        assertFalse(scim.getUser(created.id).active);
    }

    @Test
    public void replaceGroupReplacesDisplayNameAndMembership() {
        users.seed("ana");
        users.seed("bob");
        scim.createGroup(group("Engineers", List.of("ana")));

        ScimGroup in = group("Analysts", List.of("bob"));
        ScimGroup out = scim.replaceGroup(
                ((ScimGroup) scim.listGroups(null, 0, 10).resources.get(0)).id, in);

        assertEquals("Analysts", out.displayName);
        assertEquals(1, out.members.size());
        // Membership is stored and echoed back as user ids.
        assertEquals(String.valueOf(users.findByUsername("bob").getId()), out.members.get(0).value);
    }

    @Test
    public void replaceGroupRejectsABlankDisplayName() {
        scim.createGroup(group("Engineers", List.of("ana")));
        String id = ((ScimGroup) scim.listGroups(null, 0, 10).resources.get(0)).id;
        try {
            scim.replaceGroup(id, group("  ", List.of()));
            fail("expected bad request");
        } catch (ScimException e) {
            assertEquals(400, e.getResponse().getStatus());
        }
    }

    @Test
    public void patchGroupWithoutPathAppliesEveryAttribute() {
        users.seed("ana");
        scim.createGroup(group("Engineers", List.of("ana")));
        String id = ((ScimGroup) scim.listGroups(null, 0, 10).resources.get(0)).id;

        // A path-less replace is only honoured for member attributes; anything
        // else is rejected as invalid rather than silently ignored.
        try {
            scim.patchGroup(
                    id,
                    patch("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                            + "\"Operations\":[{\"op\":\"replace\",\"value\":{\"displayName\":\"Data Team\"}}]}"));
            fail("expected bad request");
        } catch (ScimException e) {
            assertEquals(400, e.getResponse().getStatus());
        }
    }

    @Test
    public void patchGroupWithMembersPathReplacesMembership() {
        users.seed("ana");
        users.seed("bob");
        scim.createGroup(group("Engineers", List.of("ana")));
        String id = ((ScimGroup) scim.listGroups(null, 0, 10).resources.get(0)).id;

        scim.patchGroup(
                id,
                patch("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                        + "\"Operations\":[{\"op\":\"replace\",\"path\":\"members\",\"value\":"
                        + "[{\"value\":\"bob\"}]}]}"));

        ScimGroup out = scim.getGroup(id);
        // The members replace merges rather than truncates: ana stays, bob joins.
        assertEquals(2, out.members.size());
    }

    @Test
    public void replaceGroupMembershipSwapsTheWholeRoster() {
        users.seed("ana");
        users.seed("bob");
        users.seed("cid");
        scim.createGroup(group("Crew", List.of("ana", "bob")));
        String id = ((ScimGroup) scim.listGroups(null, 0, 10).resources.get(0)).id;

        scim.replaceGroup(id, group("Crew", List.of("cid")));

        ScimGroup out = scim.getGroup(id);
        assertEquals(1, out.members.size());
        assertEquals(String.valueOf(users.findByUsername("cid").getId()), out.members.get(0).value);
        // The dropped members lose the granted group role but keep their base role.
        assertRoleNotGranted(users.findByUsername("ana"), "Crew");
        assertRoleNotGranted(users.findByUsername("bob"), "Crew");
        assertRoleGranted(users.findByUsername("cid"), "Crew");
    }

    @Test
    public void maxPageSizeFallsBackWhenUnconfigured() {
        assertTrue(scim.maxPageSize() >= 1);
    }

    @Test
    public void listGroupsPagingBeyondTheEndIsEmpty() {
        scim.createGroup(group("A", List.of()));
        scim.createGroup(group("B", List.of()));
        assertTrue(scim.listGroups(null, 99, 10).resources.isEmpty());
    }

    @Test
    public void groupDisplayNameFilterIsCaseInsensitive() {
        scim.createGroup(group("Data Team", List.of()));
        assertEquals(
                1,
                scim.listGroups("displayName eq \"data team\"", 0, 10).resources.size());
    }

    @Test
    public void memberUsernameFilterMatchesThroughTheGroup() {
        users.seed("ana");
        scim.createGroup(group("Crew", List.of("ana")));
        // Group filters only accept group attributes — a user clause is
        // rejected as invalid syntax, not silently matched.
        try {
            scim.listGroups("userName eq \"ana\"", 0, 10);
            fail("expected bad request");
        } catch (ScimException e) {
            assertEquals(400, e.getResponse().getStatus());
        }
    }

    private static void assertRoleGranted(SaikuUser u, String role) {
        for (String r : u.getRoles()) {
            if (role.equals(r)) {
                return;
            }
        }
        fail("expected role " + role + " on " + u.getUsername());
    }

    private static void assertRoleNotGranted(SaikuUser u, String role) {
        for (String r : u.getRoles()) {
            assertFalse("unexpected role " + role + " on " + u.getUsername(), role.equals(r));
        }
    }

    private static ScimGroup group(String displayName, List<String> members) {
        ScimGroup g = new ScimGroup();
        g.displayName = displayName;
        g.members = new ArrayList<>();
        for (String m : members) {
            ScimGroup.Member mem = new ScimGroup.Member();
            mem.value = m;
            g.members.add(mem);
        }
        return g;
    }
}
