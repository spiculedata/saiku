/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-role on/off switches for the main UI features (saiku#2618).
 *
 * <p>One JSON document at {@code ${saiku.home}/feature-toggles.json} holds, per
 * {@link FeatureCatalogue feature key}, the list of Spring roles the feature is
 * granted to. The semantics are restriction-only, so an absent config file means
 * "everything on for everyone" and an existing install keeps working until an
 * admin turns something down:
 *
 * <ul>
 *   <li>feature absent from the config, or a null grant — <b>unrestricted</b>:
 *       every authenticated user;</li>
 *   <li>{@code "roles": []} — <b>admins only</b>;</li>
 *   <li>{@code "roles": ["ROLE_X", ...]} — admins plus the listed roles.</li>
 * </ul>
 *
 * <p>ROLE_ADMIN (the wired {@code adminRoles} list of {@code UserService}) always
 * passes every check — the admin panel must stay reachable for an admin — and the
 * feature toggles can only ever narrow what the Spring Security chain already
 * granted: they never grant a role the caller does not hold.
 *
 * <p>Reads re-scan the file when its mtime changes (the {@code AgentSpaceRegistry}
 * pattern); writes are atomic (temp file + move) and sorted, so the diff in git or
 * in an ops review is stable. {@link #isEnabled} is called on every gated REST
 * request, so the cached snapshot is swapped atomically and never re-read on the
 * hot path unless the file changed.
 */
public final class FeatureToggles {

    private static final Logger log = LoggerFactory.getLogger(FeatureToggles.class);

    private static final ObjectMapper WRITE_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The admin superuser authority — the wired {@code adminRoles} of {@code UserService}
     *  is exactly {@code ROLE_ADMIN}; admins are never feature-denied. */
    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private static final class Snapshot {
        final Map<String, List<String>> grants;
        final FileTime mtime;

        Snapshot(Map<String, List<String>> grants, FileTime mtime) {
            this.grants = grants;
            this.mtime = mtime;
        }
    }

    private final Path file;
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(Map.of(), null));

    public FeatureToggles(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public FeatureToggles(String file) {
        this(Path.of(Objects.requireNonNull(file, "file")));
    }

    public Path getFile() {
        return file;
    }

    /* ------------------------------- reads ------------------------------- */

    /**
     * The stored grant for a feature key: {@code null} when the feature is
     * unrestricted (absent from the config or null roles), otherwise the
     * (unmodifiable, possibly empty) role list. Unknown keys read as
     * unrestricted — the stored config can never accidentally disable a
     * feature the catalogue doesn't know.
     */
    public List<String> grantOf(String key) {
        List<String> roles = refresh().grants.get(key);
        if (roles == null) {
            return null;
        }
        return Collections.unmodifiableList(roles);
    }

    /** Every configured grant, keyed by feature key (unmodifiable; unrestricted keys are absent). */
    public Map<String, List<String>> allGrants() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(refresh().grants));
    }

    /**
     * Whether the caller's Spring authorities see the feature. Admins always
     * pass — {@code ROLE_ADMIN} is the wired {@code adminRoles} list of
     * {@code UserService}, and the admin panel must stay reachable for an
     * admin. An unrestricted feature passes for everyone. Otherwise the caller
     * must hold at least one granted role. Never grants on a null or empty
     * authority set beyond the unrestricted case.
     */
    public boolean isEnabled(String key, Set<String> authorities) {
        List<String> roles = grantOf(key);
        if (roles == null) {
            return true;
        }
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        if (authorities.contains(ADMIN_AUTHORITY)) {
            return true;
        }
        for (String role : roles) {
            if (authorities.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------- writes ------------------------------ */

    /**
     * Replace the whole grant map. Keys not in {@link FeatureCatalogue} are
     * rejected by the caller (the admin resource validates); this layer stores
     * what it is given, atomically, and rescans. {@code grants} maps a feature
     * key to its role list or {@code null} for unrestricted; null-valued and
     * absent keys are dropped from the file so an unrestricted install carries
     * no dead config.
     *
     * @return the grants actually stored (sorted, null-free)
     * @throws IOException when the file cannot be written (the caller answers
     *     500 and nothing is half-applied — the previous file still stands)
     */
    public Map<String, List<String>> save(Map<String, List<String>> grants) throws IOException {
        Map<String, List<String>> clean = new LinkedHashMap<>();
        if (grants != null) {
            for (Map.Entry<String, List<String>> e : grants.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                List<String> sorted = new ArrayList<>(new TreeSet<>(e.getValue()));
                clean.put(e.getKey(), sorted);
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("features", clean);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, WRITE_MAPPER.writeValueAsString(doc), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // Force the next read to pick the new file up even if its mtime granularity
        // hides the write from the signature check.
        snapshot.set(scan());
        log.info("Feature toggles saved ({} configured) -> {}", clean.size(), file);
        return Collections.unmodifiableMap(clean);
    }

    /* ------------------------------- scanning ---------------------------- */

    private Snapshot refresh() {
        Snapshot current = snapshot.get();
        FileTime mtime = mtimeOf();
        if (mtime == null) {
            // No file: unrestricted. Cache that only if we previously had a file
            // (it was deleted) or never loaded one — cheap no-op otherwise.
            if (current.mtime != null) {
                Snapshot empty = new Snapshot(Map.of(), null);
                snapshot.compareAndSet(current, empty);
                return empty;
            }
            return current;
        }
        if (current.mtime != null && current.mtime.equals(mtime)) {
            return current;
        }
        Snapshot next = scan();
        snapshot.compareAndSet(current, next);
        return snapshot.get();
    }

    private FileTime mtimeOf() {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return null;
        }
    }

    private Snapshot scan() {
        FileTime mtime = mtimeOf();
        if (mtime == null) {
            return new Snapshot(Map.of(), null);
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode doc = WRITE_MAPPER.readTree(raw);
            Map<String, List<String>> grants = new LinkedHashMap<>();
            JsonNode features = doc == null ? null : doc.get("features");
            if (features != null && features.isObject()) {
                var fields = features.fields();
                while (fields.hasNext()) {
                    var e = fields.next();
                    JsonNode roles = e.getValue() == null ? null : e.getValue().get("roles");
                    if (roles != null && roles.isArray()) {
                        List<String> list = new ArrayList<>();
                        for (JsonNode role : roles) {
                            if (role != null && !role.isNull()) {
                                String value = role.asText();
                                if (value != null && !value.isBlank()) {
                                    list.add(value.trim());
                                }
                            }
                        }
                        grants.put(e.getKey(), List.copyOf(list));
                    }
                }
            }
            return new Snapshot(Collections.unmodifiableMap(grants), mtime);
        } catch (IOException | RuntimeException e) {
            // An unparseable file must never break the gate (it sits on the hot
            // path), and the grant map it carried is unrecoverable — we cannot
            // tell which keys were restricted. So the file reads as EMPTY: every
            // feature behaves unrestricted, exactly like a missing config. That
            // falls back to what the Spring Security chain already enforces (the
            // pre-#2618 posture) rather than silently denying surfaces, and the
            // failure is logged loudly so ops fix the file; the admin panel
            // surfaces it too because the grants it just wrote appear to not
            // stick. Never throw.
            log.error("Feature toggles file unreadable, ignoring until it parses: {} ({})", file, e.toString());
            return new Snapshot(Map.of(), mtime);
        }
    }
}
