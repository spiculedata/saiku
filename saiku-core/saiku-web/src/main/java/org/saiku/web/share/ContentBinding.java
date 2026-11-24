/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The content binding behind saiku#2536: a share link, embed token or public
 * embed grant is bound not only to the repository PATH it was minted for, but
 * to the CONTENT that was shared at mint time.
 *
 * <p>Why: the guest read path re-reads the pinned object on every view and
 * executes its queries under the minter's roles ({@code SessionService.runAs}).
 * That is safe only while nobody except the minter can write the object — a
 * collaborator with {@code WRITE} could otherwise swap a tile's
 * {@code reference} for a saved query the minter can read but the guest
 * cannot, and read the result through the link. Binding the minted content's
 * SHA-256 (plus a poisoned {@code changed-by-other} flag recorded by the
 * repository save hook) closes that: content written by anyone other than the
 * minter is refused with {@code 409 CONTENT_CHANGED}; a write by the minter
 * itself re-arms the binding so ordinary iteration keeps working.
 *
 * <p>State is carried on the records: {@code contentSha256} (null on legacy
 * records — they bind to current content on first use) and
 * {@code contentChangedByOther}.
 */
public final class ContentBinding {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Status string of the 409 a changed/poisoned record produces. */
    public static final String CONTENT_CHANGED = "CONTENT_CHANGED";

    private ContentBinding() {}

    /** Lowercase SHA-256 hex of {@code content}; null when content is null. */
    public static String sha256Hex(String content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JLS mandates SHA-256 in every platform; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Snapshot(String contentSha256, Map<String, String> referenceSha256) {}

    public static Snapshot snapshot(String kind, String raw, Function<String, String> readAsOwner) {
        return new Snapshot(sha256Hex(raw), raw == null ? null : referenceHashes(kind, raw, readAsOwner));
    }

    /** True when {@code pinnedSha256} is bound and matches {@code content}'s hash. */
    public static boolean matches(String pinnedSha256, String content) {
        if (pinnedSha256 == null || content == null) {
            return false;
        }
        return pinnedSha256.equals(sha256Hex(content));
    }

    /** Repository paths may arrive with or without a leading slash. */
    public static String normalizePath(String path) {
        return path == null ? null : path.replaceAll("/+", "/").replaceFirst("^/", "");
    }

    /** The content executed by a reference tile is a second repository file.
     *  Bind it alongside the dashboard/app instead of trusting only the parent file. */
    public static Map<String, String> referenceHashes(String kind, String raw, Function<String, String> readAsOwner) {
        Map<String, String> hashes = new HashMap<>();
        for (String path : referencePaths(kind, raw)) {
            String content;
            try {
                content = readAsOwner.apply(path);
            } catch (RuntimeException e) {
                content = null;
            }
            // An unreadable target can never be used as a binding. Keep its
            // path with an impossible hash so the guest read fails closed.
            hashes.put(normalizePath(path), content == null ? "" : sha256Hex(content));
        }
        return hashes;
    }

    public static Set<String> referencePaths(String kind, String raw) {
        Set<String> paths = new HashSet<>();
        if (raw == null || (!"dashboard".equals(kind) && !"app".equals(kind))) {
            return paths;
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null) {
                return paths;
            }
            if ("dashboard".equals(kind)) {
                collectReferences(root.path("layout").path("tiles"), paths);
            } else {
                for (JsonNode page : root.path("pages")) {
                    collectReferences(page.path("grid").path("tiles"), paths);
                }
            }
        } catch (Exception ignored) {
            // The guest parser also rejects malformed parent content.
        }
        return paths;
    }

    private static void collectReferences(JsonNode tiles, Set<String> paths) {
        if (!tiles.isArray()) {
            return;
        }
        for (JsonNode tile : tiles) {
            JsonNode query = tile.path("query");
            JsonNode path = query.path("path");
            if ("reference".equals(query.path("kind").asText()) && path.isTextual()) {
                paths.add(path.asText());
            }
        }
    }

    public static boolean referencesMatch(Map<String, String> hashes, Function<String, String> readAsOwner) {
        if (hashes == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            String content;
            try {
                content = readAsOwner.apply(entry.getKey());
            } catch (RuntimeException e) {
                return false;
            }
            if (!matches(entry.getValue(), content)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The refusal body for a record whose content no longer matches its
     * binding (or which another user has written since minting). Callers wrap
     * it in their own hardening headers.
     */
    public static Response conflict() {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of(
                        "status",
                        CONTENT_CHANGED,
                        "error",
                        "The shared content has changed since this link was minted. Ask the owner to share"
                                + " it again."))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
