/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The <b>signed, self-origin</b> description of what to render (saiku#1810).
 *
 * <p>A headless renderer that accepted a URL would be an SSRF primitive: whoever can name the target
 * chooses where the server goes. So the unit of work is <b>not a URL</b> — it is this record, which
 * carries a <b>repository path</b> (never a host, scheme or authority), the panels to snapshot, the
 * owner the render runs as, and an expiry. {@link SnapshotReferenceSigner} serialises it to a compact
 * token and stamps it with an HMAC derived from the per-install key; only a token that verifies is
 * ever rendered.
 *
 * <p>Consequences that matter, all enforced by the signer on both sign and verify:
 *
 * <ul>
 *   <li>There is no field in which a caller can supply a fetch target. The only location-ish field is
 *       {@link #dashboardPath()}, which must be a <b>relative</b> JCR repository path ending {@code
 *       .saikudash} — no scheme, no authority, no {@code ..} segment, no backslash, no leading
 *       {@code /}, no NUL. {@code https://169.254.169.254/latest/meta-data} cannot be expressed.
 *   <li>The {@link #owner()} is <b>inside the signed bytes</b>, so a reference minted for one user
 *       cannot be replayed as another; the renderer additionally requires the caller's authenticated
 *       name to equal it.
 *   <li>References expire. A leaked token stops working rather than becoming a permanent read
 *       capability.
 * </ul>
 *
 * <p>Because a valid token can only have been produced by this install, the renderer resolves the
 * dashboard <b>locally</b> from the repository and issues <b>no outbound request at all</b>. There is
 * no redirect to follow off-origin because there is no fetch.
 */
public final class SnapshotReference {

    /** The only file extension a renderable dashboard may have. */
    public static final String DASHBOARD_EXTENSION = ".saikudash";

    private final String owner;
    private final String dashboardPath;
    private final String title;
    private final List<SnapshotPanel> panels;
    private final long expiresAtEpochMillis;

    public SnapshotReference(
            String owner, String dashboardPath, String title, List<SnapshotPanel> panels, long expiresAt) {
        this.owner = owner;
        this.dashboardPath = dashboardPath;
        this.title = title;
        this.panels = panels == null ? List.of() : List.copyOf(panels);
        this.expiresAtEpochMillis = expiresAt;
    }

    /** The username the render runs as — signed, and re-checked against the caller's identity. */
    public String owner() {
        return owner;
    }

    /** The dashboard's JCR repository path (relative; never a URL). */
    public String dashboardPath() {
        return dashboardPath;
    }

    /** Display title rendered at the top of the snapshot; may be null (the path stem is used). */
    public String title() {
        return title;
    }

    /** The panels (measure tiles) to snapshot, in render order. Never null. */
    public List<SnapshotPanel> panels() {
        return panels;
    }

    /** Epoch millis after which this reference must be rejected. */
    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    /** True when {@code nowEpochMillis} is at or past the expiry. */
    public boolean isExpired(long nowEpochMillis) {
        return nowEpochMillis >= expiresAtEpochMillis;
    }

    /**
     * Fail-closed validation of a decoded reference. Called by {@link SnapshotReferenceSigner} on
     * <b>both</b> sign and verify, so a reference that could never have been rendered can never be
     * rendered either.
     *
     * @throws SnapshotReferenceException when any field is missing, malformed, or off-origin
     */
    public void validate() {
        if (owner == null || owner.isBlank()) {
            throw new SnapshotReferenceException("snapshot reference owner is required");
        }
        if (dashboardPath == null || dashboardPath.isBlank()) {
            throw new SnapshotReferenceException("snapshot reference dashboard path is required");
        }
        if (!isSelfOriginRepositoryPath(dashboardPath)) {
            // Deliberately terse and uniform: the reason is not echoed back with the offending value,
            // so a caller cannot use the error text as a path oracle.
            throw new SnapshotReferenceException("snapshot reference is not a self-origin dashboard reference");
        }
        if (panels.isEmpty()) {
            throw new SnapshotReferenceException("snapshot reference must name at least one panel");
        }
        for (SnapshotPanel p : panels) {
            p.validate();
        }
    }

    /**
     * True when {@code path} is a <b>relative, self-origin</b> dashboard repository path.
     *
     * <p>Rejects, in order: a null/blank path; anything with a NUL or a control character; anything
     * with a URL scheme ({@code ://}) or an authority ({@code @}, or a leading {@code //}); a leading
     * {@code /} (absolute path) or {@code ~}; a backslash (Windows separator / UNC smuggling); a
     * {@code ..} or {@code .} path segment (traversal); an empty segment ({@code //}); and finally
     * anything that does not end in {@value #DASHBOARD_EXTENSION}.
     *
     * <p>Note this is a <b>syntactic</b> gate and is the only shape gate there is: there is no host
     * field to be off-origin in the first place.
     */
    public static boolean isSelfOriginRepositoryPath(String path) {
        if (path == null || path.isBlank() || path.length() > 512) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == 0 || c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        if (path.contains("://") || path.indexOf('@') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        if (path.startsWith("/") || path.startsWith("//") || path.startsWith("~")) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(DASHBOARD_EXTENSION)) {
            return false;
        }
        // The stem must be non-empty: ".saikudash" alone is not a dashboard.
        if (path.length() == DASHBOARD_EXTENSION.length()) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * One tile in the snapshot: a display label, the cube it reads, the measure to read, and the
     * saved filter state (slicer members) to apply. The measure is read <b>as a scalar</b> under the
     * job owner's identity, so the snapshot cannot contain a row the owner cannot see interactively.
     */
    public static final class SnapshotPanel {

        private final String label;
        private final String cube;
        private final String measure;
        private final Map<String, List<String>> filters;

        public SnapshotPanel(String label, String cube, String measure, Map<String, List<String>> filters) {
            this.label = label;
            this.cube = cube;
            this.measure = measure;
            this.filters = filters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
        }

        /** Display label; falls back to the measure name at render time when null/blank. */
        public String label() {
            return label == null || label.isBlank() ? measure : label;
        }

        /** {@code connection/catalog/schema/cube}. */
        public String cube() {
            return cube;
        }

        /** The measure to read a scalar value for. */
        public String measure() {
            return measure;
        }

        /** Saved filter state: dimension unique-name prefix to selected members. Never null. */
        public Map<String, List<String>> filters() {
            return filters;
        }

        void validate() {
            if (cube == null || !isValidCubeRef(cube)) {
                throw new SnapshotReferenceException(
                        "snapshot panel cube is not a valid 'connection/catalog/schema/cube' ref");
            }
            if (measure == null || measure.isBlank() || measure.length() > 256) {
                throw new SnapshotReferenceException("snapshot panel measure is required");
            }
            if (filters.size() > 32) {
                throw new SnapshotReferenceException("snapshot panel has too many filters");
            }
            for (Map.Entry<String, List<String>> e : filters.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 256) {
                    throw new SnapshotReferenceException("snapshot panel filter dimension is invalid");
                }
                // The token encoding separates a filter dimension from its member list with '=' and
                // separates members with '|'. A dimension or member containing one of those could
                // not round-trip, so signing such a reference would silently change the saved
                // filter state at render time — the snapshot would then show something other than
                // what was signed. Reject it instead.
                if (e.getKey().indexOf('=') >= 0 || e.getKey().indexOf(';') >= 0) {
                    throw new SnapshotReferenceException(
                            "snapshot panel filter dimension contains a reserved character");
                }
                List<String> members = e.getValue();
                if (members == null || members.isEmpty() || members.size() > 256) {
                    throw new SnapshotReferenceException("snapshot panel filter must select between 1 and 256 members");
                }
                for (String m : members) {
                    if (m == null || m.isBlank() || m.length() > 512) {
                        throw new SnapshotReferenceException("snapshot panel filter member is invalid");
                    }
                    if (m.indexOf('|') >= 0) {
                        throw new SnapshotReferenceException(
                                "snapshot panel filter member contains a reserved character");
                    }
                }
            }
        }

        /** A cube ref is exactly four non-empty, slash-free, control-free segments. */
        private static boolean isValidCubeRef(String cube) {
            if (cube.length() > 512 || cube.indexOf('\\') >= 0) {
                return false;
            }
            String[] parts = cube.split("/", -1);
            if (parts.length != 4) {
                return false;
            }
            for (String p : parts) {
                if (p.isEmpty()) {
                    return false;
                }
                for (int i = 0; i < p.length(); i++) {
                    char c = p.charAt(i);
                    if (c < 0x20 || c == 0x7f) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SnapshotPanel other)) {
                return false;
            }
            return Objects.equals(label, other.label)
                    && Objects.equals(cube, other.cube)
                    && Objects.equals(measure, other.measure)
                    && Objects.equals(filters, other.filters);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, cube, measure, filters);
        }
    }

    /** Mutable builder — references are assembled from an admin-authored spec, then signed. */
    public static final class Builder {
        private String owner;
        private String dashboardPath;
        private String title;
        private final List<SnapshotPanel> panels = new ArrayList<>();
        private long expiresAt = Long.MAX_VALUE;

        public Builder owner(String v) {
            this.owner = v;
            return this;
        }

        public Builder dashboardPath(String v) {
            this.dashboardPath = v;
            return this;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder panel(SnapshotPanel v) {
            this.panels.add(v);
            return this;
        }

        public Builder expiresAtEpochMillis(long v) {
            this.expiresAt = v;
            return this;
        }

        public SnapshotReference build() {
            return new SnapshotReference(owner, dashboardPath, title, panels, expiresAt);
        }
    }
}
