/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
