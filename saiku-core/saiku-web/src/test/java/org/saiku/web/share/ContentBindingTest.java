/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Unit coverage of the saiku#2536 content-binding helper. */
public class ContentBindingTest {

    @Test
    public void sha256_is_deterministic_and_lowercase_hex() {
        // Known SHA-256 vector.
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ContentBinding.sha256Hex("abc"));
        assertEquals(ContentBinding.sha256Hex("abc"), ContentBinding.sha256Hex("abc"));
        assertFalse(ContentBinding.sha256Hex("abc").equals(ContentBinding.sha256Hex("abcd")));
        assertTrue(ContentBinding.sha256Hex("x").matches("[0-9a-f]{64}"));
    }

    @Test
    public void sha256_null_content_binds_null() {
        assertNull(ContentBinding.sha256Hex(null));
    }

    @Test
    public void matches_requires_a_bound_hash_and_equal_content() {
        String sha = ContentBinding.sha256Hex("ORIGINAL");
        assertTrue(ContentBinding.matches(sha, "ORIGINAL"));
        assertFalse(ContentBinding.matches(sha, "TAMPERED"));
        assertFalse(ContentBinding.matches(null, "ORIGINAL")); // unbound legacy record
        assertFalse(ContentBinding.matches(sha, null));
    }

    @Test
    public void conflict_is_409_with_explicit_status() {
        Response r = ContentBinding.conflict();
        assertEquals(409, r.getStatus());
        assertEquals(ContentBinding.CONTENT_CHANGED, ((java.util.Map<?, ?>) r.getEntity()).get("status"));
    }

    @Test
    public void snapshot_binds_dashboard_and_app_reference_tiles() {
        String dash = "{\"layout\":{\"tiles\":[{\"query\":{\"kind\":\"reference\",\"path\":\"/F/q.saiku\"}}]}}";
        String app =
                "{\"pages\":[{\"grid\":{\"tiles\":[{\"query\":{\"kind\":\"reference\",\"path\":\"/F/q.saiku\"}}]}}]}";
        for (String kind : Set.of("dashboard", "app")) {
            String raw = "dashboard".equals(kind) ? dash : app;
            ContentBinding.Snapshot snapshot = ContentBinding.snapshot(kind, raw, path -> "query-v1");
            assertEquals(ContentBinding.sha256Hex(raw), snapshot.contentSha256());
            assertEquals(Map.of("F/q.saiku", ContentBinding.sha256Hex("query-v1")), snapshot.referenceSha256());
            assertTrue(ContentBinding.referencesMatch(snapshot.referenceSha256(), path -> "query-v1"));
            assertFalse(ContentBinding.referencesMatch(snapshot.referenceSha256(), path -> "query-v2"));
        }
    }

    @Test
    public void unreadable_reference_cannot_bind_and_path_forms_match() {
        String dash = "{\"layout\":{\"tiles\":[{\"query\":{\"kind\":\"reference\",\"path\":\"//F//q.saiku\"}}]}}";
        Map<String, String> hashes = ContentBinding.referenceHashes("dashboard", dash, path -> null);
        assertEquals(Map.of("F/q.saiku", ""), hashes);
        assertFalse(ContentBinding.referencesMatch(hashes, path -> ""));
        assertEquals("F/q.saiku", ContentBinding.normalizePath("//F//q.saiku"));
    }

    @Test
    public void parent_refresh_keeps_existing_hash_and_only_reads_new_references() {
        String dash = "{\"layout\":{\"tiles\":["
                + "{\"query\":{\"kind\":\"reference\",\"path\":\"/F/old.saiku\"}},"
                + "{\"query\":{\"kind\":\"reference\",\"path\":\"/F/new.saiku\"}}]}}";
        Map<String, String> refreshed = ContentBinding.refreshReferenceHashes(
                "dashboard", dash, Map.of("F/old.saiku", ContentBinding.sha256Hex("trusted")), path -> {
                    assertEquals("/F/new.saiku", path);
                    return "new query";
                });

        assertEquals(ContentBinding.sha256Hex("trusted"), refreshed.get("F/old.saiku"));
        assertEquals(ContentBinding.sha256Hex("new query"), refreshed.get("F/new.saiku"));
    }
}
