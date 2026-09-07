/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import org.saiku.service.util.security.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed store for periodic-send jobs (saiku#1809, PR1), persisted one-per-id as
 * {@code ${saiku.home}/jobs/<jobId>.json}.
 *
 * <p><b>This is dormant infrastructure.</b> PR1 ships the job model and its store ONLY — there is no
 * engine, no REST resource, no send capability, and no scheduling thread anywhere in this PR. Nothing
 * here can run or send a job: this class only creates/loads/lists/saves/deletes/enables records on
 * disk. A persisted job just sits there.
 *
 * <p>Security posture (mirrors {@code ShareTokenStore}):
 * <ul>
 *   <li>Job ids are 256-bit {@link SecureRandom} values, Base64URL-encoded — unguessable.</li>
 *   <li>Every id is regex-validated <b>before</b> any filesystem use and the resolved path is
 *       asserted to stay within the jobs dir ({@link #safeResolve}) — a crafted id
 *       ({@code ../}, separators, encoded slashes, absolute paths) can never escape the dir.</li>
 *   <li>Writes are atomic (temp file + {@code ATOMIC_MOVE}) so a concurrent read never sees a torn
 *       record.</li>
 *   <li>When there is no {@code saiku.home} to persist to (unit tests), it falls back to an in-memory
 *       map.</li>
 * </ul>
 */
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    /** URL-safe Base64 alphabet; min length guards against trivially short ids. */
    private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Non-null when persisting to disk; null → use {@link #memory}. */
    private final Path dir;

    /** In-memory fallback for test/no-home runs. */
    private final Map<String, ScheduledJobFile> memory = new ConcurrentHashMap<>();

    public JobStore() {
        this(System.getProperty("saiku.home"));
    }

    /** Visible for tests — pass an explicit home (or null for in-memory). */
    public JobStore(String saikuHome) {
        Path d = null;
        if (saikuHome != null && !saikuHome.isBlank()) {
            try {
                d = Paths.get(saikuHome).toAbsolutePath().normalize().resolve("jobs");
                Files.createDirectories(d);
            } catch (IOException e) {
                log.warn("Could not create jobs dir under {} — using in-memory store", saikuHome, e);
                d = null;
            }
        }
        this.dir = d;
    }

    /**
     * Mint and persist a new job for {@code ownerUsername}. The job is inert — creating it schedules
     * nothing and sends nothing; it is a record on disk.
     */
    public ScheduledJobFile create(
            String ownerUsername,
            List<String> ownerRoles,
            JobSchedule schedule,
            String type,
            Map<String, Object> payload,
            boolean enabled) {
        ScheduledJobFile j = new ScheduledJobFile();
        j.setId(generateId());
        j.setOwnerUsername(ownerUsername);
        j.setOwnerRolesSnapshot(ownerRoles == null ? List.of() : new ArrayList<>(ownerRoles));
        j.setSchedule(schedule);
        j.setType(type);
        j.setPayload(payload);
        j.setEnabled(enabled);
        j.setCreatedAt(System.currentTimeMillis());
        persist(j);
        return j;
    }

    /** Load a job by id, or null if the id is malformed / not found / unreadable. */
    public ScheduledJobFile load(String id) {
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
            return MAPPER.readValue(f.toFile(), ScheduledJobFile.class);
        } catch (IOException e) {
            log.warn("Unreadable job file {}", f, e);
            return null;
        }
    }

    /** Every job in the store (admin view). */
    public List<ScheduledJobFile> listAll() {
        List<ScheduledJobFile> out = new ArrayList<>();
        if (dir == null) {
            out.addAll(memory.values());
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    out.add(MAPPER.readValue(p.toFile(), ScheduledJobFile.class));
                } catch (IOException e) {
                    log.warn("Skipping unreadable job {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not list jobs dir {}", dir, e);
        }
        return out;
    }

    /** All jobs owned by {@code owner}. */
    public List<ScheduledJobFile> listByOwner(String owner) {
        List<ScheduledJobFile> out = new ArrayList<>();
        for (ScheduledJobFile j : listAll()) {
            if (Usernames.sameUser(owner, j.getOwnerUsername())) {
                out.add(j);
            }
        }
        return out;
    }

    /** Persist an updated job record. Returns the same instance for convenience. */
    public ScheduledJobFile save(ScheduledJobFile job) {
        if (job == null || !isValidId(job.getId())) {
            throw new IllegalArgumentException("Refusing to save job with missing/invalid id");
        }
        persist(job);
        return job;
    }

    /** Delete a job by id. Returns false if the id is malformed or no file existed. Idempotent. */
    public boolean delete(String id) {
        if (!isValidId(id)) {
            return false;
        }
        if (dir == null) {
            return memory.remove(id) != null;
        }
        Path f = safeResolve(id);
        if (f == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(f);
        } catch (IOException e) {
            log.warn("Could not delete job {}", f, e);
            return false;
        }
    }

    /**
     * Flip a job's {@code enabled} flag and persist. Returns the updated record, or null if the id is
     * unknown. NB: with no engine in PR1, this gates nothing yet — it only records intent.
     */
    public ScheduledJobFile setEnabled(String id, boolean enabled) {
        ScheduledJobFile j = load(id);
        if (j == null) {
            return null;
        }
        j.setEnabled(enabled);
        persist(j);
        return j;
    }

    /* --------------------------- internals --------------------------- */

    private void persist(ScheduledJobFile j) {
        if (dir == null) {
            memory.put(j.getId(), j);
            return;
        }
        Path target = safeResolve(j.getId());
        if (target == null) {
            throw new IllegalStateException("Refusing to persist job with unsafe id");
        }
        Path tmp = dir.resolve(j.getId() + ".json.tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), j);
            restrictPermissions(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Fall back to a non-atomic move if the FS doesn't support ATOMIC_MOVE.
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new RuntimeException("Failed to persist job", e2);
            }
        }
    }

    /** Resolve {@code <id>.json} strictly within {@link #dir}; null if it escapes. */
    private Path safeResolve(String id) {
        Path resolved = dir.resolve(id + ".json").normalize();
        if (!resolved.startsWith(dir)) {
            log.warn("Refusing job path that escapes the store dir: {}", id);
            return null;
        }
        return resolved;
    }

    private static boolean isValidId(String id) {
        return id != null && JOB_ID.matcher(id).matches();
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
