/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of saved queries / dashboards that are publicly embeddable — the
 * second auth path for the {@code <saiku-embed>} surface alongside opaque
 * tokens.
 *
 * <p>Persisted as a single {@code ${saiku.home}/embed-public.json} keyed by
 * resource path. Choice of one file over one-per-grant: the registry is small
 * (admins curate the public surface), reads happen on EVERY embed request and
 * a single hot file beats a directory scan, and atomic rewrites on a small map
 * are cheap.
 *
 * <p>In-memory fallback when {@code saiku.home} isn't set mirrors
 * {@code EmbedTokenStore} so unit tests don't need a temp dir.
 */
public class EmbedPublicRegistry {

    private static final Logger log = LoggerFactory.getLogger(EmbedPublicRegistry.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Non-null when persisting to disk; null → memory-only. */
    private final Path file;

    /** Authoritative in-memory map (file is reloaded on construction). Key
     *  format: {@code "<kind>:<path>"} so kind-mismatch can't masquerade as a
     *  public grant. */
    private final Map<String, EmbedPublicGrant> grants = new ConcurrentHashMap<>();

    public EmbedPublicRegistry() {
        this(System.getProperty("saiku.home"));
    }

    public EmbedPublicRegistry(String saikuHome) {
        Path f = null;
        if (saikuHome != null && !saikuHome.isBlank()) {
            try {
                Path home = Paths.get(saikuHome).toAbsolutePath().normalize();
                Files.createDirectories(home);
                f = home.resolve("embed-public.json");
            } catch (IOException e) {
                log.warn("Could not create embed-public.json under {} — using in-memory registry", saikuHome, e);
                f = null;
            }
        }
        this.file = f;
        load();
    }

    /** True when {@code resourcePath} of {@code resourceKind} has an active
     *  public grant. Null kind / path → false. */
    public boolean isPublic(String resourceKind, String resourcePath) {
        EmbedPublicGrant g = lookup(resourceKind, resourcePath);
        return g != null;
    }

    /** Public grant for the path, or null. The owner-role snapshot on the
     *  returned record is the data-scope public reads should run under. */
    public EmbedPublicGrant lookup(String resourceKind, String resourcePath) {
        if (resourceKind == null || resourcePath == null) {
            return null;
        }
        return grants.get(key(resourceKind, resourcePath));
    }

    /** Mark a resource publicly embeddable. Idempotent — repeating a grant
     *  overwrites the owner snapshot (useful when the original grantor's
     *  roles change and they want public reads to track the new scope). */
    public synchronized EmbedPublicGrant grant(
            String resourceKind, String resourcePath, String grantedBy, List<String> ownerRoles, String label) {
        EmbedPublicGrant g = new EmbedPublicGrant();
        g.resourceKind = resourceKind;
        g.resourcePath = resourcePath;
        g.grantedBy = grantedBy;
        g.ownerRolesSnapshot = ownerRoles == null ? List.of() : new ArrayList<>(ownerRoles);
        g.grantedAt = System.currentTimeMillis();
        g.label = label;
        grants.put(key(resourceKind, resourcePath), g);
        persist();
        return g;
    }

    /** Remove the public grant. Returns true if a grant existed and was
     *  removed; idempotent {@code revoke}-of-nothing returns false. */
    public synchronized boolean revoke(String resourceKind, String resourcePath) {
        if (resourceKind == null || resourcePath == null) {
            return false;
        }
        EmbedPublicGrant removed = grants.remove(key(resourceKind, resourcePath));
        if (removed != null) {
            persist();
            return true;
        }
        return false;
    }

    /** All public grants whose {@code grantedBy} matches {@code owner}. */
    public List<EmbedPublicGrant> listByOwner(String owner) {
        List<EmbedPublicGrant> out = new ArrayList<>();
        for (EmbedPublicGrant g : grants.values()) {
            if (owner != null && owner.equals(g.grantedBy)) {
                out.add(g);
            }
        }
        return out;
    }

    /** Every public grant — admin view. */
    public List<EmbedPublicGrant> listAll() {
        return new ArrayList<>(grants.values());
    }

    /* --------------------------- internals --------------------------- */

    private static String key(String resourceKind, String resourcePath) {
        return resourceKind + ":" + resourcePath;
    }

    private synchronized void load() {
        grants.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            // Stored shape: { "query:/path.saiku": {EmbedPublicGrant}, ... }
            Map<String, EmbedPublicGrant> read =
                    MAPPER.readValue(file.toFile(), new TypeReference<Map<String, EmbedPublicGrant>>() {});
            if (read != null) {
                grants.putAll(read);
            }
        } catch (IOException e) {
            log.warn("Could not read embed-public.json — starting with empty registry", e);
        }
    }

    private synchronized void persist() {
        if (file == null) {
            return;
        }
        try {
            // Snapshot under the synchronized monitor so a concurrent grant
            // can't tear the map during serialization.
            Map<String, EmbedPublicGrant> snapshot = new HashMap<>(grants);
            Path tmp = file.resolveSibling("embed-public.json.tmp");
            MAPPER.writeValue(tmp.toFile(), snapshot);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist embed-public.json", e);
        }
    }
}
