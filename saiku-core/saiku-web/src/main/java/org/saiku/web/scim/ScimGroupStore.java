/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed registry of SCIM groups (issue #1438), persisted as
 * {@code ${saiku.home}/scim-groups/<id>.json}.
 *
 * <p>Saiku has no first-class "group" entity: the closest thing is a role string in
 * {@code USER_ROLES}. A SCIM group is therefore a <i>named role</i> plus the list of member
 * usernames; membership changes are applied to {@code USER_ROLES} by
 * {@link ScimService}. The registry exists so a group has a stable, IdP-visible {@code id} and a
 * place to record which role it owns.
 *
 * <p>Ids are UUIDs minted here (never client-supplied — see {@link #create}), so the traversal
 * guard below can insist on the exact shape before any filesystem use.
 */
public class ScimGroupStore {

    private static final Logger log = LoggerFactory.getLogger(ScimGroupStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A group as stored: id, display name (= the Saiku role), members, timestamps. */
    public static class GroupRecord {
        public String id;
        public String displayName;
        public long created;
        public long lastModified;
        /** Canonicalised usernames; a member that has been deleted from the directory lingers
         *  here until the next membership sync, and is skipped rather than failing the write. */
        public List<String> members = new ArrayList<>();
    }

    private final Path dir;
    private final Map<String, GroupRecord> memory = new ConcurrentHashMap<>();

    public ScimGroupStore() {
        this(System.getProperty("saiku.home"));
    }

    /** Visible for tests — pass an explicit home, or null for in-memory. */
    public ScimGroupStore(String saikuHome) {
        Path d = null;
        if (saikuHome != null && !saikuHome.isBlank()) {
            try {
                d = Paths.get(saikuHome).toAbsolutePath().normalize().resolve("scim-groups");
                Files.createDirectories(d);
            } catch (IOException e) {
                log.warn("Could not create scim-groups dir under {} — using in-memory store", saikuHome, e);
                d = null;
            }
        }
        this.dir = d;
    }

    public GroupRecord create(String displayName, List<String> members, long now) {
        GroupRecord r = new GroupRecord();
        r.id = UUID.randomUUID().toString();
        r.displayName = displayName;
        r.created = now;
        r.lastModified = now;
        r.members = members == null ? new ArrayList<>() : new ArrayList<>(members);
        persist(r);
        return r;
    }

    public GroupRecord load(String id) {
        if (!isValidId(id)) {
            return null;
        }
        return read(id);
    }

    public List<GroupRecord> listAll() {
        List<GroupRecord> out = new ArrayList<>();
        if (dir == null) {
            out.addAll(memory.values());
        } else {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                    try {
                        out.add(MAPPER.readValue(p.toFile(), GroupRecord.class));
                    } catch (IOException e) {
                        log.warn("Skipping unreadable scim-group record {}", p, e);
                    }
                });
            } catch (IOException e) {
                log.warn("Could not list scim-groups dir {}", dir, e);
            }
        }
        out.sort((a, b) -> String.valueOf(a.displayName).compareToIgnoreCase(String.valueOf(b.displayName)));
        return out;
    }

    /** Find the group that owns a given role string, or null. Case-insensitive. */
    public GroupRecord findByDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        for (GroupRecord r : listAll()) {
            if (displayName.equalsIgnoreCase(r.displayName)) {
                return r;
            }
        }
        return null;
    }

    public boolean delete(String id) {
        GroupRecord r = load(id);
        if (r == null) {
            return false;
        }
        if (dir == null) {
            memory.remove(r.id);
        } else {
            try {
                Files.deleteIfExists(safeResolve(r.id));
            } catch (IOException e) {
                log.warn("Could not delete scim-group record {}", r.id, e);
                return false;
            }
        }
        return true;
    }

    public void save(GroupRecord r) {
        if (r == null) {
            return;
        }
        r.lastModified = System.currentTimeMillis();
        persist(r);
    }

    private GroupRecord read(String id) {
        if (dir == null) {
            return memory.get(id);
        }
        Path f = safeResolve(id);
        if (f == null || !Files.exists(f)) {
            return null;
        }
        try {
            return MAPPER.readValue(f.toFile(), GroupRecord.class);
        } catch (IOException e) {
            log.warn("Unreadable scim-group record {}", f, e);
            return null;
        }
    }

    private void persist(GroupRecord r) {
        if (dir == null) {
            memory.put(r.id, r);
            return;
        }
        Path target = safeResolve(r.id);
        if (target == null) {
            throw new IllegalStateException("Refusing to persist scim group with unsafe id");
        }
        try {
            Path tmp = dir.resolve(r.id + ".json.tmp");
            MAPPER.writeValue(tmp.toFile(), r);
            restrictPermissions(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(dir.resolve(r.id + ".json.tmp"), target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new RuntimeException("Failed to persist scim group", e2);
            }
        }
    }

    private Path safeResolve(String id) {
        if (!isValidId(id)) {
            return null;
        }
        Path resolved = dir.resolve(id + ".json").normalize();
        if (!resolved.startsWith(dir)) {
            log.warn("Refusing scim-group path that escapes the store dir");
            return null;
        }
        return resolved;
    }

    private static boolean isValidId(String id) {
        if (id == null || id.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
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
