/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.web.embed.EmbedPublicGrant;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;

/** Reference-file writes must invalidate all three guest-read records. */
public class SharedContentReferenceStoreTest {

    private static final String DASH = "homes/alice/F/d.saikudash";
    private static final String QUERY = "homes/alice/F/q.saiku";
    private static final String DASH_RAW =
            "{\"layout\":{\"tiles\":[{\"query\":{\"kind\":\"reference\",\"path\":\"/homes/alice/F/q.saiku\"}}]}}";
    private static final Map<String, String> REFERENCES = Map.of(QUERY, ContentBinding.sha256Hex("old"));

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void share_reference_poison_survives_parent_save_until_owner_updates_reference() throws Exception {
        ShareTokenStore store = new ShareTokenStore(tmp.newFolder().getAbsolutePath());
        ShareToken token = store.create(
                DASH, "alice", List.of("ROLE_USER"), 1000, null, ContentBinding.sha256Hex(DASH_RAW), REFERENCES);

        store.onContentSaved("//homes//alice/F/q.saiku", ContentBinding.sha256Hex("bob"), "bob");
        assertTrue(store.load(token.token).referencesChangedByOther.contains(QUERY));
        store.onContentSaved(DASH, ContentBinding.sha256Hex(DASH_RAW), "alice", DASH_RAW, (path, record) -> "bob");
        assertTrue(store.load(token.token).referencesChangedByOther.contains(QUERY));
        store.onContentSaved(QUERY, ContentBinding.sha256Hex("owner"), "alice");
        assertTrue(store.load(token.token).referencesChangedByOther.isEmpty());
        assertEquals(
                ContentBinding.sha256Hex("owner"),
                store.load(token.token).referenceSha256.get(QUERY));
    }

    @Test
    public void embed_token_and_public_grant_track_referenced_query() throws Exception {
        EmbedTokenStore tokens = new EmbedTokenStore(tmp.newFolder().getAbsolutePath());
        EmbedPublicRegistry grants = new EmbedPublicRegistry(tmp.newFolder().getAbsolutePath());
        EmbedToken token = tokens.create(
                "dashboard",
                DASH,
                "alice",
                List.of("ROLE_USER"),
                1000,
                null,
                null,
                null,
                ContentBinding.sha256Hex(DASH_RAW),
                REFERENCES);
        EmbedPublicGrant grant = grants.grant(
                "dashboard", DASH, "alice", List.of("ROLE_USER"), null, ContentBinding.sha256Hex(DASH_RAW), REFERENCES);

        tokens.onContentSaved(QUERY, ContentBinding.sha256Hex("bob"), "bob");
        grants.onContentSaved("query", QUERY, ContentBinding.sha256Hex("bob"), "bob");
        assertTrue(tokens.load(token.token).referencesChangedByOther.contains(QUERY));
        assertTrue(grant.referencesChangedByOther.contains(QUERY));
        tokens.onContentSaved(QUERY, ContentBinding.sha256Hex("owner"), "alice");
        grants.onContentSaved("query", QUERY, ContentBinding.sha256Hex("owner"), "alice");
        assertFalse(tokens.load(token.token).referencesChangedByOther.contains(QUERY));
        assertFalse(grants.lookup("dashboard", DASH).referencesChangedByOther.contains(QUERY));
    }

    @Test
    public void minter_parent_save_binds_new_reference_before_guest_read() throws Exception {
        ShareTokenStore store = new ShareTokenStore(tmp.newFolder().getAbsolutePath());
        ShareToken token =
                store.create(DASH, "alice", List.of("ROLE_USER"), 1000, null, ContentBinding.sha256Hex("{}"), Map.of());

        store.onContentSaved(
                DASH, ContentBinding.sha256Hex(DASH_RAW), "alice", DASH_RAW, (path, record) -> "new query");

        assertEquals(Map.of(QUERY, ContentBinding.sha256Hex("new query")), store.load(token.token).referenceSha256);
    }

    @Test
    public void parent_save_preserves_existing_reference_hash_for_all_record_types() throws Exception {
        ShareTokenStore shares = new ShareTokenStore(tmp.newFolder().getAbsolutePath());
        EmbedTokenStore tokens = new EmbedTokenStore(tmp.newFolder().getAbsolutePath());
        EmbedPublicRegistry grants = new EmbedPublicRegistry(tmp.newFolder().getAbsolutePath());
        ShareToken share = shares.create(
                DASH, "alice", List.of("ROLE_USER"), 1000, null, ContentBinding.sha256Hex(DASH_RAW), REFERENCES);
        EmbedToken token = tokens.create(
                "dashboard",
                DASH,
                "alice",
                List.of("ROLE_USER"),
                1000,
                null,
                null,
                null,
                ContentBinding.sha256Hex(DASH_RAW),
                REFERENCES);
        grants.grant(
                "dashboard", DASH, "alice", List.of("ROLE_USER"), null, ContentBinding.sha256Hex(DASH_RAW), REFERENCES);

        shares.onContentSaved(DASH, ContentBinding.sha256Hex(DASH_RAW), "alice", DASH_RAW, (path, record) -> "bob");
        tokens.onContentSaved(DASH, ContentBinding.sha256Hex(DASH_RAW), "alice", DASH_RAW, (path, record) -> "bob");
        grants.onContentSaved(
                "dashboard", DASH, ContentBinding.sha256Hex(DASH_RAW), "alice", DASH_RAW, (path, record) -> "bob");

        assertEquals(REFERENCES, shares.load(share.token).referenceSha256);
        assertEquals(REFERENCES, tokens.load(token.token).referenceSha256);
        assertEquals(REFERENCES, grants.lookup("dashboard", DASH).referenceSha256);
    }

    @Test
    public void delete_or_move_of_bound_path_by_other_user_poisons_all_records() throws Exception {
        ShareTokenStore shares = new ShareTokenStore(tmp.newFolder().getAbsolutePath());
        EmbedTokenStore tokens = new EmbedTokenStore(tmp.newFolder().getAbsolutePath());
        EmbedPublicRegistry grants = new EmbedPublicRegistry(tmp.newFolder().getAbsolutePath());
        ShareToken share = shares.create(
                DASH, "alice", List.of("ROLE_USER"), 1000, null, ContentBinding.sha256Hex(DASH_RAW), REFERENCES);
        EmbedToken token = tokens.create(
                "dashboard",
                DASH,
                "alice",
                List.of("ROLE_USER"),
                1000,
                null,
                null,
                null,
                ContentBinding.sha256Hex(DASH_RAW),
                REFERENCES);
        grants.grant(
                "dashboard", DASH, "alice", List.of("ROLE_USER"), null, ContentBinding.sha256Hex(DASH_RAW), REFERENCES);
        SharedContentBindingListener listener = new SharedContentBindingListener();
        listener.setShareTokenStore(shares);
        listener.setEmbedTokenStore(tokens);
        listener.setEmbedPublicRegistry(grants);

        listener.onFileChangedWithoutSave("//homes//alice/F/q.saiku", "bob", null);

        assertTrue(shares.load(share.token).referencesChangedByOther.contains(QUERY));
        assertTrue(tokens.load(token.token).referencesChangedByOther.contains(QUERY));
        assertTrue(grants.lookup("dashboard", DASH).referencesChangedByOther.contains(QUERY));
    }
}
