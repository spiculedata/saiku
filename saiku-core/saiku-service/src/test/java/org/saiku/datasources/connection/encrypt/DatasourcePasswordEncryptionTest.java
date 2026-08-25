/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection.encrypt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the per-install-key hardening of stored datasource password encryption (audit-3).
 *
 * <ul>
 *   <li>AES-256-GCM round-trip via the public {@link CryptoUtil} API.
 *   <li>New saves emit a {@code v2:}-prefixed value.
 *   <li>A {@code v2:} value decrypts back to plaintext.
 *   <li>A legacy static-3DES value still decrypts via the backward-compatible fallback (constructed
 *       with the retained {@link CryptoUtil#legacyEncrypt}).
 *   <li>Per-install key generation + persistence works in a temp {@code saiku.home}.
 * </ul>
 *
 * <p>Per the task brief these use try/fail/catch rather than {@code assertThrows}.
 */
public class DatasourcePasswordEncryptionTest {

    private String savedSaikuHome;
    private Path tempHome;

    @Before
    public void setUp() throws Exception {
        savedSaikuHome = System.getProperty("saiku.home");
        tempHome = Files.createTempDirectory("saiku-enc-test-");
        System.setProperty("saiku.home", tempHome.toString());
        InstallKeyProvider.resetForTesting();
    }

    @After
    public void tearDown() throws Exception {
        InstallKeyProvider.resetForTesting();
        if (savedSaikuHome == null) {
            System.clearProperty("saiku.home");
        } else {
            System.setProperty("saiku.home", savedSaikuHome);
        }
        // Best-effort cleanup of the temp home tree.
        try {
            Files.walk(tempHome)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignore) {
                            // best effort
                        }
                    });
        } catch (Exception ignore) {
            // best effort
        }
    }

    @Test
    public void aesGcmRoundTripThroughPublicApi() {
        String plaintext = "s3cr3t-DB-passw0rd!";
        String encrypted = CryptoUtil.encrypt(plaintext);
        assertNotEquals("ciphertext must differ from plaintext", plaintext, encrypted);
        assertEquals("round-trip must recover plaintext", plaintext, CryptoUtil.decrypt(encrypted));
    }

    @Test
    public void newSavesUseV2Prefix() {
        String encrypted = CryptoUtil.encrypt("anything");
        assertTrue("new saves must be v2:-prefixed AES-GCM, got: " + encrypted, encrypted.startsWith("v2:"));
    }

    @Test
    public void v2ValueDecryptsBackToPlaintext() {
        String plaintext = "another-secret";
        String v2 = AesGcmPasswordEncoder.encrypt(plaintext);
        assertTrue(v2.startsWith("v2:"));
        assertEquals(plaintext, CryptoUtil.decrypt(v2));
    }

    @Test
    public void gcmIvIsRandomSoCiphertextDiffersPerEncrypt() {
        String plaintext = "repeatme";
        String a = CryptoUtil.encrypt(plaintext);
        String b = CryptoUtil.encrypt(plaintext);
        assertNotEquals("random IV must make repeated encryptions differ", a, b);
        assertEquals(plaintext, CryptoUtil.decrypt(a));
        assertEquals(plaintext, CryptoUtil.decrypt(b));
    }

    /**
     * Golden vectors pinning the legacy static-3DES wire format.
     *
     * <p>The other legacy test round-trips {@code legacyEncrypt} through {@code decrypt}, which would
     * still pass if BOTH sides changed algorithm together. These are hard-coded ciphertexts produced
     * by the original hand-rolled DES implementation, so they fail if the legacy format ever shifts —
     * which would silently lock every pre-v2 {@code .sds} file out of its own password.
     *
     * <p>{@code 63cb6745f7b4324f} is the sample value that lived in the historical
     * {@code CryptoUtil.main} debug harness.
     */
    @Test
    public void legacyCiphertextGoldenVectorsStillDecrypt() {
        assertEquals("password", CryptoUtil.legacyDecrypt("6b51c16f30bd7191255cc1984c2bd67b"));
        assertEquals("admin123", CryptoUtil.legacyDecrypt("149c68daef5b360f255cc1984c2bd67b"));
        assertEquals(
                "aVeryLongPassword_16", CryptoUtil.legacyDecrypt("c93724bafea237f872cf13ed0991252e8edf885dbcfd7d27"));
        assertEquals("deea", CryptoUtil.legacyDecrypt("63cb6745f7b4324f"));
    }

    /** The legacy encoder must keep emitting exactly the historical ciphertext, byte for byte. */
    @Test
    public void legacyEncryptStillProducesHistoricalCiphertext() {
        assertEquals("6b51c16f30bd7191255cc1984c2bd67b", CryptoUtil.legacyEncrypt("password"));
        assertEquals("149c68daef5b360f255cc1984c2bd67b", CryptoUtil.legacyEncrypt("admin123"));
        assertEquals(
                "c93724bafea237f872cf13ed0991252e8edf885dbcfd7d27", CryptoUtil.legacyEncrypt("aVeryLongPassword_16"));
    }

    /** Non-ASCII plaintext must survive the legacy path unchanged (UTF-8 in, UTF-8 out). */
    @Test
    public void legacyPathHandlesNonAsciiPlaintext() {
        assertEquals("911edbbfc664afa8", CryptoUtil.legacyEncrypt("\u00e9\u00fc\u00f1x"));
        assertEquals("\u00e9\u00fc\u00f1x", CryptoUtil.legacyDecrypt("911edbbfc664afa8"));
    }

    @Test
    public void legacyThreeDesValueStillDecryptsViaFallback() {
        String plaintext = "old-foodmart-pw";
        // Reproduce a historical .sds value: hex-encoded static-3DES, no version prefix.
        String legacy = CryptoUtil.legacyEncrypt(plaintext);
        assertFalse("legacy value must NOT carry the v2: prefix", legacy.startsWith("v2:"));
        // Public decrypt must transparently route non-v2 values to the legacy fallback.
        assertEquals("legacy stored password must still decrypt", plaintext, CryptoUtil.decrypt(legacy));
    }

    @Test
    public void emptyAndNullPassThrough() {
        assertEquals("", CryptoUtil.encrypt(""));
        assertEquals("", CryptoUtil.decrypt(""));
        assertEquals(null, CryptoUtil.encrypt(null));
        assertEquals(null, CryptoUtil.decrypt(null));
    }

    @Test
    public void keyIsGeneratedAndPersistedInSaikuHomeConf() {
        Path keyFile = tempHome.resolve("conf").resolve("secret.key");
        // Trigger key resolution (which generates + persists on first use).
        CryptoUtil.encrypt("force-key-materialisation");
        if (!Files.isRegularFile(keyFile)) {
            fail("expected per-install key persisted at " + keyFile);
        }
        try {
            String contents = new String(Files.readAllBytes(keyFile), java.nio.charset.StandardCharsets.UTF_8).trim();
            byte[] decoded = Base64.getDecoder().decode(contents);
            assertEquals("persisted key must be 32 bytes (AES-256)", 32, decoded.length);
        } catch (Exception e) {
            fail("persisted key file unreadable or not Base64: " + e);
        }
    }

    @Test
    public void persistedKeyIsStableAcrossResolves() {
        String plaintext = "stable-across-restart";
        String encrypted = CryptoUtil.encrypt(plaintext);
        // Simulate a JVM restart: drop the cached key; the persisted file must still decrypt.
        InstallKeyProvider.resetForTesting();
        assertEquals("key persisted to disk must decrypt prior ciphertext", plaintext, CryptoUtil.decrypt(encrypted));
    }
}
