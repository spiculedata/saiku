package org.saiku.web.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit coverage for {@link ShareTokenStore} (issue #941) — round-trip,
 * path-traversal rejection on the token id, expiry, owner-scoped listing,
 * atomic write hygiene, and revocation.
 */
public class ShareTokenStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ShareTokenStore newStore() throws Exception {
        return new ShareTokenStore(tmp.newFolder("home").getAbsolutePath());
    }

    @Test
    public void create_then_load_roundtrips() throws Exception {
        ShareTokenStore store = newStore();
        ShareToken t = store.create("/homes/admin/q.saikudash", "admin", List.of("ROLE_ADMIN"), 60_000L, "Q report");
        assertNotNull(t.token);
        assertTrue("token id must be URL-safe and long", t.token.matches("^[A-Za-z0-9_-]{16,64}$"));

        ShareToken got = store.load(t.token);
        assertNotNull("minted token must load back", got);
        assertEquals("/homes/admin/q.saikudash", got.dashboardPath);
        assertEquals("admin", got.createdBy);
        assertEquals(List.of("ROLE_ADMIN"), got.ownerRolesSnapshot);
        assertEquals("Q report", got.label);
        assertFalse(got.revoked);
    }

    @Test
    public void load_rejects_traversal_and_malformed_ids() throws Exception {
        ShareTokenStore store = newStore();
        for (String bad : new String[] {
            "../secret", "..\\secret", "a/b", "a\\b", "%2F%2Fetc", "with space", "", "short", "a.b", "../../etc/passwd"
        }) {
            assertNull("malformed/traversal id must not resolve: " + bad, store.load(bad));
        }
    }

    @Test
    public void expiry_boundary() throws Exception {
        ShareTokenStore store = newStore();
        ShareToken t = store.create("/d.saikudash", "admin", List.of(), 1_000L, null);
        long created = t.createdAt;
        assertTrue("valid before expiry", t.isValid(created + 500));
        assertFalse("expired at/after expiresAt", t.isValid(t.expiresAt));
        assertTrue("expired flag", t.isExpired(t.expiresAt + 1));
    }

    @Test
    public void listByOwner_scopes_to_creator() throws Exception {
        ShareTokenStore store = newStore();
        store.create("/a.saikudash", "admin", List.of(), 60_000L, null);
        store.create("/b.saikudash", "admin", List.of(), 60_000L, null);
        store.create("/c.saikudash", "bob", List.of(), 60_000L, null);

        List<ShareToken> adminTokens = store.listByOwner("admin");
        assertEquals(2, adminTokens.size());
        assertTrue(adminTokens.stream().allMatch(t -> "admin".equals(t.createdBy)));
        assertEquals(1, store.listByOwner("bob").size());
        assertEquals(3, store.listAll().size());
    }

    @Test
    public void revoke_marks_token_and_persists() throws Exception {
        ShareTokenStore store = newStore();
        ShareToken t = store.create("/d.saikudash", "admin", List.of(), 60_000L, null);
        assertTrue(store.revoke(t.token));
        assertTrue("revocation must persist", store.load(t.token).revoked);
        assertFalse("revoke of unknown id is false", store.revoke("doesnotexistbutvalidlen0001"));
    }

    @Test
    public void persist_leaves_no_tmp_files() throws Exception {
        File home = tmp.newFolder("home2");
        ShareTokenStore store = new ShareTokenStore(home.getAbsolutePath());
        store.create("/d.saikudash", "admin", List.of(), 60_000L, null);
        File dir = new File(home, "share-tokens");
        try (Stream<java.nio.file.Path> s = Files.list(dir.toPath())) {
            assertTrue("no .tmp left behind", s.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

    @Test
    public void in_memory_fallback_when_no_home() {
        ShareTokenStore store = new ShareTokenStore((String) null);
        ShareToken t = store.create("/d.saikudash", "admin", List.of(), 60_000L, null);
        assertNotNull(store.load(t.token));
        assertTrue(store.revoke(t.token));
        assertTrue(store.load(t.token).revoked);
    }
}
