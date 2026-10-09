/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * saiku#1435 — the path algebra for embed <b>Creator Mode</b>.
 *
 * <p>An {@code resourceKind="authoring"} embed token pins a cube plus a tenant
 * id, and grants the token-bearer the ability to CREATE saved queries and
 * dashboards <em>only</em> inside one derived folder. Every other write surface
 * stays closed. The derivation and the containment check both live here as pure
 * functions (no Spring, no filesystem) so the security-critical part of the
 * feature is unit-testable on its own and can't drift between the save path and
 * the read path.
 *
 * <p>The scope folder is <b>inside the token owner's own home</b>:
 * {@code /homes/<owner>/embed-guest-<tenantId>}. That placement is deliberate —
 * {@code FilesystemRepositoryManager.saveFile} gates every write on
 * {@code Acl2.canWrite(parent, user, roles)}, and {@code Acl2} isolates
 * {@code /homes/<user>} per user. A flat {@code /homes/embed-guest-<tenantId>}
 * would therefore be unwritable for the running identity (the owner's home
 * would deny it, and writing as the synthetic guest user would need a real
 * provisioned Saiku account per tenant). Anchoring under the owner keeps the
 * existing ACL model load-bearing — we never widen it — while the
 * {@code embed-guest-<tenantId>} leaf still gives per-tenant isolation, so a
 * tenant-B JWT resolves to a different folder and can never name a tenant-A
 * object.
 *
 * <p>All methods fail closed: an unusable input throws
 * {@link IllegalArgumentException} (or returns {@code false} / {@code null})
 * rather than returning a best-effort path.
 */
public final class EmbedAuthoringScope {

    /** Leaf folder name prefix inside the owner's home. */
    public static final String FOLDER_PREFIX = "embed-guest-";

    /** Saved-query extension. */
    public static final String QUERY_EXT = ".saiku";

    /** Dashboard extension. */
    public static final String DASHBOARD_EXT = ".saikudash";

    /** Cap on a sanitised object name. Keeps the derived path short and the
     *  on-disk filename portable (Windows MAX_PATH headroom, ext4 limits). */
    public static final int MAX_NAME_LENGTH = 64;

    /**
     * Tenant ids are host-chosen, so they're treated as untrusted input and
     * pinned to a conservative alphabet. No dots, no slashes, no whitespace —
     * a traversal payload cannot even be expressed.
     */
    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** Usernames reaching this class are the token's recorded owner. Same
     *  conservative alphabet, so the derived home path can't be steered. */
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** Characters kept in a user-supplied object name. Everything else becomes
     *  {@code '_'}. Notably absent: {@code .} (kills {@code ..} traversal and
     *  the Windows 8.3 / NTFS tail tricks saiku#1903 had to defend against),
     *  {@code /} and {@code \} (no separators at all). */
    /** A plain file suffix: one dot and a short alphanumeric run, nothing that could be a path. */
    private static final Pattern EXTENSION = Pattern.compile("^\\.[A-Za-z0-9]{1,16}$");

    private static final Pattern KEEP = Pattern.compile("[^A-Za-z0-9 _-]");

    private EmbedAuthoringScope() {}

    /** True when {@code tenantId} is safe to interpolate into a path. */
    public static boolean isValidTenantId(String tenantId) {
        return tenantId != null && TENANT_ID.matcher(tenantId).matches();
    }

    /**
     * Derive the tenant's scoped home folder: {@code /homes/<owner>/embed-guest-<tenantId>}.
     *
     * @throws IllegalArgumentException when either the owner or the tenant id is
     *     unusable — the caller must fail closed rather than fall back to a
     *     shared folder.
     */
    public static String homeFor(String ownerUser, String tenantId) {
        if (ownerUser == null || !OWNER.matcher(ownerUser).matches()) {
            throw new IllegalArgumentException("unusable embed authoring owner");
        }
        if (!isValidTenantId(tenantId)) {
            throw new IllegalArgumentException("unusable embed authoring tenantId");
        }
        return "/homes/" + ownerUser + "/" + FOLDER_PREFIX + tenantId;
    }

    /**
     * Reduce a host-supplied object name to a single safe path segment.
     * Dots are not preserved, so {@code ".."} and {@code "a/../../b"} both
     * collapse to harmless underscores rather than escaping the scope.
     *
     * @throws IllegalArgumentException when nothing usable survives
     */
    public static String sanitizeName(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("name is required");
        }
        // Normalise whitespace first so "  My  Query  " doesn't keep its padding.
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        String cleaned = KEEP.matcher(collapsed).replaceAll("_");
        // Trim again: a name made only of separators/underscores can gain
        // leading/trailing padding after the substitution.
        cleaned = cleaned.trim();
        if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '_' || c == ' ')) {
            throw new IllegalArgumentException("name has no usable characters");
        }
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH).trim();
        }
        return cleaned;
    }

    /**
     * Resolve {@code name} (plus {@code extension}) to a full repository path
     * inside {@code scope}. The result is asserted to be within the scope, so
     * even a bug in {@link #sanitizeName} can't produce an out-of-scope path.
     *
     * @throws IllegalArgumentException when the name is unusable or the
     *     resolved path would leave the scope
     */
    public static String resolve(String scope, String name, String extension) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
        if (extension == null || !EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("extension is not a plain file suffix");
        }
        String base = normalize(scope);
        String resolved = base + "/" + sanitizeName(name) + extension.toLowerCase(Locale.ROOT);
        if (!isWithin(base, resolved)) {
            // Unreachable given sanitizeName's alphabet; kept as a belt-and-braces
            // assertion so a future relaxation of the alphabet can't silently
            // turn this into a traversal sink.
            throw new IllegalArgumentException("resolved path escapes the authoring scope");
        }
        // Keep the caller's form: a scope handed in as an absolute repository path ("/homes/...")
        // yields an absolute object path, matching how homeFor() builds it.
        return scope.trim().startsWith("/") ? "/" + resolved : resolved;
    }

    /**
     * True when {@code path} is the scope folder itself or a descendant of it.
     * Both sides are canonicalised first ({@code ..} and {@code .} resolved,
     * duplicate separators collapsed, backslashes folded) so a crafted path
     * can't ride a normalisation gap.
     */
    public static boolean isWithin(String scope, String path) {
        if (scope == null || path == null) {
            return false;
        }
        String s;
        String p;
        try {
            s = normalize(scope);
            p = normalize(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return p.equals(s) || p.startsWith(s + "/");
    }

    /**
     * Canonicalise a repository path: leading separator dropped (the store
     * convention is relative, e.g. {@code homes/admin/x.saiku}), backslashes
     * folded to {@code /}, {@code .}/{@code //} collapsed, {@code ..} resolved
     * and rejected if it climbs above the root.
     *
     * @throws IllegalArgumentException on a path that escapes the root
     */
    public static String normalize(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        String p = path.trim().replace('\\', '/');
        StringBuilder out = new StringBuilder();
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (out.length() == 0) {
                    throw new IllegalArgumentException("path escapes the repository root");
                }
                // Pop the previous segment. The builder always ends in a separator, so drop that
                // first; otherwise lastIndexOf finds the trailing one and nothing is popped (".."
                // would then be silently ignored instead of resolved).
                out.setLength(out.length() - 1);
                out.setLength(out.lastIndexOf("/") + 1);
                continue;
            }
            out.append(segment).append('/');
        }
        if (out.length() == 0) {
            throw new IllegalArgumentException("path is empty after normalisation");
        }
        // Drop the trailing separator the loop appended.
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
