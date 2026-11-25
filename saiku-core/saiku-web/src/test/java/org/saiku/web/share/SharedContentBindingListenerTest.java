/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;

/**
 * Coverage of the saiku#2536 repository save hook: a save by the minter re-arms
 * the minted content hash (links keep working); a save by anyone else poisons
 * the record (the guest read is refused until re-minted).
 */
public class SharedContentBindingListenerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PATH = "/homes/alice/F/d.saikudash";
    private static final String ORIGINAL = "{\"v\":1}";
    private static final String EDITED = "{\"v\":2}";

    private ShareTokenStore shares;
    private EmbedTokenStore embeds;
    private EmbedPublicRegistry grants;
    private SharedContentBindingListener listener;

    @Before
    public void setUp() throws Exception {
        shares = new ShareTokenStore(tmp.newFolder("share").getAbsolutePath());
        embeds = new EmbedTokenStore(tmp.newFolder("embeds").getAbsolutePath());
        grants = new EmbedPublicRegistry(tmp.newFolder("public").getAbsolutePath());
        listener = new SharedContentBindingListener();
        listener.setShareTokenStore(shares);
        listener.setEmbedTokenStore(embeds);
        listener.setEmbedPublicRegistry(grants);
    }

    private ShareToken mintShare(String content) {
        return shares.create(
                PATH, "alice", java.util.List.of("ROLE_USER"), 3600_000L, null, ContentBinding.sha256Hex(content));
    }

    private EmbedToken mintEmbed(String kind, String path, String content) {
        return embeds.create(
                kind,
                path,
                "alice",
                java.util.List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                content == null ? null : ContentBinding.sha256Hex(content));
    }

    /* ------------------------ share tokens ------------------------ */

    @Test
    public void minter_save_rearms_share_binding() {
        ShareToken t = mintShare(ORIGINAL);
        listener.onFileSaved(PATH, EDITED, "alice");
        ShareToken after = shares.load(t.token);
        assertEquals(ContentBinding.sha256Hex(EDITED), after.contentSha256);
        assertFalse(after.contentChangedByOther);
    }

    @Test
    public void collaborator_save_poisons_share_record() {
        ShareToken t = mintShare(ORIGINAL);
        listener.onFileSaved(PATH, EDITED, "bob");
        assertTrue(shares.load(t.token).contentChangedByOther);
    }

    @Test
    public void collaborator_save_refuses_even_after_bytes_are_restored() {
        ShareToken t = mintShare(ORIGINAL);
        listener.onFileSaved(PATH, EDITED, "bob");
        listener.onFileSaved(PATH, ORIGINAL, "bob");
        assertTrue(shares.load(t.token).contentChangedByOther);
        // And the minter writing again clears the poison.
        listener.onFileSaved(PATH, EDITED, "alice");
        assertFalse(shares.load(t.token).contentChangedByOther);
    }

    @Test
    public void other_paths_leave_the_record_alone() {
        ShareToken t = mintShare(ORIGINAL);
        listener.onFileSaved("/homes/bob/other.saikudash", EDITED, "bob");
        assertFalse(shares.load(t.token).contentChangedByOther);
        assertEquals(ContentBinding.sha256Hex(ORIGINAL), shares.load(t.token).contentSha256);
    }

    /* ----------------- legacy (unbound) records ------------------- */

    @Test
    public void minter_save_binds_a_legacy_record() {
        ShareToken t = shares.create(PATH, "alice", java.util.List.of("ROLE_USER"), 3600_000L, null, null);
        listener.onFileSaved(PATH, EDITED, "alice");
        assertEquals(ContentBinding.sha256Hex(EDITED), shares.load(t.token).contentSha256);
        assertFalse(shares.load(t.token).contentChangedByOther);
    }

    @Test
    public void collaborator_save_poisons_a_legacy_record() {
        ShareToken t = shares.create(PATH, "alice", java.util.List.of("ROLE_USER"), 3600_000L, null, null);
        listener.onFileSaved(PATH, EDITED, "bob");
        assertTrue(shares.load(t.token).contentChangedByOther);
    }

    /* ------------------------ embed tokens ------------------------ */

    @Test
    public void collaborator_save_poisons_embed_token() {
        EmbedToken t = mintEmbed("query", "/homes/alice/q.saiku", ORIGINAL);
        listener.onFileSaved("/homes/alice/q.saiku", EDITED, "bob");
        assertTrue(embeds.load(t.token).contentChangedByOther);
    }

    @Test
    public void minter_save_rearms_embed_token() {
        EmbedToken t = mintEmbed("dashboard", "/homes/alice/d.saikudash", ORIGINAL);
        listener.onFileSaved("/homes/alice/d.saikudash", EDITED, "alice");
        assertEquals(ContentBinding.sha256Hex(EDITED), embeds.load(t.token).contentSha256);
    }

    @Test
    public void cube_pinned_kinds_are_never_poisoned() {
        // An authoring token pins a cube, not a file — a file save can never
        // poison it, wherever the save lands.
        EmbedToken t = embeds.create(
                "authoring",
                "conn/cat/schema/cube",
                "alice",
                java.util.List.of("ROLE_ADMIN"),
                3600_000L,
                null,
                null,
                "tenant-1",
                null);
        listener.onFileSaved("conn/cat/schema/cube", EDITED, "bob");
        assertFalse(embeds.load(t.token).contentChangedByOther);
    }

    /* ------------------------ public grants ----------------------- */

    @Test
    public void collaborator_save_poisons_public_grant() {
        grants.grant(
                "query",
                "/homes/alice/q.saiku",
                "alice",
                java.util.List.of("ROLE_USER"),
                null,
                ContentBinding.sha256Hex(ORIGINAL));
        listener.onFileSaved("/homes/alice/q.saiku", EDITED, "bob");
        assertTrue(grants.lookup("query", "/homes/alice/q.saiku").contentChangedByOther);
    }

    @Test
    public void grantor_save_rearms_public_grant() {
        grants.grant(
                "dashboard",
                "/homes/alice/d.saikudash",
                "alice",
                java.util.List.of("ROLE_USER"),
                null,
                ContentBinding.sha256Hex(ORIGINAL));
        listener.onFileSaved("/homes/alice/d.saikudash", EDITED, "alice");
        assertEquals(
                ContentBinding.sha256Hex(EDITED), grants.lookup("dashboard", "/homes/alice/d.saikudash").contentSha256);
    }

    /* --------------------------- edges --------------------------- */

    @Test
    public void null_content_save_is_ignored() {
        ShareToken t = mintShare(ORIGINAL);
        // Folder creates carry null content — nothing bound may be poisoned.
        listener.onFileSaved(PATH, null, "bob");
        assertFalse(shares.load(t.token).contentChangedByOther);
    }
}
