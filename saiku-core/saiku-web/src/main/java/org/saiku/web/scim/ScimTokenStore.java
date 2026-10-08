/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed store for SCIM connector credentials (issue #1438), persisted as
 * {@code ${saiku.home}/scim-tokens/<sha256>.json}.
 *
 * <p>Security posture:
 * <ul>
 *   <li>The bearer secret is 256 bits of {@link SecureRandom}, Base64URL-encoded. Only its
 *       SHA-256 is persisted, so the on-disk store is not a set of usable credentials.</li>
 *   <li>Every lookup key is a 64-char lowercase hex digest produced by this class, and the
 *       resolved path is asserted to stay inside the token dir before any filesystem call — a
 *       presented bearer is never concatenated into a path, so a traversal attempt
 *       ({@code ../../etc/passwd}) cannot escape.</li>
 *   <li>Writes are atomic (temp file + {@code ATOMIC_MOVE}) so a concurrent read never sees a
 *       torn record during a mint/revoke race.</li>
 *   <li>With no {@code saiku.home} (unit tests) it falls back to an in-memory map, mirroring
 *       {@code ShareTokenStore}.</li>
 * </ul>
 */
public class ScimTokenStore {

    private static final Logger log = LoggerFactory.getLogger(ScimTokenStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;
    private final Map<String, ScimToken> memory = new ConcurrentHashMap<>();

    public ScimTokenStore() {
        this(System.getProperty("saiku.home"));
    }

    /** Visible for tests — pass an explicit home, or null for in-memory. */
    public ScimTokenStore(String saikuHome) {
        Path d = null;
        if (saikuHome != null && !saikuHome.isBlank()) {
            try {
                d = Paths.get(saikuHome).toAbsolutePath().normalize().resolve("scim-tokens");
                Files.createDirectories(d);
            } catch (IOException e) {
                log.warn("Could not create scim-tokens dir under {} — using in-memory store", saikuHome, e);
                d = null;
            }
        }
        this.dir = d;
    }

    /**
     * Mint a credential. The returned secret is shown to the operator once and never persisted;
     * {@link ScimToken#getId()} is the non-secret handle the admin list shows.
     */
    public MintedToken mint(String label, String idp, String createdBy) {
        String secret = generateSecret();
        ScimToken t = new ScimToken();
        t.id = hash(secret);
        t.label = label;
        t.idp = idp;
        t.createdBy = createdBy;
        t.createdAt = System.currentTimeMillis();
        t.lastUsedAt = 0L;
        t.revoked = false;
        persist(t);
        return new MintedToken(t, secret);
    }

    /** The token record matching {@code secret}, or null if unknown / revoked / malformed. */
    public ScimToken load(String secret) {
        String id = idFor(secret);
        if (id == null) {
            return null;
        }
        ScimToken t = read(id);
        return t != null && t.isValid() ? t : null;
    }

    /** Every token's metadata, newest first. Secrets are not recoverable from here. */
    public List<ScimToken> listAll() {
        List<ScimToken> out = new ArrayList<>();
        if (dir == null) {
            out.addAll(memory.values());
        } else {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                    try {
                        out.add(MAPPER.readValue(p.toFile(), ScimToken.class));
                    } catch (IOException e) {
                        log.warn("Skipping unreadable scim-token record {}", p, e);
                    }
                });
            } catch (IOException e) {
                log.warn("Could not list scim-tokens dir {}", dir, e);
            }
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    /** Revoke by non-secret handle. Idempotent; false when the handle is unknown. */
    public boolean revoke(String id) {
        return revokeLoaded(read(id));
    }

    /** Revoke by presented secret — what the admin UI has to hand when only the secret is known. */
    public boolean revokeBySecret(String secret) {
        String id = idFor(secret);
        return id != null && revokeLoaded(read(id));
    }

    /** Stamp last-use; best effort — an audit-only side effect must never fail a request. */
    public void touch(ScimToken t) {
        if (t == null) {
            return;
        }
        t.lastUsedAt = System.currentTimeMillis();
        try {
            persist(t);
        } catch (RuntimeException e) {
            log.debug("Could not persist scim-token last-use", e);
        }
    }

    private ScimToken read(String id) {
        if (dir == null) {
            return memory.get(id);
        }
        Path f = safeResolve(id);
        if (f == null || !Files.exists(f)) {
            return null;
        }
        try {
            return MAPPER.readValue(f.toFile(), ScimToken.class);
        } catch (IOException e) {
            log.warn("Unreadable scim-token record {}", f, e);
            return null;
        }
    }

    private boolean revokeLoaded(ScimToken t) {
        if (t == null) {
            return false;
        }
        t.revoked = true;
        persist(t);
        return true;
    }

    private void persist(ScimToken t) {
        if (dir == null) {
            memory.put(t.id, t);
            return;
        }
        Path target = safeResolve(t.id);
        if (target == null) {
            throw new IllegalStateException("Refusing to persist scim token with unsafe id");
        }
        try {
            Path tmp = dir.resolve(t.id + ".json.tmp");
            MAPPER.writeValue(tmp.toFile(), t);
            restrictPermissions(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(dir.resolve(t.id + ".json.tmp"), target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new RuntimeException("Failed to persist scim token", e2);
            }
        }
    }

    /** Resolve {@code <id>.json} strictly within {@link #dir}; null if it escapes. */
    private Path safeResolve(String id) {
        if (!isValidId(id)) {
            return null;
        }
        Path resolved = dir.resolve(id + ".json").normalize();
        if (!resolved.startsWith(dir)) {
            log.warn("Refusing scim-token path that escapes the store dir");
            return null;
        }
        return resolved;
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("^[a-f0-9]{64}$");
    }

    /** sha256(secret) hex, or null for a blank/implausibly long presented secret. */
    private static String idFor(String secret) {
        if (secret == null || secret.isBlank() || secret.length() > 512) {
            return null;
        }
        return hash(secret.trim());
    }

    private static String hash(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String generateSecret() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(
                    file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX (Windows) — best effort; the file sits under saiku-home.
        }
    }

    /** The mint result: metadata plus the one-time plaintext secret. */
    public static final class MintedToken {
        public final ScimToken token;
        public final String secret;

        MintedToken(ScimToken token, String secret) {
            this.token = token;
            this.secret = secret;
        }
    }
}
