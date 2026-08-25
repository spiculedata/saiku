/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection.encrypt;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESedeKeySpec;

/**
 * Encryption of datasource passwords at rest.
 *
 * <p>New values are AES-256-GCM under the per-install key (see {@link AesGcmPasswordEncoder} and
 * {@link InstallKeyProvider}). The legacy two-key 3DES path below is <strong>decrypt-only in
 * practice</strong> and exists purely so {@code .sds} files written before the per-install-key
 * hardening still load; those values are transparently upgraded to {@code v2} on the next save.
 */
public class CryptoUtil {

    /**
     * The historical compiled-in 3DES key halves. These are NOT a secret — they shipped identically
     * in every install, which is precisely why the format was replaced. They are retained only to
     * read pre-existing ciphertext.
     */
    private static final byte[] LEGACY_KEY_1 = {
        (byte) 0x93, (byte) 0xa9, (byte) 0x0f, (byte) 0xb4, (byte) 0x57, (byte) 0x11, (byte) 0x8d, (byte) 0x2c
    };

    private static final byte[] LEGACY_KEY_2 = {
        (byte) 0x75, (byte) 0x2c, (byte) 0xf4, (byte) 0x5c, (byte) 0x75, (byte) 0x15, (byte) 0xc6, (byte) 0xa3
    };

    /** 3DES operates on 64-bit blocks; the legacy format pads plaintext up to a multiple of this. */
    private static final int DES_BLOCK_BYTES = 8;

    private CryptoUtil() {
        // static utility
    }

    /**
     * Encrypts a datasource password for storage.
     *
     * <p>As of the per-install-key hardening this emits an AES-256-GCM value with a {@code v2:}
     * version prefix, keyed by the per-install key (see {@link InstallKeyProvider}). The previous
     * implementation used a hard-coded, compiled-in 3DES key identical across every install, which
     * meant anyone with a {@code .sds} file could decrypt every stored password offline. Existing
     * legacy values are still readable via {@link #decrypt(String)}'s fallback and are migrated to
     * {@code v2} on the next save.
     */
    public static String encrypt(String text) {
        if (text == null || text.length() == 0) {
            return text;
        }
        return AesGcmPasswordEncoder.encrypt(text);
    }

    /**
     * Decrypts a stored datasource password.
     *
     * <p>If the value carries the {@code v2:} prefix it is AES-256-GCM decrypted with the per-install
     * key. Otherwise it is treated as a legacy static-3DES value and decrypted with the historical
     * hard-coded key — this is the backward-compatibility path so every {@code .sds} written before
     * this change still loads. Legacy values are transparently upgraded to {@code v2} the next time
     * the datasource is saved (which re-runs {@link #encrypt(String)}).
     */
    public static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.length() == 0) {
            return encrypted;
        }
        if (AesGcmPasswordEncoder.isV2(encrypted)) {
            return AesGcmPasswordEncoder.decrypt(encrypted);
        }
        return legacyDecrypt(encrypted);
    }

    /**
     * Legacy static-3DES encrypt. Retained only so tests and migration fixtures can reproduce
     * historical ciphertext; <strong>do not</strong> use for new saves — it is keyed by a compiled-in
     * static key and is therefore insecure. New saves go through {@link #encrypt(String)}.
     */
    static String legacyEncrypt(String text) {
        if (text.length() == 0) {
            return text;
        }
        byte[] source = text.getBytes(StandardCharsets.UTF_8);

        // The historical format always encrypts at least one trailing NUL, which is what lets
        // legacyDecrypt find the end of the plaintext without storing a length.
        int length = paddedLength(source.length + 1);
        byte[] padded = new byte[length];
        System.arraycopy(source, 0, padded, 0, source.length);

        return toHex(legacyCrypt(Cipher.ENCRYPT_MODE, padded));
    }

    /** Legacy static-3DES decrypt-only fallback for pre-existing stored passwords. */
    static String legacyDecrypt(String encrypted) {
        if (encrypted.length() == 0) {
            return encrypted;
        }
        if (encrypted.length() % 2 != 0) {
            throw new IllegalArgumentException("Cannot decrypt the input length has to be multiple of 2:" + encrypted);
        }

        byte[] plain = legacyCrypt(Cipher.DECRYPT_MODE, fromHex(encrypted));

        // Trim the NUL padding: the plaintext ends at the last non-zero byte.
        int finalLen = 0;
        for (int i = 0; i < plain.length; i++) {
            if (plain[i] != 0) {
                finalLen = i + 1;
            }
        }
        return new String(plain, 0, finalLen, StandardCharsets.UTF_8);
    }

    /**
     * Exposes the raw per-install key bytes (32 bytes, AES-256) for callers that need to derive a
     * SEPARATE secondary key — currently only the unsubscribe-token HMAC signer (saiku#1811 PR2). This
     * is the SAME per-install key that {@link #encrypt(String)} uses (see {@link InstallKeyProvider}),
     * so no new key mechanism is invented; the token signer domain-separates it with an HMAC label
     * before use. Never log the returned bytes.
     */
    public static byte[] installKeyMaterial() {
        return InstallKeyProvider.getKeyBytes();
    }

    /**
     * Two-key 3DES (EDE, K1-K2-K1) in ECB with no padding — the exact transform the historical
     * hand-rolled implementation performed, so ciphertext stays byte-compatible. Pinned by the golden
     * vectors in {@code DatasourcePasswordEncryptionTest}.
     */
    private static byte[] legacyCrypt(int mode, byte[] input) {
        try {
            byte[] keyBytes = new byte[24];
            System.arraycopy(LEGACY_KEY_1, 0, keyBytes, 0, 8);
            System.arraycopy(LEGACY_KEY_2, 0, keyBytes, 8, 8);
            System.arraycopy(LEGACY_KEY_1, 0, keyBytes, 16, 8);

            SecretKey key = SecretKeyFactory.getInstance("DESede").generateSecret(new DESedeKeySpec(keyBytes));
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(mode, key);
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new RuntimeException("Legacy 3DES password transform failed", e);
        }
    }

    /** Rounds up to the next whole 3DES block. */
    private static int paddedLength(int length) {
        if ((length % DES_BLOCK_BYTES) == 0) {
            return length;
        }
        return ((length / DES_BLOCK_BYTES) * DES_BLOCK_BYTES) + DES_BLOCK_BYTES;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int unsigned = b & 0xff;
            if (unsigned < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(unsigned));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
