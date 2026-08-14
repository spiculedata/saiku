// *****************************************************************************
// %name: CryptoUtil1.java %
// Desc :
//
// Copyright (�) 1994-2006 All Rights Reserved, Unauthorized Duplication
// Prohibited
// Program Property of Embarcadero Technologies, Inc.
// *****************************************************************************
package org.saiku.datasources.connection.encrypt;

public class CryptoUtil {

    private static final byte defaultKey1[] = {
        (byte) 0x93, (byte) 0xa9, (byte) 0x0f, (byte) 0xb4, (byte) 0x57, (byte) 0x11, (byte) 0x8d, (byte) 0x2c
    };

    private static final byte defaultKey2[] = {
        (byte) 0x75, (byte) 0x2c, (byte) 0xf4, (byte) 0x5c, (byte) 0x75, (byte) 0x15, (byte) 0xc6, (byte) 0xa3
    };

    /**
     * Encrypts a datasource password for storage.
     *
     * <p>As of the per-install-key hardening this now emits an AES-256-GCM value with a {@code v2:}
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
     * Legacy static-3DES encrypt. Retained only so existing tests/tools and migration fixtures can
     * reproduce historical ciphertext; <strong>do not</strong> use for new saves — it is keyed by a
     * compiled-in static key and is therefore insecure. New saves go through {@link #encrypt(String)}.
     */
    static String legacyEncrypt(String text) {
        try {
            if (text.length() == 0) {
                return text;
            }

            // We encrypt the NUL to simplify decryption
            int length = text.getBytes("UTF8").length + 1;

            // Make the input string length a multiple of DES_BLOCK_BYTES bytes
            if ((length % Des.DES_BLOCK_BYTES) != 0)
                length = ((length / Des.DES_BLOCK_BYTES) * Des.DES_BLOCK_BYTES) + Des.DES_BLOCK_BYTES;
            byte[] source = text.getBytes("UTF8");
            byte[] digest = new byte[length];

            System.arraycopy(source, 0, digest, 0, source.length);

            // Initialize the encryption keys
            Des des = new Des();
            des.SetKey(true, defaultKey1, defaultKey2);

            // Encrypt the text
            des.Crypt(digest);

            // Convert the encrypted data to ASCII HEX
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < length; i++) {
                int temp = (int) digest[i] & 0x000000ff;
                if (temp < 16) sb.append('0');
                sb.append(Integer.toHexString(temp));
            }

            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    /** Legacy static-3DES decrypt-only fallback for pre-existing stored passwords. */
    static String legacyDecrypt(String encrypted) {
        try {
            if (encrypted.length() == 0) {
                return encrypted;
            }

            if (encrypted.length() % 2 != 0) {
                throw new IllegalArgumentException(
                        "Cannot decrypt the input length has to be multiple of 2:" + encrypted);
            }

            int len = encrypted.length() / 2;
            byte[] digest = new byte[len];

            for (int i = 0; i < digest.length; i++) {
                digest[i] = (byte) Integer.parseInt(encrypted.substring(i * 2, i * 2 + 2), 16);
            }

            // Initialize the encryption keys
            Des des = new Des();
            des.SetKey(false, defaultKey1, defaultKey2);

            // decrypt
            des.Crypt(digest);

            int finalLen = 0;
            for (int i = 0; i < digest.length; i++) {
                if (digest[i] != 0) {
                    finalLen = i + 1;
                }
            }

            return new String(digest, 0, finalLen, "UTF8");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    private static String encodePassword(String rawPass) {

        // The password may be empty. Not recommended, but.....
        if (rawPass.length() == 0) {
            return rawPass;
        }

        // We encrypt the NUL to simplify decryption
        int length = rawPass.length() + 1;

        // Make the input string length a multiple of DES_BLOCK_BYTES bytes
        if ((length % Des.DES_BLOCK_BYTES) != 0)
            length = ((length / Des.DES_BLOCK_BYTES) * Des.DES_BLOCK_BYTES) + Des.DES_BLOCK_BYTES;

        byte[] source = rawPass.getBytes();
        byte[] digest = new byte[length];

        System.arraycopy(source, 0, digest, 0, source.length);
        // Initialize the encryption keys
        Des des = new Des();
        des.SetKey(true, defaultKey1, defaultKey2);

        // Encrypt the password
        des.Crypt(digest);

        // Convert the encrypted data to ASCII HEX
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int temp = (int) digest[i] & 0x000000ff;
            if (temp < 16) sb.append('0');
            sb.append(Integer.toHexString(temp));
        }

        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    public static boolean isPasswordValid(String encPass, String rawPass, Object salt) {
        String pass1 = "" + encPass;
        String pass2 = encodePassword(rawPass);
        return pass1.equals(pass2);
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

    public static void main(String[] args) throws Exception {

        String db = "63cb6745f7b4324f";
        // String repo="af28dbf3c80e1557";
        System.out.println(CryptoUtil.decrypt(db));
        // System.out.println( CryptoUtil.decrypt( repo ));

    }
}
