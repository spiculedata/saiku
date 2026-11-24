/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.saiku.service.util.security.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed store for dashboard share tokens (issue #941), persisted as
 * {@code ${saiku.home}/share-tokens/<token>.json}.
 *
 * <p>Security posture:
 * <ul>
 *   <li>Token ids are 256-bit {@link SecureRandom} values, Base64URL-encoded —
 *       unguessable; the id is the only secret and carries no claims.</li>
 *   <li>Every id is regex-validated <b>before</b> any filesystem use and the
 *       resolved path is asserted to stay within the token dir — the
 *       {@code resolveWithinDatadir} traversal-guard pattern — so a crafted id
 *       ({@code ../}, separators, encoded slashes) can never escape the dir.</li>
 *   <li>Writes are atomic (temp file + {@code ATOMIC_MOVE}) so a concurrent
 *       read never sees a torn record during a mint/revoke race.</li>
 *   <li>When there is no {@code saiku.home} to persist to (unit tests), it
 *       falls back to an in-memory map — mirrors {@code DemoGateSecret}.</li>
 * </ul>
 */
public class ShareTokenStore {

    private static final Logger log = LoggerFactory.getLogger(ShareTokenStore.class);

    /** URL-safe Base64 alphabet, min length guards against trivially short ids. */
    private static final Pattern TOKEN_ID = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Non-null when persisting to disk; null → use {@link #memory}. */
    private final Path dir;

    /** In-memory fallback for test/no-home runs. */
    private final Map<String, ShareToken> memory = new ConcurrentHashMap<>();

    public ShareTokenStore() {
        this(System.getProperty("saiku.home"));
    }

    /** Visible for tests — pass an explicit home (or null for in-memory). */
    public ShareTokenStore(String saikuHome) {
        Path d = null;
        if (saikuHome != null && !saikuHome.isBlank()) {
            try {
                d = Paths.get(saikuHome).toAbsolutePath().normalize().resolve("share-tokens");
                Files.createDirectories(d);
            } catch (IOException e) {
                log.warn("Could not create share-tokens dir under {} — using in-memory store", saikuHome, e);
                d = null;
            }
        }
        this.dir = d;
    }

    /** Mint a new token for {@code dashboardPath}, owned by {@code createdBy}.
     *  Legacy overload: no content binding (the token binds to current content
     *  on first use). */
    public ShareToken create(
            String dashboardPath, String createdBy, List<String> ownerRoles, long ttlMillis, String label) {
        return create(dashboardPath, createdBy, ownerRoles, ttlMillis, label, null);
    }

    /** saiku#2536 — mint with the mint-time content binding: {@code
     *  contentSha256} is the hash of the shared content as read under the
     *  minter's scope. */
    public ShareToken create(
            String dashboardPath,
            String createdBy,
            List<String> ownerRoles,
            long ttlMillis,
            String label,
            String contentSha256) {
        return create(dashboardPath, createdBy, ownerRoles, ttlMillis, label, contentSha256, null);
    }

    public ShareToken create(
            String dashboardPath,
            String createdBy,
            List<String> ownerRoles,
            long ttlMillis,
            String label,
            String contentSha256,
            Map<String, String> referenceSha256) {
        ShareToken t = new ShareToken();
        t.token = generateId();
        t.dashboardPath = dashboardPath;
        t.createdBy = createdBy;
        t.ownerRolesSnapshot = ownerRoles == null ? List.of() : new ArrayList<>(ownerRoles);
        t.createdAt = System.currentTimeMillis();
        t.expiresAt = t.createdAt + ttlMillis;
        t.revoked = false;
        t.label = label;
        t.contentSha256 = contentSha256;
        t.referenceSha256 = referenceSha256 == null ? null : new HashMap<>(referenceSha256);
        persist(t);
        return t;
    }

    /** saiku#2536 — bind (or re-arm) a record's content hash. Used by the guest
     *  read path to bind a legacy unbound record on first use. No-op when the
     *  id is unknown. */
    public void bindContentSha256(String id, String sha256Hex) {
        ShareToken t = load(id);
        if (t == null) {
            return;
        }
        t.contentSha256 = sha256Hex;
        t.contentChangedByOther = false;
        persist(t);
    }

    public void bindReferences(String id, Map<String, String> hashes) {
        ShareToken t = load(id);
        if (t != null && t.referenceSha256 == null) {
            t.referenceSha256 = new HashMap<>(hashes);
            persist(t);
        }
    }

    /** saiku#2536 — repository save hook (called by
     *  {@code SharedContentBindingListener} after every successful
     *  {@code saveFile}). When the writer is the minter, re-arm the binding to
     *  the freshly written content — the link keeps serving the owner's own
     *  edits. When anyone else wrote, poison the record: the guest read is
     *  refused until the link is re-minted, even if the bytes are later
     *  restored. */
    public void onContentSaved(String path, String newSha256Hex, String writer) {
        onContentSaved(path, newSha256Hex, writer, null, (reference, token) -> null);
    }

    public void onContentSaved(
            String path,
            String newSha256Hex,
            String writer,
            String content,
            BiFunction<String, ShareToken, String> readReference) {
        if (path == null) {
            return;
        }
        String normalized = ContentBinding.normalizePath(path);
        for (ShareToken t : listAll()) {
            boolean primary = normalized.equals(ContentBinding.normalizePath(t.dashboardPath));
            boolean referenced = t.referenceSha256 != null && t.referenceSha256.containsKey(normalized);
            if (!primary && !referenced) {
                continue;
            }
            boolean owner = Usernames.sameUser(writer, t.createdBy);
            if (primary && owner) {
                t.contentSha256 = newSha256Hex;
                t.contentChangedByOther = false;
                if (content != null && t.dashboardPath.endsWith(".saikudash")) {
                    t.referenceSha256 = ContentBinding.referenceHashes(
                            "dashboard", content, reference -> readReference.apply(reference, t));
                    if (t.referencesChangedByOther != null) {
                        t.referencesChangedByOther.retainAll(t.referenceSha256.keySet());
                    }
                }
            } else if (primary) {
                t.contentChangedByOther = true;
                log.warn(
                        "Shared content {} was written by '{}' but its share link was minted by '{}' —"
                                + " the link is refused until re-minted (saiku#2536)",
                        path,
                        writer,
                        t.createdBy);
            }
            if (referenced) {
                if (owner) {
                    t.referenceSha256 = new HashMap<>(t.referenceSha256);
                    t.referenceSha256.put(normalized, newSha256Hex);
                    if (t.referencesChangedByOther != null) {
                        t.referencesChangedByOther.remove(normalized);
                    }
                } else {
                    if (t.referencesChangedByOther == null) {
                        t.referencesChangedByOther = new HashSet<>();
                    }
                    t.referencesChangedByOther.add(normalized);
                }
            }
            persist(t);
        }
    }

    /** Load a token by id, or null if the id is malformed / not found / unreadable. */
    public ShareToken load(String id) {
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
            return MAPPER.readValue(f.toFile(), ShareToken.class);
        } catch (IOException e) {
            log.warn("Unreadable share-token file {}", f, e);
            return null;
        }
    }

    /** All tokens minted by {@code owner} (includes revoked/expired — caller filters). */
    public List<ShareToken> listByOwner(String owner) {
        List<ShareToken> all = listAll();
        List<ShareToken> out = new ArrayList<>();
        for (ShareToken t : all) {
            if (Usernames.sameUser(owner, t.createdBy)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Every token in the store (admin view). */
    public List<ShareToken> listAll() {
        List<ShareToken> out = new ArrayList<>();
        if (dir == null) {
            out.addAll(memory.values());
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    out.add(MAPPER.readValue(p.toFile(), ShareToken.class));
                } catch (IOException e) {
                    log.warn("Skipping unreadable share-token {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not list share-tokens dir {}", dir, e);
        }
        return out;
    }

    /** Soft-revoke a token. Returns false if the id is unknown. Idempotent. */
    public boolean revoke(String id) {
        ShareToken t = load(id);
        if (t == null) {
            return false;
        }
        t.revoked = true;
        persist(t);
        return true;
    }

    /* --------------------------- internals --------------------------- */

    private void persist(ShareToken t) {
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
                throw new RuntimeException("Failed to persist share token", e2);
            }
        }
    }

    /** Resolve {@code <id>.json} strictly within {@link #dir}; null if it escapes. */
    private Path safeResolve(String id) {
        Path resolved = dir.resolve(id + ".json").normalize();
        if (!resolved.startsWith(dir)) {
            log.warn("Refusing share-token path that escapes the store dir: {}", id);
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
