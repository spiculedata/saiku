/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed store for embed tokens (the {@code <saiku-embed>} Web Component
 * flow), persisted as {@code ${saiku.home}/embed-tokens/<token>.json}.
 *
 * <p>Security posture mirrors {@code ShareTokenStore} (saiku#941):
 * <ul>
 *   <li>Token ids are 256-bit {@link SecureRandom} values, Base64URL-encoded —
 *       unguessable; the id is the only secret and carries no claims.</li>
 *   <li>Every id is regex-validated <b>before</b> any filesystem use and the
 *       resolved path is asserted to stay within the token dir — a crafted id
 *       ({@code ../}, separators, encoded slashes) can never escape.</li>
 *   <li>Writes are atomic (temp file + {@code ATOMIC_MOVE}) so a concurrent
 *       read never sees a torn record during a mint/revoke race.</li>
 *   <li>When there is no {@code saiku.home} to persist to (unit tests), it
 *       falls back to an in-memory map.</li>
 * </ul>
 */
public class EmbedTokenStore {

    private static final Logger log = LoggerFactory.getLogger(EmbedTokenStore.class);

    /** URL-safe Base64 alphabet, min length guards against trivially short ids. */
    private static final Pattern TOKEN_ID = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Resource kinds the embed surface understands. The mint endpoint rejects
     *  anything outside this set so a typo can never persist a "ghost" kind
     *  that the view endpoint silently ignores. */
    private static final Set<String> ALLOWED_KINDS = Set.of("query", "dashboard", "app");

    /** Non-null when persisting to disk; null → use {@link #memory}. */
    private final Path dir;

    /** In-memory fallback for test/no-home runs. */
    private final Map<String, EmbedToken> memory = new ConcurrentHashMap<>();

    public EmbedTokenStore() {
        this(System.getProperty("saiku.home"));
    }

    /** Visible for tests — pass an explicit home (or null for in-memory). */
    public EmbedTokenStore(String saikuHome) {
        Path d = null;
        if (saikuHome != null && !saikuHome.isBlank()) {
            try {
                d = Paths.get(saikuHome).toAbsolutePath().normalize().resolve("embed-tokens");
                Files.createDirectories(d);
            } catch (IOException e) {
                log.warn("Could not create embed-tokens dir under {} — using in-memory store", saikuHome, e);
                d = null;
            }
        }
        this.dir = d;
    }

    /**
     * Mint a new token. {@code resourceKind} must be {@code "query"} or
     * {@code "dashboard"}; anything else throws {@link IllegalArgumentException}
     * so a malformed mint cannot land in the store. Defaults the token's
     * {@link EmbedToken#redactionPolicy} to {@code TENANT_DEFAULT} — back-
     * compat overload kept so existing callers don't have to think about
     * the saiku-cloud#940 policy field.
     */
    public EmbedToken create(
            String resourceKind,
            String resourcePath,
            String createdBy,
            List<String> ownerRoles,
            long ttlMillis,
            String label) {
        return create(
                resourceKind,
                resourcePath,
                createdBy,
                ownerRoles,
                ttlMillis,
                label,
                EmbedToken.RedactionPolicy.TENANT_DEFAULT);
    }

    /**
     * saiku-cloud#940 overload — explicit redaction policy. Used by the
     * embed-token mint when the inspector detected PII columns on the
     * resource and the policy must be FORCE_ON.
     */
    public EmbedToken create(
            String resourceKind,
            String resourcePath,
            String createdBy,
            List<String> ownerRoles,
            long ttlMillis,
            String label,
            EmbedToken.RedactionPolicy redactionPolicy) {
        if (resourceKind == null || !ALLOWED_KINDS.contains(resourceKind)) {
            // ALLOWED_KINDS.contains(null) throws NPE on Set.of immutables, so
            // null-check first — defends both the API and the runtime.
            throw new IllegalArgumentException("Unknown resourceKind: " + resourceKind);
        }
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath is required");
        }
        EmbedToken t = new EmbedToken();
        t.token = generateId();
        t.resourceKind = resourceKind;
        t.resourcePath = resourcePath;
        t.createdBy = createdBy;
        t.ownerRolesSnapshot = ownerRoles == null ? List.of() : new ArrayList<>(ownerRoles);
        t.createdAt = System.currentTimeMillis();
        t.redactionPolicy = redactionPolicy == null ? EmbedToken.RedactionPolicy.TENANT_DEFAULT : redactionPolicy;
        t.expiresAt = t.createdAt + ttlMillis;
        t.revoked = false;
        t.label = label;
        persist(t);
        return t;
    }

    /** Load a token by id, or null if the id is malformed / not found / unreadable. */
    public EmbedToken load(String id) {
        if (!isValidId(id)) {
            return null;
        }
        if (dir == null) {
            return memory.get(id);
        }
        Path f = safeResolve(id);
        if (f == null || !Files.exists(f)) {
            return null;
        }
        try {
            return MAPPER.readValue(f.toFile(), EmbedToken.class);
        } catch (IOException e) {
            log.warn("Unreadable embed-token file {}", f, e);
            return null;
        }
    }

    /** All tokens minted by {@code owner} (includes revoked/expired — caller filters). */
    public List<EmbedToken> listByOwner(String owner) {
        List<EmbedToken> all = listAll();
        List<EmbedToken> out = new ArrayList<>();
        for (EmbedToken t : all) {
            if (owner != null && owner.equals(t.createdBy)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Every token in the store (admin view). */
    public List<EmbedToken> listAll() {
        List<EmbedToken> out = new ArrayList<>();
        if (dir == null) {
            out.addAll(memory.values());
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    out.add(MAPPER.readValue(p.toFile(), EmbedToken.class));
                } catch (IOException e) {
                    log.warn("Skipping unreadable embed-token {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not list embed-tokens dir {}", dir, e);
        }
        return out;
    }

    /** Soft-revoke a token. Returns false if the id is unknown. Idempotent. */
    public boolean revoke(String id) {
        EmbedToken t = load(id);
        if (t == null) {
            return false;
        }
        t.revoked = true;
        persist(t);
        return true;
    }

    /* --------------------------- internals --------------------------- */

    private void persist(EmbedToken t) {
        if (dir == null) {
            memory.put(t.token, t);
            return;
        }
        Path target = safeResolve(t.token);
        if (target == null) {
            throw new IllegalStateException("Refusing to persist token with unsafe id");
        }
        try {
            Path tmp = dir.resolve(t.token + ".json.tmp");
            MAPPER.writeValue(tmp.toFile(), t);
            restrictPermissions(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Fall back to a non-atomic move if the FS doesn't support ATOMIC_MOVE.
            try {
                Path tmp = dir.resolve(t.token + ".json.tmp");
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new RuntimeException("Failed to persist embed token", e2);
            }
        }
    }

    /** Resolve {@code <id>.json} strictly within {@link #dir}; null if it escapes. */
    private Path safeResolve(String id) {
        Path resolved = dir.resolve(id + ".json").normalize();
        if (!resolved.startsWith(dir)) {
            log.warn("Refusing embed-token path that escapes the store dir: {}", id);
            return null;
        }
        return resolved;
    }

    private static boolean isValidId(String id) {
        return id != null && TOKEN_ID.matcher(id).matches();
    }

    private static String generateId() {
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
}
