/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection.encrypt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.saiku.service.security.SecretFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>The key file is written with owner-only permissions and atomically (create-0600 + move, see
 * {@link SecretFileStore}) — there is no window in which the key is readable by other accounts, and
 * on Windows an owner-only ACL is applied. A key that EXISTS but cannot be read, or that exists and
 * cannot be parsed, is fatal: silently rotating it would orphan every stored {@code v2:} password
 * with no trace. Likewise a key that cannot be persisted is a hard error, not a warning.
 *
 * <p>This class is intentionally self-contained within saiku-service and is loaded lazily so that
 * test code and callers that never touch encryption pay no cost.
 */
final class InstallKeyProvider {

    static final String ENV_KEY = "SAIKU_DS_ENCRYPTION_KEY";
    static final String KEY_FILE_NAME = "secret.key";
    static final String CONF_DIR_NAME = "conf";

    private static final Logger log = LoggerFactory.getLogger(InstallKeyProvider.class);

    private static final int KEY_BYTES = 32; // AES-256
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKey cachedKey;
    private static volatile boolean warnedAboutTmpdirFallback;

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
        boolean present = Files.exists(keyFile);
        if (present && !Files.isReadable(keyFile)) {
            throw new IllegalStateException(
                    "Install key '"
                            + keyFile
                            + "' exists but is not readable by this process. Booting anyway would mint a NEW key and make every stored datasource password unreadable. Fix the ownership/ACL of saiku.home (chown to the runtime user) or remove the file to start over.",
                    null);
        }
        if (present) {
            try {
                String existing =
                        new String(Files.readAllBytes(keyFile), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    byte[] decoded = Base64.getDecoder().decode(existing);
                    if (decoded.length == KEY_BYTES) {
                        return new SecretKeySpec(decoded, "AES");
                    }
                    throw new IllegalStateException("Install key '" + keyFile + "' is not a 32-byte Base64 AES key");
                }
                throw new IllegalStateException("Install key '" + keyFile + "' is empty");
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException(
                        "Install key '" + keyFile
                                + "' is not valid Base64. Refusing to overwrite it: booting with a new key would orphan every stored datasource password. Restore the key from backup, or delete the file to start over.",
                        malformed);
            } catch (java.io.IOException readFailure) {
                throw new IllegalStateException(
                        "Could not read the install key '" + keyFile + "': " + readFailure.getMessage(), readFailure);
            }
        }

        byte[] fresh = new byte[KEY_BYTES];
        RANDOM.nextBytes(fresh);
        persistKey(keyFile, fresh);
        return new SecretKeySpec(fresh, "AES");
    }

    /**
     * Persists the key atomically with 0600-from-creation permissions ({@link SecretFileStore}).
     *
     * <p>Failure here is FATAL and logged at ERROR: the alternative (keeping the in-memory key and
     * carrying on) means the next restart mints a different key, and every stored {@code v2:}
     * datasource password silently becomes undecryptable with no log line anywhere (#1919 18c).
     */
    private static void persistKey(Path keyFile, byte[] keyBytes) {
        try {
            String encoded = Base64.getEncoder().encodeToString(keyBytes);
            SecretFileStore.writeOwnerOnly(keyFile, encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception persistFailure) {
            log.error(
                    "Could not persist the install key to '{}'. Refusing to start: on the next restart a"
                            + " different key would be generated and every stored datasource password would"
                            + " become unreadable. Make saiku.home writable by the Saiku process.",
                    keyFile,
                    persistFailure);
            throw new IllegalStateException("Could not persist the install key to '" + keyFile + "'", persistFailure);
        }
    }

    /**
     * {@code <saiku.home>/conf/secret.key}. When {@code saiku.home} is unset the key lands in the
     * shared temp directory, where the file is 0600 but any local account could pre-create
     * {@code conf/secret.key} to pin a key it knows (CWE-377). That is not a reason to break a
     * working deployment on boot, so the fallback is kept and warned about loudly, once.
     */
    static Path resolveKeyFilePath() {
        String home = System.getProperty("saiku.home");
        if (home == null || home.trim().isEmpty()) {
            if (!warnedAboutTmpdirFallback) {
                warnedAboutTmpdirFallback = true;
                log.warn(
                        "saiku.home is not set — the install key will be persisted under java.io.tmpdir,"
                                + " a world-readable shared directory. Set saiku.home (or SAIKU_DS_ENCRYPTION_KEY) in production.");
            }
            home = System.getProperty("java.io.tmpdir");
        }
        return Paths.get(home, CONF_DIR_NAME, KEY_FILE_NAME);
    }
}
