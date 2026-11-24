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
}
