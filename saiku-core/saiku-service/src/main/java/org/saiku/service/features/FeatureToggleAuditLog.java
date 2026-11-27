/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.features;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit trail of every feature-toggle change (saiku#2618). One JSON
 * line per admin write, written to {@code ${saiku.home}/logs/feature-toggle-audit.jsonl}.
 * Mirrors {@link org.saiku.service.sqlworkbench.SqlWorkbenchAuditLog} in shape and
 * posture (append-only, never throws on write failure, reads the file fresh each
 * call since reads are admin/ops and not a hot path) but is its own instance and
 * file: "who turned which product surface off for which roles" is a distinct
 * compliance concern from the SQL or AI audit trails.
 */
public class FeatureToggleAuditLog {

    private static final Logger log = LoggerFactory.getLogger(FeatureToggleAuditLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** System property to disable auditing entirely (default ON — compliance). */
    public static final String PROP_ENABLED = "features.audit.enabled";
    /** System property to override the audit file location. */
    public static final String PROP_FILE = "features.audit.file";
    /** Hard cap on entries a single read returns (the admin UI's read is bounded). */
    public static final int READ_LIMIT = 500;

    private final Path file;
    private final boolean enabled;
    private final ReentrantLock writeLock = new ReentrantLock();

    public FeatureToggleAuditLog() {
        this(resolveDefaultFile(), !"false".equalsIgnoreCase(System.getProperty(PROP_ENABLED, "true")));
        if (enabled) {
            log.info("Feature toggle audit log ENABLED -> {}", file);
        } else {
            log.info("Feature toggle audit log DISABLED ({}=false)", PROP_ENABLED);
        }
    }

    /** Explicit constructor for tests / programmatic wiring. */
    public FeatureToggleAuditLog(Path file, boolean enabled) {
        this.file = file;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path getFile() {
        return file;
    }

    private static Path resolveDefaultFile() {
        String override = System.getProperty(PROP_FILE);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("saiku.home");
        Path base = (home != null && !home.isEmpty()) ? Paths.get(home) : Paths.get("saiku-home");
        return base.resolve("logs").resolve("feature-toggle-audit.jsonl");
    }

    /**
     * Append one audit record. Stamps {@code ts} (UTC, second precision) when the
     * caller left it null. Never throws — a failure to audit must not fail the
     * admin write it records, though a write failure is logged loudly as it is a
     * compliance gap.
     */
    public void record(FeatureToggleAuditEntry entry) {
        if (!enabled || entry == null) {
            return;
        }
        if (entry.ts == null) {
            entry.ts = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        }
        writeLock.lock();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String line = MAPPER.writeValueAsString(entry) + "\n";
            Files.write(
                    file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error(
                    "Feature toggle audit WRITE FAILED for {} {} — entry lost: {}",
                    entry.feature,
                    entry.actor,
                    e.toString());
        } catch (RuntimeException e) {
            log.error("Feature toggle audit SERIALISATION FAILED — entry lost: {}", e.toString());
        }
    }

    /**
     * The most recent entries, oldest first, capped at {@link #READ_LIMIT}. Reads
     * the file fresh each call — an admin/ops read, not a hot path. Never throws.
     */
    public List<FeatureToggleAuditEntry> readRecent() {
        List<FeatureToggleAuditEntry> out = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                return out;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - READ_LIMIT);
            for (String line : lines.subList(from, lines.size())) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readValue(trimmed, FeatureToggleAuditEntry.class));
                } catch (IOException e) {
                    log.warn("Skipping unparseable feature toggle audit line: {}", e.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Feature toggle audit read failed: {}", e.toString());
        }
        return out;
    }
}
