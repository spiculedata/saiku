package org.saiku.web.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit coverage for {@link EmbedTokenStore} — round-trip per resourceKind,
 * traversal/malformed id rejection, expiry boundary, owner-scoped listing,
 * atomic write hygiene, revocation, and kind-validation at mint time.
 */
public class EmbedTokenStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbedTokenStore newStore() throws Exception {
        return new EmbedTokenStore(tmp.newFolder("home").getAbsolutePath());
    }

    @Test
    public void create_query_then_load_roundtrips() throws Exception {
        EmbedTokenStore store = newStore();
        EmbedToken t = store.create(
                "query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"), 60_000L, "Sales chart");
        assertNotNull(t.token);
        assertTrue("token id must be URL-safe and long", t.token.matches("^[A-Za-z0-9_-]{16,64}$"));

        EmbedToken got = store.load(t.token);
        assertNotNull("minted token must load back", got);
        assertEquals("query", got.resourceKind);
        assertEquals("/homes/admin/sales.saiku", got.resourcePath);
        assertEquals("admin", got.createdBy);
        assertEquals(List.of("ROLE_ADMIN"), got.ownerRolesSnapshot);
        assertEquals("Sales chart", got.label);
        assertFalse(got.revoked);
    }

    @Test
    public void create_dashboard_then_load_roundtrips() throws Exception {
        EmbedTokenStore store = newStore();
        EmbedToken t = store.create("dashboard", "/homes/admin/exec.saikudash", "admin", List.of(), 60_000L, null);

        EmbedToken got = store.load(t.token);
        assertNotNull(got);
        assertEquals("dashboard", got.resourceKind);
        assertEquals("/homes/admin/exec.saikudash", got.resourcePath);
    }

    @Test
    public void create_app_then_load_roundtrips() throws Exception {
        // App Builder Phase 2 — minting an "app" embed token must succeed
        // (the view side already understands kind="app").
        EmbedTokenStore store = newStore();
        EmbedToken t = store.create("app", "/homes/admin/sales.saikuapp", "admin", List.of(), 60_000L, null);

        EmbedToken got = store.load(t.token);
        assertNotNull("minted app token must load back", got);
        assertEquals("app", got.resourceKind);
        assertEquals("/homes/admin/sales.saikuapp", got.resourcePath);
    }

    @Test
    public void create_rejects_unknown_resource_kind() throws Exception {
        EmbedTokenStore store = newStore();
        for (String bad : new String[] {"schema", "", null}) {
            try {
                store.create(bad, "/x.saiku", "admin", List.of(), 60_000L, null);
                fail("expected IllegalArgumentException for kind: " + bad);
            } catch (IllegalArgumentException expected) {
                /* ok */
            }
        }
    }

    @Test
    public void create_rejects_blank_resource_path() throws Exception {
        EmbedTokenStore store = newStore();
        for (String bad : new String[] {"", null}) {
            try {
                store.create("query", bad, "admin", List.of(), 60_000L, null);
                fail("expected IllegalArgumentException for path: " + bad);
            } catch (IllegalArgumentException expected) {
                /* ok */
            }
        }
    }

    @Test
    public void load_rejects_traversal_and_malformed_ids() throws Exception {
        EmbedTokenStore store = newStore();
        for (String bad : new String[] {
            "../secret", "..\\secret", "a/b", "a\\b", "%2F%2Fetc", "with space", "", "short", "a.b", "../../etc/passwd"
        }) {
            assertNull("malformed/traversal id must not resolve: " + bad, store.load(bad));
        }
    }

    @Test
    public void expiry_boundary() throws Exception {
        EmbedTokenStore store = newStore();
        EmbedToken t = store.create("query", "/q.saiku", "admin", List.of(), 1_000L, null);
        long created = t.createdAt;
        assertTrue("valid before expiry", t.isValid(created + 500));
        assertFalse("expired at/after expiresAt", t.isValid(t.expiresAt));
        assertTrue("expired flag", t.isExpired(t.expiresAt + 1));
    }

    @Test
    public void listByOwner_scopes_to_creator() throws Exception {
        EmbedTokenStore store = newStore();
        store.create("query", "/a.saiku", "admin", List.of(), 60_000L, null);
        store.create("dashboard", "/b.saikudash", "admin", List.of(), 60_000L, null);
        store.create("query", "/c.saiku", "bob", List.of(), 60_000L, null);

        List<EmbedToken> adminTokens = store.listByOwner("admin");
        assertEquals(2, adminTokens.size());
        assertTrue(adminTokens.stream().allMatch(t -> "admin".equals(t.createdBy)));
        assertEquals(1, store.listByOwner("bob").size());
        assertEquals(3, store.listAll().size());
    }

    @Test
    public void revoke_marks_token_and_persists() throws Exception {
        EmbedTokenStore store = newStore();
        EmbedToken t = store.create("query", "/q.saiku", "admin", List.of(), 60_000L, null);
        assertTrue(store.revoke(t.token));
        assertTrue("revocation must persist", store.load(t.token).revoked);
        assertFalse("revoke of unknown id is false", store.revoke("doesnotexistbutvalidlen0001"));
    }

    @Test
    public void persist_leaves_no_tmp_files() throws Exception {
        File home = tmp.newFolder("home2");
        EmbedTokenStore store = new EmbedTokenStore(home.getAbsolutePath());
        store.create("query", "/q.saiku", "admin", List.of(), 60_000L, null);
        File dir = new File(home, "embed-tokens");
        try (Stream<java.nio.file.Path> s = Files.list(dir.toPath())) {
            assertTrue("no .tmp left behind", s.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

    @Test
    public void in_memory_fallback_when_no_home() {
        EmbedTokenStore store = new EmbedTokenStore((String) null);
        EmbedToken t = store.create("query", "/q.saiku", "admin", List.of(), 60_000L, null);
        assertNotNull(store.load(t.token));
        assertTrue(store.revoke(t.token));
        assertTrue(store.load(t.token).revoked);
    }
}
