package org.saiku.datasources.connection.encrypt;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM authenticated encryption for stored datasource passwords.
 *
 * <p>Wire format of a {@code v2} value: {@code "v2:" + base64( IV(12 bytes) || GCM ciphertext+tag )}.
 * The key is the per-install key from {@link InstallKeyProvider}; a fresh random 12-byte IV is
 * generated per encryption and prepended so it never repeats with the same key.
 *
 * <p>This is the encrypt path for all new saves. The legacy static-3DES path remains available as a
 * decrypt-only fallback in {@link CryptoUtil} so existing {@code .sds} files keep working.
 */
final class AesGcmPasswordEncoder {

    static final String VERSION_PREFIX = "v2:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmPasswordEncoder() {}

    /** Encrypts plaintext to a {@code v2:}-prefixed, Base64-encoded value. */
    static String encrypt(String plaintext) {
        try {
            SecretKey key = InstallKeyProvider.getKey();
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encryption failed", e);
        }
    }

    /** True if the given value carries the {@code v2:} version prefix. */
    static boolean isV2(String value) {
        return value != null && value.startsWith(VERSION_PREFIX);
    }

    /** Decrypts a {@code v2:}-prefixed value produced by {@link #encrypt(String)}. */
    static String decrypt(String value) {
        try {
            String body = value.substring(VERSION_PREFIX.length());
            byte[] all = Base64.getDecoder().decode(body);
            if (all.length < IV_BYTES) {
                throw new IllegalArgumentException("v2 ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(all, IV_BYTES, all.length);

            SecretKey key = InstallKeyProvider.getKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decryption failed", e);
        }
    }
}
