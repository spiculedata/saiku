/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin-side storage for {@link ExportDestination} configuration (saiku#1987), under
 * {@code ${saiku.home}/export-destinations}.
 *
 * <p>Two files, on purpose:
 *
 * <ul>
 *   <li>{@code <DEST_ID>.json} — the <b>plain settings</b> only (Drive folder id, S3 bucket, …), one
 *       file per destination. These are safe to read back over the admin API.</li>
 *   <li>{@code secrets.json} — <b>every destination's credentials in one owner-only file</b>, written
 *       with {@code 0600} and never merged into the per-destination file. Keeping the secrets in a
 *       single file means "does this destination have a credential set?" is one stat, and it means a
 *       stray backup/glob of the per-destination files can never pick up a token.</li>
 * </ul>
 *
 * <p>Also load-bearing per the issue: <b>a secret never travels in a query JSON or a job payload.</b>
 * The scheduled job names a {@code destination} id and the store resolves the credentials; a job file
 * written to {@code ${saiku.home}/jobs/} therefore cannot leak them, and neither can a shared job
 * definition.
 *
 * <p>Writes are atomic (temp file + {@code ATOMIC_MOVE}) so a concurrent read never sees a torn
 * config. Every read is defensive: an unreadable or malformed file yields an empty config and one
 * WARN, never an exception into a scheduled run.
 */
public class ExportDestinationConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ExportDestinationConfigStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Destination ids are UPPER_SNAKE_CASE and become a file name, so they are strictly validated. */
    private static final Pattern DEST_ID = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final Path dir;

    /** Absolute path of the single credentials file; also the only file we chmod 0600. */
    private final Path secretsFile;

    /** In-memory fallback for tests / no-home runs. */
    private final Map<String, ExportDestinationConfig> memory = new LinkedHashMap<>();

    /** {@code forSaikuHome} factory used by the Spring wiring, mirroring {@code JobStore}. */
    public static ExportDestinationConfigStore forSaikuHome(String saikuHome) {
        return new ExportDestinationConfigStore(Paths.get(saikuHome == null || saikuHome.isBlank() ? "." : saikuHome)
                .resolve("export-destinations"));
    }

    /** Visible for tests: an explicit directory. A null directory selects in-memory mode. */
    public ExportDestinationConfigStore(Path dir) {
        this.dir = dir;
        this.secretsFile = dir == null ? null : dir.resolve("secrets.json");
    }

    /**
     * The stored config for {@code destinationId}, or {@link ExportDestinationConfig#empty()} when
     * nothing is stored. Never throws for a missing/unreadable file.
     */
    public ExportDestinationConfig get(String destinationId) {
        String id = normalise(destinationId);
        if (id == null) {
            return ExportDestinationConfig.empty();
        }
        if (dir == null) {
            return memory.getOrDefault(id, ExportDestinationConfig.empty());
        }
        Map<String, String> settings = readMap(fileFor(id));
        Map<String, String> secrets = secretsFor(id);
        return ExportDestinationConfig.of(settings, secrets);
    }

    /** True when the destination has any stored settings or credentials. */
    public boolean isConfigured(String destinationId) {
        return !get(destinationId).isEmpty();
    }

    /**
     * Store {@code config} for {@code destinationId}, replacing whatever was there. Secrets go to
     * {@code secrets.json}; plain settings to {@code <id>.json}. A destination id with no plain
     * settings left is deleted rather than left as an empty file.
     *
     * <p>Writes each half independently, so a failure to persist the secrets file still leaves the
     * old credentials in place (never a half-updated credential) and is surfaced by throwing.
     */
    public void save(String destinationId, ExportDestinationConfig config) {
        String id = normalise(destinationId);
        if (id == null) {
            throw new IllegalArgumentException("export destination id is invalid");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (dir == null) {
            memory.put(id, config);
            return;
        }
        try {
            Files.createDirectories(dir);
            writeMap(fileFor(id), config.settings());
            Map<String, Map<String, String>> byDestination = new LinkedHashMap<>(readAllSecretsByDestination());
            if (config.secrets().isEmpty()) {
                byDestination.remove(id);
            } else {
                byDestination.put(id, config.secrets());
            }
            writeNestedSecrets(byDestination);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist export destination config for " + id, e);
        }
    }

    /**
     * Forget everything stored for {@code destinationId} — both the plain settings and the
     * credentials. Idempotent; a no-op for an id that was never configured.
     */
    public void delete(String destinationId) {
        String id = normalise(destinationId);
        if (id == null) {
            throw new IllegalArgumentException("export destination id is invalid");
        }
        if (dir == null) {
            memory.remove(id);
            return;
        }
        try {
            Files.deleteIfExists(fileFor(id));
            Map<String, Map<String, String>> byDestination = new LinkedHashMap<>(readAllSecretsByDestination());
            if (byDestination.remove(id) != null) {
                writeNestedSecrets(byDestination);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete export destination config for " + id, e);
        }
    }

    private Path fileFor(String id) {
        return dir.resolve(id + ".json");
    }

    /** This destination's slice of the shared secrets file. */
    private Map<String, String> secretsFor(String id) {
        Map<String, Map<String, String>> all = readAllSecretsByDestination();
        return all.getOrDefault(id, Map.of());
    }

    /**
     * Rewrite the whole credentials file, owner-only and atomically. The file is small and rewritten
     * on every config save (an admin action), so a read-modify-write is the right trade against
     * partial-update bugs.
     */
    private void writeNestedSecrets(Map<String, Map<String, String>> byDestination) throws IOException {
        writeJsonAtomically(secretsFile, byDestination, true);
    }

    /**
     * The secrets file as {@code {DEST_ID: {field: value}}}. A file that is missing, empty or
     * malformed yields an empty map plus one WARN — a corrupt credentials file must not take down the
     * scheduler.
     */
    private Map<String, Map<String, String>> readAllSecretsByDestination() {
        if (dir == null || !Files.isReadable(secretsFile)) {
            return Map.of();
        }
        try {
            Map<String, Map<String, String>> parsed = MAPPER.readValue(secretsFile.toFile(), new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (IOException e) {
            log.warn(
                    "Export destination secrets file {} is unreadable — treating as no credentials: {}",
                    secretsFile.getFileName(),
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, String> readMap(Path file) {
        if (dir == null || !Files.isReadable(file)) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(file.toFile(), new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (IOException e) {
            log.warn(
                    "Export destination config file {} is unreadable — treating as unconfigured: {}",
                    file.getFileName(),
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private void writeMap(Path file, Map<String, String> values) throws IOException {
        writeJsonAtomically(file, values, false);
    }

    /** Write {@code value} as JSON to {@code file} atomically, optionally chmod 0600. */
    private void writeJsonAtomically(Path file, Object value, boolean ownerOnly) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), value);
        if (ownerOnly) {
            restrictPermissions(tmp);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        if (ownerOnly) {
            restrictPermissions(file);
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(
                    file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX (Windows) — best effort; the file sits under saiku-home, same as JobStore.
        }
    }

    /** Validate + canonicalise a destination id, or null when unusable. */
    private static String normalise(String destinationId) {
        if (destinationId == null) {
            return null;
        }
        String id = destinationId.trim().toUpperCase(Locale.ROOT);
        return DEST_ID.matcher(id).matches() ? id : null;
    }

    /** In-memory mode for unit tests and no-home runs; null when persisting to disk. */
    public Path directory() {
        return dir;
    }
}
