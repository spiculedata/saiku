/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection.encrypt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Resolves the per-install AES-256 key used to encrypt stored datasource passwords.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>If the {@code SAIKU_DS_ENCRYPTION_KEY} environment variable is set, its value is used as the
 *       key material (UTF-8 bytes, normalised to 32 bytes via SHA-256 if not already 32 bytes; a
 *       raw Base64-encoded 32-byte value is also accepted).
 *   <li>Otherwise a random 32-byte key is generated on first use and persisted to
 *       {@code <saiku.home>/conf/secret.key} (Base64). Subsequent calls read it back.
 * </ol>
 *
 * <p>The key file is created with best-effort owner-only permissions (POSIX 0600); on platforms
 * without POSIX (e.g. Windows) the permission tightening is skipped silently.
 *
 * <p>This class is intentionally self-contained within saiku-service and is loaded lazily so that
 * test code and callers that never touch encryption pay no cost.
 */
final class InstallKeyProvider {

    static final String ENV_KEY = "SAIKU_DS_ENCRYPTION_KEY";
    static final String KEY_FILE_NAME = "secret.key";
    static final String CONF_DIR_NAME = "conf";

    private static final int KEY_BYTES = 32; // AES-256
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKey cachedKey;

    private InstallKeyProvider() {}

    /**
     * Returns the raw per-install key bytes (32 bytes, AES-256). Callers that need to derive a
     * secondary key (e.g. an HMAC key for unsubscribe-token signing, saiku#1811 PR2) use this rather
     * than the {@link SecretKey} wrapper. Never log the returned bytes.
     */
    static byte[] getKeyBytes() {
        return getKey().getEncoded();
    }

    /** Returns the per-install AES key, resolving (and persisting, if needed) on first call. */
    static SecretKey getKey() {
        SecretKey local = cachedKey;
        if (local == null) {
            synchronized (InstallKeyProvider.class) {
                local = cachedKey;
                if (local == null) {
                    local = resolveKey();
                    cachedKey = local;
                }
            }
        }
        return local;
    }

    /** For tests: clears the cached key so the next {@link #getKey()} re-resolves. */
    static synchronized void resetForTesting() {
        cachedKey = null;
    }

    private static SecretKey resolveKey() {
        String env = System.getenv(ENV_KEY);
        if (env != null && !env.trim().isEmpty()) {
            return new SecretKeySpec(deriveKeyBytes(env.trim()), "AES");
        }
        return loadOrCreatePersistedKey();
    }

    /**
     * Accepts either a Base64-encoded 32-byte key or arbitrary text; non-32-byte material is hashed
     * to a stable 32-byte key with SHA-256.
     */
    private static byte[] deriveKeyBytes(String material) {
        try {
            byte[] decoded = Base64.getDecoder().decode(material);
            if (decoded.length == KEY_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException notBase64) {
            // fall through to hashing
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static SecretKey loadOrCreatePersistedKey() {
        Path keyFile = resolveKeyFilePath();
        try {
            if (Files.isReadable(keyFile)) {
                String existing =
                        new String(Files.readAllBytes(keyFile), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    byte[] decoded = Base64.getDecoder().decode(existing);
                    if (decoded.length == KEY_BYTES) {
                        return new SecretKeySpec(decoded, "AES");
                    }
                }
            }
        } catch (Exception readFailure) {
            // Could not read an existing key; fall through and create a fresh one.
        }

        byte[] fresh = new byte[KEY_BYTES];
        RANDOM.nextBytes(fresh);
        persistKey(keyFile, fresh);
        return new SecretKeySpec(fresh, "AES");
    }

    private static void persistKey(Path keyFile, byte[] keyBytes) {
        try {
            Path parent = keyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String encoded = Base64.getEncoder().encodeToString(keyBytes);
            Files.write(keyFile, encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tightenPermissions(keyFile);
        } catch (Exception persistFailure) {
            // Best-effort persistence. If we cannot write the key file (read-only home, etc.) the
            // in-memory key is still returned for this JVM lifetime; it just won't survive a restart.
        }
    }

    private static void tightenPermissions(Path keyFile) {
        try {
            Set<PosixFilePermission> perms =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyFile, perms);
        } catch (Exception nonPosixOrDenied) {
            // Windows / non-POSIX filesystems (UnsupportedOperationException) or denied; ignore.
        }
    }

    /** {@code <saiku.home>/conf/secret.key}, falling back to {@code java.io.tmpdir} if unset. */
    static Path resolveKeyFilePath() {
        String home = System.getProperty("saiku.home");
        if (home == null || home.trim().isEmpty()) {
            home = System.getProperty("java.io.tmpdir");
        }
        return Paths.get(home, CONF_DIR_NAME, KEY_FILE_NAME);
    }
}
