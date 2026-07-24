/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.saiku.datasources.connection.encrypt.CryptoUtil;

/**
 * Round-trip property for datasource password encryption: decrypting an encrypted value must
 * return the original, for every string. This is the classic property-testing sweet spot — an
 * inverse-pair invariant that hand-picked examples rarely probe at the edges (empty strings,
 * multi-byte UTF-8, long inputs).
 */
class CryptoUtilPropertyTest {

    @HegelTest
    void decryptUndoesEncrypt(TestCase tc) {
        String secret = tc.draw(text(), "secret");

        String roundTripped = CryptoUtil.decrypt(CryptoUtil.encrypt(secret));

        assertEquals(secret, roundTripped);
    }

    /** Every new save uses the authenticated AES-GCM v2 format (never the legacy 3DES path). */
    @HegelTest
    void newEncryptionsAreV2Format(TestCase tc) {
        String secret = tc.draw(text(), "secret");
        tc.assume(!secret.isEmpty()); // empty/null pass through unencrypted by contract

        assertTrue(
                CryptoUtil.encrypt(secret).startsWith("v2:"),
                "a non-empty secret must encrypt to the v2 AES-GCM format");
    }

    /**
     * AES-GCM uses a fresh random nonce per encryption, so encrypting the same secret twice yields
     * different ciphertext — yet both still decrypt back to the original. Guards against an
     * accidental slide into deterministic (nonce-reuse) encryption.
     */
    @HegelTest
    void encryptionIsNonDeterministicYetReversible(TestCase tc) {
        String secret = tc.draw(text(), "secret");
        tc.assume(!secret.isEmpty());

        String first = CryptoUtil.encrypt(secret);
        String second = CryptoUtil.encrypt(secret);

        assertNotEquals(first, second, "same secret must not produce identical ciphertext (nonce reuse)");
        assertEquals(secret, CryptoUtil.decrypt(first));
        assertEquals(secret, CryptoUtil.decrypt(second));
    }
}
