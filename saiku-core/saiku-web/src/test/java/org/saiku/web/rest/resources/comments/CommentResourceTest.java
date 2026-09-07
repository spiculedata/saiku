/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.comments.Comment;
import org.saiku.service.comments.CommentService;
import org.saiku.service.user.UserService;
import org.saiku.web.service.SessionService;

/**
 * Resource-level coverage of {@link CommentResource#delete} — the F4 (saiku#1907, CWE-178)
 * author/owner-equality site for dashboard comments (one of the 8 sites across 7 files SEC
 * enumerated; this one had NO prior test coverage at all). Hand-rolled stubs in lieu of a mock
 * library (saiku-web has no Mockito).
 */
public class CommentResourceTest {

    private StubCommentService comments;
    private StubSessionService session;
    private StubUserService users;
    private CommentResource resource;

    @Before
    public void setUp() {
        comments = new StubCommentService();
        session = new StubSessionService();
        users = new StubUserService();
        resource = new CommentResource();
        resource.setCommentService(comments);
        resource.setSessionService(session);
        resource.setUserService(users);
    }

    @After
    public void tearDown() {
        // saiku#1752: don't bleed the seeded SecurityContext authorities into the next test.
        org.saiku.web.rest.resources.RoleTestSupport.clear();
    }

    @Test
    public void author_can_delete_own_comment() {
        session.username = "admin";
        Comment c = comments.seed("admin");

        Response r = resource.delete(c.id, "/homes/admin/exec.saikudash");
        assertEquals(200, r.getStatus());
        assertTrue(comments.deleted.contains(c.id));
    }

    /**
     * saiku#1907 F4 (CWE-178): the account store matches usernames case-insensitively, so a
     * comment's author must be honoured for a caller presenting the SAME account under a
     * different case. RED pre-fix (case-sensitive equals denies the author; 403).
     */
    @Test
    public void author_can_delete_own_comment_when_caller_case_differs() {
        Comment c = comments.seed("Admin"); // author recorded with a different case
        session.username = "admin"; // caller principal (canonical spelling)

        Response r = resource.delete(c.id, "/homes/admin/exec.saikudash");
        assertEquals(200, r.getStatus());
        assertTrue(comments.deleted.contains(c.id));
    }

    @Test
    public void admin_role_can_delete_others_comment() {
        Comment c = comments.seed("alice");
        session.username = "admin";
        session.roles = List.of("ROLE_ADMIN");
        users.adminRoles = List.of("ROLE_ADMIN");

        Response r = resource.delete(c.id, "/homes/alice/exec.saikudash");
        assertEquals(200, r.getStatus());
        assertTrue(comments.deleted.contains(c.id));
    }

    @Test
    public void unrelated_user_cannot_delete_comment() {
        Comment c = comments.seed("alice");
        session.username = "bob";
        session.roles = List.of("ROLE_USER");

        Response r = resource.delete(c.id, "/homes/alice/exec.saikudash");
        assertEquals(403, r.getStatus());
        assertFalse(comments.deleted.contains(c.id));
    }

    @Test
    public void delete_unknown_comment_is_404() {
        session.username = "admin";
        Response r = resource.delete("not-a-real-id", "/homes/admin/exec.saikudash");
        assertEquals(404, r.getStatus());
    }

    /* --------------------------- stubs ---------------------------- */

    private static class StubSessionService extends SessionService {
        String username;
        List<String> roles = List.of();

        @Override
        public Map<String, Object> getAllSessionObjects() {
            // saiku#1752: roles are read authoritatively from SecurityContextHolder, not this map.
            org.saiku.web.rest.resources.RoleTestSupport.authenticate(username, roles);
            Map<String, Object> m = new HashMap<>();
            m.put("username", username);
            return m;
        }
    }

    private static class StubUserService extends UserService {
        List<String> adminRoles = List.of();

        @Override
        public List<String> getAdminRoles() {
            return adminRoles;
        }
    }

    /** In-memory CommentService stand-in: canReadDashboard always true (the dashboard-read gate
     *  isn't what's under test here); findById/softDelete work off a small seeded map, keyed by
     *  the comment's own id — mirroring the real store's identity semantics closely enough for
     *  the resource-level author/owner check to be exercised faithfully. */
    private static class StubCommentService extends CommentService {
        private final Map<String, Comment> byId = new HashMap<>();
        final Set<String> deleted = new HashSet<>();

        Comment seed(String author) {
            Comment c = new Comment();
            c.id = UUID.randomUUID().toString();
            c.author = author;
            c.body = "hi";
            byId.put(c.id, c);
            return c;
        }

        @Override
        public boolean canReadDashboard(String dashboardPath, String user, List<String> roles) {
            return true;
        }

        @Override
        public Comment findById(String dashboardPath, String commentId) {
            return byId.get(commentId);
        }

        @Override
        public boolean softDelete(String dashboardPath, String commentId) {
            deleted.add(commentId);
            return true;
        }
    }
}
