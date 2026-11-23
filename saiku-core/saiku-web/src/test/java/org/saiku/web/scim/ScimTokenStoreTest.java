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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#1438 — SCIM connector credential store.
 *
 * <p>The two properties that matter are asserted here rather than assumed: a presented bearer is
 * never concatenated into a filesystem path (so a traversal payload cannot escape the store), and
 * the plaintext secret is never written to disk (so a leaked {@code saiku-home} does not yield
 * usable credentials).
 */
public class ScimTokenStoreTest {

    @Rule
    public TemporaryFolder home = new TemporaryFolder();

    @Test
    public void mintThenLoad() {
        ScimTokenStore store = new ScimTokenStore(null);
        ScimTokenStore.MintedToken minted = store.mint("Okta production", "Okta", "admin");
        assertNotNull(minted.secret);
        assertTrue(minted.secret.length() >= 32);

        ScimToken loaded = store.load(minted.secret);
        assertNotNull(loaded);
        assertEquals("Okta production", loaded.label);
        assertEquals("Okta", loaded.idp);
        assertEquals("admin", loaded.createdBy);
        assertTrue(loaded.isValid());
    }

    @Test
    public void secretsAreUnique() {
        ScimTokenStore store = new ScimTokenStore(null);
        assertFalse(store.mint("a", "b", "c").secret.equals(store.mint("a", "b", "c").secret));
    }

    @Test
    public void unknownOrBlankSecretIsNull() {
        ScimTokenStore store = new ScimTokenStore(null);
        assertNull(store.load("nope"));
        assertNull(store.load(""));
        assertNull(store.load("   "));
        assertNull(store.load(null));
    }

    @Test
    public void revokedTokenStopsAuthenticating() {
        ScimTokenStore store = new ScimTokenStore(null);
        ScimTokenStore.MintedToken minted = store.mint("Okta", "Okta", "admin");
        assertNotNull(store.load(minted.secret));
        assertTrue(store.revoke(minted.token.id));
        assertNull("a revoked bearer must authenticate nothing", store.load(minted.secret));
    }

    @Test
    public void revokeIsIdempotentAndUnknownIsFalse() {
        ScimTokenStore store = new ScimTokenStore(null);
        ScimTokenStore.MintedToken minted = store.mint("Okta", "Okta", "admin");
        assertTrue(store.revoke(minted.token.id));
        assertTrue(store.revoke(minted.token.id));
        assertFalse(store.revoke("not-a-handle"));
    }

    @Test
    public void revokeBySecretWorks() {
        ScimTokenStore store = new ScimTokenStore(null);
        ScimTokenStore.MintedToken minted = store.mint("Okta", "Okta", "admin");
        assertTrue(store.revokeBySecret(minted.secret));
        assertNull(store.load(minted.secret));
    }

    @Test
    public void listExposesMetadataNeverSecrets() throws IOException {
        ScimTokenStore store = new ScimTokenStore(home.getRoot().getAbsolutePath());
        ScimTokenStore.MintedToken minted = store.mint("Entra", "Microsoft Entra ID", "admin");
        List<ScimToken> all = store.listAll();
        assertEquals(1, all.size());
        assertEquals("Entra", all.get(0).label);

        // Nothing on disk may contain the plaintext bearer.
        Path dir = home.getRoot().toPath().resolve("scim-tokens");
        try (var files = Files.list(dir)) {
            for (Path p : files.collect(Collectors.toList())) {
                String body = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                assertFalse("the secret must never be persisted: " + p, body.contains(minted.secret));
            }
        }
    }

    @Test
    public void onDiskStoreSurvivesANewInstance() throws IOException {
        String path = home.getRoot().getAbsolutePath();
        ScimTokenStore first = new ScimTokenStore(path);
        ScimTokenStore.MintedToken minted = first.mint("Okta", "Okta", "admin");
        ScimTokenStore second = new ScimTokenStore(path);
        assertNotNull("a restarted server must still accept the bearer", second.load(minted.secret));
    }

    @Test
    public void traversalPayloadIsRejectedAsABearer() throws IOException {
        ScimTokenStore store = new ScimTokenStore(home.getRoot().getAbsolutePath());
        store.mint("Okta", "Okta", "admin");
        for (String evil : new String[] {"../../etc/passwd", "..%2f..%2fetc", "/etc/passwd", "a/../../b"}) {
            assertNull("must not resolve: " + evil, store.load(evil));
        }
        // And nothing escaped the store directory.
        assertFalse(Files.exists(home.getRoot().toPath().getParent().resolve("etc")));
    }

    @Test
    public void revokeRejectsAHandleThatIsNotADigest() throws IOException {
        ScimTokenStore store = new ScimTokenStore(home.getRoot().getAbsolutePath());
        store.mint("Okta", "Okta", "admin");
        try {
            assertFalse(store.revoke("../../../etc/shadow"));
        } catch (RuntimeException e) {
            fail("a crafted handle must be a miss, not an exception: " + e);
        }
    }

    @Test
    public void touchStampsLastUse() {
        ScimTokenStore store = new ScimTokenStore(null);
        ScimTokenStore.MintedToken minted = store.mint("Okta", "Okta", "admin");
        assertEquals(0L, minted.token.lastUsedAt);
        store.touch(store.load(minted.secret));
        assertTrue(store.load(minted.secret).lastUsedAt > 0);
    }
}
