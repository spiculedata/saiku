/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;
import org.saiku.service.util.security.Usernames;

/**
 * saiku#1438 — the SCIM ⇄ Saiku mapping, driven through a fake {@link UserService} that mirrors the
 * real one's case-insensitive identity rule ({@link Usernames}) and its "ENABLED is a separate
 * column, not part of the full-row update" behaviour.
 *
 * <p>The fake exists because the real {@code UserService} drags the whole datasource/OLAP graph
 * into a unit test; the parts SCIM depends on are these five methods, and their semantics are
 * pinned here so a regression in {@link ScimService} is what fails the test, not the fake.
 */
public class ScimServiceTest {

    /** In-memory stand-in for the JDBC-backed user directory. */
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
            u.setEnabled(true); // the DAO hard-codes TRUE on insert
            users.add(u);
            return u;
        }

        @Override
        public SaikuUser updateUser(SaikuUser u, boolean updatePassword) {
            u.setEnabled(true); // the legacy UPDATE hard-codes enabled = TRUE
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

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad fixture: " + s, e);
        }
    }

    private static ScimPatchRequest patch(String bodyJson) {
        try {
            return MAPPER.readValue(bodyJson, ScimPatchRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad fixture: " + bodyJson, e);
        }
    }

    private ScimUser create(String userName, boolean active) {
        ScimUser in = new ScimUser();
        in.userName = userName;
        in.active = active;
        in.name = new ScimUser.Name("Given", "Family");
        in.emails = List.of(new ScimUser.Email(userName + "@example.com", "work", true));
        return scim.createUser(in);
    }

    /* ------------------------------- create ------------------------------- */

    @Test
    public void createCanonicalisesUserNameAndDefaultsActive() {
        ScimUser created = create("Bob.Smith", true);
        assertEquals("bob.smith", created.userName);
        assertEquals(Boolean.TRUE, created.active);
        assertNotNull(created.id);
        assertEquals("Given", created.name.givenName);
        // The email is stored exactly as the IdP sent it — only the login identity is
        // case-normalised, because EMAIL has no uniqueness or ACL role.
        assertEquals("Bob.Smith@example.com", created.emails.get(0).value);
    }

    @Test
    public void createWithActiveFalseDeactivatesTheNewAccount() {
        // addUser inserts ENABLED=TRUE unconditionally, so the flag must be re-applied afterwards.
        ScimUser created = create("carol", false);
        assertEquals(Boolean.FALSE, created.active);
        assertFalse(users.findByUsername("carol").isEnabled());
    }

    @Test
    public void createNeverStoresAGuessablePassword() {
        create("dave", true);
        SaikuUser stored = users.findByUsername("dave");
        assertNotNull("the NOT NULL password column must be satisfied", stored.getPassword());
        assertFalse("a SCIM account must not carry a guessable credential", "dave".equals(stored.getPassword()));
        assertTrue(
                "a high-entropy random value is expected", stored.getPassword().length() >= 24);
    }

    @Test
    public void createIsIdempotencySafeForTheIdp() {
        create("erin", true);
        try {
            create("ERIN", true);
            fail("a second create for the same account must be a uniqueness conflict");
        } catch (ScimException e) {
            assertEquals(409, e.getResponse().getStatus());
            assertEquals("uniqueness", e.getScimType());
        }
    }

    @Test
    public void createRejectsABlankUserName() {
        try {
            scim.createUser(new ScimUser());
            fail("userName is required");
        } catch (ScimException e) {
            assertEquals("invalidValue", e.getScimType());
        }
    }

    @Test
    public void createRejectsAPathBearingUserName() {
        try {
            create("../../etc/passwd", true);
            fail("a userName that could escape /homes/<user> must be rejected");
        } catch (ScimException e) {
            assertEquals("invalidValue", e.getScimType());
        }
    }

    /* -------------------------------- read -------------------------------- */

    @Test
    public void listReturnsAWebPagedEnvelope() {
        create("a", true);
        create("b", true);
        create("c", true);
        ScimListResponse page = scim.listUsers(null, 1, 2);
        assertEquals(3, page.totalResults);
        assertEquals(2, page.itemsPerPage);
        assertEquals(1, page.startIndex);
        assertEquals(2, page.resources.size());

        ScimListResponse second = scim.listUsers(null, 3, 2);
        assertEquals(1, second.resources.size());
        assertEquals(3, second.startIndex);
    }

    @Test
    public void listPagingPastTheEndIsEmptyNotAnError() {
        create("a", true);
        assertEquals(0, scim.listUsers(null, 99, 10).resources.size());
    }

    @Test
    public void filterByUserNameIsCaseInsensitive() {
        create("Frank", true);
        assertEquals(1, scim.listUsers("userName eq \"frank\"", 1, 10).totalResults);
        assertEquals(1, scim.listUsers("userName eq \"FRANK\"", 1, 10).totalResults);
    }

    @Test
    public void filterByUserNameStartsWith() {
        create("frank", true);
        create("frances", true);
        assertEquals(2, scim.listUsers("userName sw \"fran\"", 1, 10).totalResults);
    }

    @Test
    public void filterByActiveMatchesTheEnabledColumn() {
        create("active.user", true);
        create("gone.user", false);
        assertEquals(1, scim.listUsers("active eq true", 1, 10).totalResults);
        assertEquals(1, scim.listUsers("active eq false", 1, 10).totalResults);
    }

    @Test
    public void unsupportedFilterIsRejectedRatherThanSilentlyEmpty() {
        create("a", true);
        try {
            scim.listUsers("externalId eq \"x\"", 1, 10);
            fail("an unstored attribute must not silently match nothing");
        } catch (ScimException e) {
            assertEquals("invalidFilter", e.getScimType());
        }
    }

    @Test
    public void getUnknownUserIsNotFound() {
        try {
            scim.getUser("404");
            fail("unknown id must 404");
        } catch (ScimException e) {
            assertEquals(404, e.getResponse().getStatus());
        }
    }

    @Test
    public void getWithANonNumericIdIsNotFound() {
        try {
            scim.getUser("../../etc/passwd");
            fail("a non-numeric id can never name an account");
        } catch (ScimException e) {
            assertEquals(404, e.getResponse().getStatus());
        }
    }

    /* ------------------------------- replace ------------------------------- */

    @Test
    public void putReplacesTheProfile() {
        ScimUser created = create("gina", true);
        ScimUser in = new ScimUser();
        in.userName = "gina";
        in.name = new ScimUser.Name("G", "Two");
        in.active = true;
        ScimUser replaced = scim.replaceUser(created.id, in);
        assertEquals("G", replaced.name.givenName);
        assertEquals("gina", replaced.userName);
    }

    @Test
    public void putHonoursActiveFalse() {
        ScimUser created = create("hank", true);
        ScimUser in = new ScimUser();
        in.userName = "hank";
        in.active = false;
        assertEquals(Boolean.FALSE, scim.replaceUser(created.id, in).active);
        assertFalse(users.findByUsername("hank").isEnabled());
    }

    @Test
    public void putRenameToATakenNameIsAConflict() {
        create("ivy", true);
        ScimUser kate = create("kate", true);
        ScimUser in = new ScimUser();
        in.userName = "ivy";
        try {
            scim.replaceUser(kate.id, in);
            fail("renaming onto an existing account must be a uniqueness conflict");
        } catch (ScimException e) {
            assertEquals(409, e.getResponse().getStatus());
            assertEquals("uniqueness", e.getScimType());
        }
    }

    /* -------------------------------- patch -------------------------------- */

    @Test
    public void oktaPatchDeactivates() {
        ScimUser created = create("leo", true);
        ScimUser out = scim.patchUser(
                created.id, patch("{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}"));
        assertEquals(Boolean.FALSE, out.active);
        assertFalse(users.findByUsername("leo").isEnabled());
    }

    @Test
    public void entraPatchReactivates() {
        ScimUser created = create("mona", false);
        ScimUser out = scim.patchUser(
                created.id,
                patch(
                        "{\"Operations\":[{\"op\":\"Replace\",\"path\":\"urn:ietf:params:scim:schemas:core:2.0:User:active\",\"value\":true}]}"));
        assertEquals(Boolean.TRUE, out.active);
    }

    @Test
    public void pathlessPatchIsHonoured() {
        ScimUser created = create("nina", true);
        ScimUser out = scim.patchUser(
                created.id, patch("{\"Operations\":[{\"op\":\"replace\",\"value\":{\"active\":false}}]}"));
        assertEquals(Boolean.FALSE, out.active);
    }

    @Test
    public void patchActiveDoesNotBlankTheProfile() {
        // The regression this guards: an active-only PATCH must not wipe the name sent at create.
        ScimUser created = create("omar", true);
        scim.patchUser(
                created.id, patch("{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}"));
        SaikuUser stored = users.findByUsername("omar");
        assertEquals("Given", stored.getGivenName());
    }

    @Test
    public void patchNameAndEmail() {
        ScimUser created = create("pat", true);
        scim.patchUser(
                created.id,
                patch("{\"Operations\":["
                        + "{\"op\":\"replace\",\"path\":\"name.givenName\",\"value\":\"Patricia\"},"
                        + "{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"].value\",\"value\":\"pat@new.example\"}"
                        + "]}"));
        SaikuUser stored = users.findByUsername("pat");
        assertEquals("Patricia", stored.getGivenName());
        assertEquals("pat@new.example", stored.getEmail());
    }

    @Test
    public void patchRenameMovesTheAccount() {
        ScimUser created = create("quinn", true);
        ScimUser out = scim.patchUser(
                created.id,
                patch("{\"Operations\":[{\"op\":\"replace\",\"path\":\"userName\",\"value\":\"quinn.new\"}]}"));
        assertEquals("quinn.new", out.userName);
        assertNull(users.findByUsername("quinn"));
    }

    @Test
    public void patchRejectsRemovingUserName() {
        ScimUser created = create("rita", true);
        try {
            scim.patchUser(created.id, patch("{\"Operations\":[{\"op\":\"remove\",\"path\":\"userName\"}]}"));
            fail("userName is immutable in this mapping");
        } catch (ScimException e) {
            assertEquals("mutability", e.getScimType());
        }
    }

    @Test
    public void patchRejectsAnUnknownAttribute() {
        ScimUser created = create("sam", true);
        try {
            scim.patchUser(
                    created.id, patch("{\"Operations\":[{\"op\":\"replace\",\"path\":\"nickName\",\"value\":\"x\"}]}"));
            fail("an unknown path must be rejected, not ignored");
        } catch (ScimException e) {
            assertEquals("invalidPath", e.getScimType());
        }
    }

    @Test
    public void emptyPatchBodyIsRejected() {
        ScimUser created = create("tia", true);
        try {
            scim.patchUser(created.id, patch("{\"Operations\":[]}"));
            fail("RFC 7644 3.5.2 requires at least one operation");
        } catch (ScimException e) {
            assertEquals("invalidSyntax", e.getScimType());
        }
    }

    /* ------------------------------- delete ------------------------------- */

    @Test
    public void deleteIsASoftDeactivation() {
        ScimUser created = create("uma", true);
        scim.deactivateUser(created.id);
        assertNotNull("the account must survive a delete", users.findByUsername("uma"));
        assertFalse(users.findByUsername("uma").isEnabled());
    }

    @Test
    public void deleteOfAnUnknownUserIsNotFound() {
        try {
            scim.deactivateUser("9999");
            fail("unknown id must 404");
        } catch (ScimException e) {
            assertEquals(404, e.getResponse().getStatus());
        }
    }

    @Test
    public void deleteThenReReactivateRoundTrips() {
        // Okta re-sends deactivate for an already-deactivated user; the cycle must be safe.
        ScimUser created = create("vic", true);
        scim.deactivateUser(created.id);
        scim.deactivateUser(created.id);
        assertEquals(
                Boolean.TRUE,
                scim.patchUser(
                                created.id,
                                patch("{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]}"))
                        .active);
    }

    /* ------------------------------- groups ------------------------------- */

    @Test
    public void groupCreateGrantsTheRoleToMembers() {
        SaikuUser u = users.seed("wendy");
        ScimGroup in = new ScimGroup();
        in.displayName = "analysts";
        in.members = new ArrayList<>(List.of(new ScimGroup.Member(String.valueOf(u.getId()), "wendy")));

        ScimGroup g = scim.createGroup(in);
        assertEquals("analysts", g.displayName);
        assertNotNull(g.id);
        assertTrue("the group's role must be granted", rolesOf(u).contains("analysts"));
        assertTrue("ROLE_USER must survive a role change", rolesOf(u).contains("ROLE_USER"));
    }

    @Test
    public void groupPatchAddsAndRemovesMembers() {
        SaikuUser a = users.seed("xena");
        SaikuUser b = users.seed("yara");
        ScimGroup g = new ScimGroup();
        g.displayName = "finance";
        g = scim.createGroup(g);

        scim.patchGroup(
                g.id,
                patch("{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + a.getId()
                        + "\"}]}]}"));
        assertTrue(rolesOf(a).contains("finance"));

        scim.patchGroup(
                g.id,
                patch("{\"Operations\":[{\"op\":\"remove\",\"path\":\"members\",\"value\":{\"value\":\"" + a.getId()
                        + "\"}}]}"));
        assertFalse("a removed member must lose the role", rolesOf(a).contains("finance"));

        scim.patchGroup(
                g.id,
                patch("{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[\"" + b.getId() + "\"]}]}"));
        assertTrue(rolesOf(b).contains("finance"));
    }

    @Test
    public void groupPutRenameAlsoMovesTheGrant() {
        // PUT and PATCH must not diverge here: the displayName IS the role name.
        SaikuUser u = users.seed("quinn");
        ScimGroup g = new ScimGroup();
        g.displayName = "before";
        g = scim.createGroup(g);
        scim.patchGroup(
                g.id,
                patch("{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[\"" + u.getId() + "\"]}]}"));

        ScimGroup replacement = new ScimGroup();
        replacement.displayName = "after";
        replacement.members = new ArrayList<>(List.of(new ScimGroup.Member(String.valueOf(u.getId()), "quinn")));
        scim.replaceGroup(g.id, replacement);

        assertFalse(rolesOf(u).contains("before"));
        assertTrue(rolesOf(u).contains("after"));
        assertEquals(1, scim.listGroups(null, 1, 10).totalResults);
    }

    @Test
    public void groupRenameMovesTheGrant() {
        SaikuUser u = users.seed("zara");
        ScimGroup g = new ScimGroup();
        g.displayName = "old_role";
        g = scim.createGroup(g);
        scim.patchGroup(
                g.id,
                patch("{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[\"" + u.getId() + "\"]}]}"));
        assertTrue(rolesOf(u).contains("old_role"));
        scim.patchGroup(
                g.id, patch("{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"new_role\"}]}"));
        assertFalse(rolesOf(u).contains("old_role"));
        assertTrue(rolesOf(u).contains("new_role"));
    }

    @Test
    public void groupDeleteRevokesTheRoleFromEveryMember() {
        SaikuUser u = users.seed("anna");
        ScimGroup g = new ScimGroup();
        g.displayName = "contractors";
        g = scim.createGroup(g);
        scim.patchGroup(
                g.id,
                patch("{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[\"" + u.getId() + "\"]}]}"));
        assertTrue(rolesOf(u).contains("contractors"));

        scim.deleteGroup(g.id);
        assertFalse("a deleted group must not leave a stale grant", rolesOf(u).contains("contractors"));
        assertTrue(rolesOf(u).contains("ROLE_USER"));
    }

    @Test
    public void groupCreateRejectsADuplicateDisplayName() {
        ScimGroup g = new ScimGroup();
        g.displayName = "sales";
        scim.createGroup(g);
        try {
            scim.createGroup(g);
            fail("a duplicate displayName is a uniqueness conflict");
        } catch (ScimException e) {
            assertEquals("uniqueness", e.getScimType());
        }
    }

    @Test
    public void groupRejectsAnOverlongRoleName() {
        ScimGroup g = new ScimGroup();
        g.displayName = "x".repeat(50);
        try {
            scim.createGroup(g);
            fail("USER_ROLES.ROLE is VARCHAR(45)");
        } catch (ScimException e) {
            assertEquals("invalidValue", e.getScimType());
        }
    }

    @Test
    public void userCreateWithGroupsGrantsTheRole() {
        ScimGroup g = new ScimGroup();
        g.displayName = "reviewers";
        g = scim.createGroup(g);
        create("ben", true);
        ScimUser in = new ScimUser();
        in.userName = "cara";
        in.groups = new ArrayList<>(List.of(new ScimUser.Member(g.id, "reviewers")));
        ScimUser created = scim.createUser(in);
        assertTrue(rolesOf(users.findByUsername("cara")).contains("reviewers"));
        assertEquals(1, created.groups.size());
    }

    @Test
    public void unknownGroupOnCreateIsWarnedNotFatal() {
        ScimUser in = new ScimUser();
        in.userName = "dee";
        in.groups = new ArrayList<>(List.of(new ScimUser.Member("no-such-group", "ghost")));
        assertNotNull("the user must still be provisioned", scim.createUser(in).id);
    }

    @Test
    public void listGroupsFiltersByDisplayName() {
        ScimGroup a = new ScimGroup();
        a.displayName = "alpha";
        scim.createGroup(a);
        ScimGroup b = new ScimGroup();
        b.displayName = "beta";
        scim.createGroup(b);
        assertEquals(2, scim.listGroups(null, 1, 10).totalResults);
        assertEquals(1, scim.listGroups("displayName eq \"alpha\"", 1, 10).totalResults);
    }

    private static List<String> rolesOf(SaikuUser u) {
        return u.getRoles() == null ? List.of() : Arrays.asList(u.getRoles());
    }
}
