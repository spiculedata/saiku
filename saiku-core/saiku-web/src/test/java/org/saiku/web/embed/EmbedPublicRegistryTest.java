/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit coverage for {@link EmbedPublicRegistry} — grant/lookup round-trip,
 * kind-isolation, revoke idempotency, persistence reload, owner-scoped
 * listing, in-memory fallback.
 */
public class EmbedPublicRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void grant_then_isPublic() throws Exception {
        EmbedPublicRegistry r = new EmbedPublicRegistry(tmp.newFolder("home").getAbsolutePath());
        r.grant("query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"), "public sales");
        assertTrue(r.isPublic("query", "/homes/admin/sales.saiku"));
        assertNotNull(r.lookup("query", "/homes/admin/sales.saiku"));
        assertEquals("admin", r.lookup("query", "/homes/admin/sales.saiku").grantedBy);
        assertEquals(List.of("ROLE_ADMIN"), r.lookup("query", "/homes/admin/sales.saiku").ownerRolesSnapshot);
    }

    @Test
    public void kinds_are_isolated() throws Exception {
        // A query grant must not satisfy a dashboard lookup at the same path —
        // the key includes the kind so a typo'd request can't cross-match.
        EmbedPublicRegistry r = new EmbedPublicRegistry(tmp.newFolder("home").getAbsolutePath());
        r.grant("query", "/shared/x.saikudash", "admin", List.of(), null);
        assertTrue(r.isPublic("query", "/shared/x.saikudash"));
        assertFalse(r.isPublic("dashboard", "/shared/x.saikudash"));
    }

    @Test
    public void lookup_handles_nulls() throws Exception {
        EmbedPublicRegistry r = new EmbedPublicRegistry(tmp.newFolder("home").getAbsolutePath());
        assertNull(r.lookup(null, "/x.saiku"));
        assertNull(r.lookup("query", null));
        assertFalse(r.isPublic(null, "/x.saiku"));
        assertFalse(r.isPublic("query", null));
    }

    @Test
    public void revoke_is_idempotent() throws Exception {
        EmbedPublicRegistry r = new EmbedPublicRegistry(tmp.newFolder("home").getAbsolutePath());
        r.grant("dashboard", "/exec.saikudash", "admin", List.of(), null);
        assertTrue(r.revoke("dashboard", "/exec.saikudash"));
        assertFalse(r.revoke("dashboard", "/exec.saikudash"));
        assertFalse(r.isPublic("dashboard", "/exec.saikudash"));
    }

    @Test
    public void grant_persists_across_reload() throws Exception {
        String home = tmp.newFolder("home").getAbsolutePath();
        EmbedPublicRegistry r1 = new EmbedPublicRegistry(home);
        r1.grant("query", "/q.saiku", "alice", List.of("ROLE_USER"), "marketing");

        EmbedPublicRegistry r2 = new EmbedPublicRegistry(home);
        assertTrue("a fresh registry over the same dir must replay the grant", r2.isPublic("query", "/q.saiku"));
        assertEquals("alice", r2.lookup("query", "/q.saiku").grantedBy);
    }

    @Test
    public void listByOwner_scopes_to_grantor() throws Exception {
        EmbedPublicRegistry r = new EmbedPublicRegistry(tmp.newFolder("home").getAbsolutePath());
        r.grant("query", "/a.saiku", "admin", List.of(), null);
        r.grant("dashboard", "/b.saikudash", "admin", List.of(), null);
        r.grant("query", "/c.saiku", "bob", List.of(), null);

        assertEquals(2, r.listByOwner("admin").size());
        assertEquals(1, r.listByOwner("bob").size());
        assertEquals(3, r.listAll().size());
    }

    /**
     * saiku#1907 F4 (CWE-178): grantor identity is compared case-insensitively — the account store
     * matches usernames case-insensitively, so a case-sensitive listByOwner would miss a caller's
     * own grants whenever their presented case differs from how the grant recorded {@code grantedBy}.
     * RED pre-fix (case-sensitive equals excludes the case-variant lookup).
     */
    @Test
    public void listByOwner_matches_case_insensitively() throws Exception {
        EmbedPublicRegistry r = new EmbedPublicRegistry(tmp.newFolder("home").getAbsolutePath());
        r.grant("query", "/a.saiku", "admin", List.of(), null);

        assertEquals(1, r.listByOwner("Admin").size());
        assertEquals(1, r.listByOwner("admin").size());
        assertTrue(
                "a different grantor must not match",
                r.listByOwner("someoneelse").isEmpty());
    }

    @Test
    public void in_memory_fallback_when_no_home() {
        EmbedPublicRegistry r = new EmbedPublicRegistry((String) null);
        r.grant("query", "/q.saiku", "admin", List.of(), null);
        assertTrue(r.isPublic("query", "/q.saiku"));
        assertTrue(r.revoke("query", "/q.saiku"));
        assertFalse(r.isPublic("query", "/q.saiku"));
    }

    @Test
    public void second_grant_overwrites_snapshot() throws Exception {
        // Useful when the owner's roles change and they want public reads to
        // pick up the new scope — re-granting must replace the snapshot
        // rather than keep stale data.
        EmbedPublicRegistry r = new EmbedPublicRegistry(tmp.newFolder("home").getAbsolutePath());
        r.grant("query", "/q.saiku", "admin", List.of("ROLE_OLD"), "v1");
        r.grant("query", "/q.saiku", "admin", List.of("ROLE_NEW"), "v2");

        assertEquals(List.of("ROLE_NEW"), r.lookup("query", "/q.saiku").ownerRolesSnapshot);
        assertEquals("v2", r.lookup("query", "/q.saiku").label);
        assertEquals(1, r.listAll().size());
    }
}
