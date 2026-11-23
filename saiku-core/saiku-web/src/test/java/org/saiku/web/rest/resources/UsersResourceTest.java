/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;

/**
 * saiku#1920 — the user directory is a mention picker, not a roster.
 *
 * <p>{@code GET /saiku/api/users} used to return every username to any authenticated
 * caller, which is a CWE-200 harvest primitive: the usernames feed credential
 * stuffing lists and phishing, and none of that is needed to autocomplete a mention.
 * The contract these tests lock is: a search prefix is mandatory, results are
 * filtered and capped, and disabled accounts are not offered.
 */
public class UsersResourceTest {

    private UsersResource resource;

    @Before
    public void setUp() {
        resource = new UsersResource();
        resource.setUserService(new StubUserService(
                List.of(user("alice", true), user("bob", true), user("carol", true), user("dormant", false))));
    }

    @Test
    public void no_query_is_rejected() {
        Response r = resource.list(null);
        assertEquals(400, r.getStatus());
    }

    @Test
    public void blank_query_is_rejected() {
        assertEquals(400, resource.list("").getStatus());
        assertEquals(400, resource.list("   ").getStatus());
    }

    @Test
    public void single_character_query_is_rejected() {
        // One character at a time would still walk the whole directory.
        assertEquals(400, resource.list("a").getStatus());
    }

    @Test
    public void matching_prefix_returns_only_matches() throws Exception {
        Response r = resource.list("al");
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<String> names = namesOf(r);
        assertEquals(List.of("alice"), names);
    }

    @Test
    public void match_is_case_insensitive() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> names = namesOf(resource.list("AL"));
        assertEquals(List.of("alice"), names);
    }

    @Test
    public void substring_match_works_not_just_prefix() throws Exception {
        // The mention picker matches substrings, so the server must too.
        @SuppressWarnings("unchecked")
        List<String> names = namesOf(resource.list("ob"));
        assertEquals(List.of("bob"), names);
    }

    @Test
    public void disabled_accounts_are_not_offered() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> names = namesOf(resource.list("dor"));
        assertTrue("a disabled account is not mentionable", names.isEmpty());
    }

    @Test
    public void results_are_capped() throws Exception {
        List<SaikuUser> many = new ArrayList<>();
        for (int i = 0; i < UsersResource.MAX_RESULTS * 3; i++) {
            many.add(user(String.format("user%03d", i), true));
        }
        UsersResource capped = new UsersResource();
        capped.setUserService(new StubUserService(many));

        @SuppressWarnings("unchecked")
        List<String> names = namesOf(capped.list("user"));
        assertEquals(UsersResource.MAX_RESULTS, names.size());
    }

    @Test
    public void missing_user_service_yields_an_empty_list_not_an_error() throws Exception {
        UsersResource unwired = new UsersResource();
        @SuppressWarnings("unchecked")
        List<String> names = namesOf(unwired.list("al"));
        assertTrue(names.isEmpty());
    }

    // ---- helpers -------------------------------------------------------

    /** Pull the usernames out of the serialised response the JAX-RS layer would write. */
    @SuppressWarnings("unchecked")
    private static List<String> namesOf(Response r) throws Exception {
        Object entity = r.getEntity();
        if (entity instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add(String.valueOf(((Map<String, String>) o).get("username")));
            }
            return out;
        }
        throw new AssertionError("expected a JSON array entity, got: " + entity);
    }

    private static SaikuUser user(String name, boolean enabled) {
        SaikuUser u = new SaikuUser();
        u.setUsername(name);
        u.setEnabled(enabled);
        return u;
    }

    /** Minimal UserService stand-in — only {@code getUsers()} is exercised. */
    private static final class StubUserService extends UserService {
        private final List<SaikuUser> users;

        StubUserService(List<SaikuUser> users) {
            this.users = users;
        }

        @Override
        public List<SaikuUser> getUsers() {
            return users;
        }
    }
}
