/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.sqlworkbench;

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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit log of every SQL workbench execution (saiku#1107). One JSON line per run
 * written to {@code ${saiku.home}/logs/sql-workbench-audit.jsonl}. Mirrors
 * {@link org.saiku.service.olap.ai.audit.AiAuditLog} in shape and posture (append-only, never
 * throws on write failure, reads the file fresh each call since reads are admin/ops and not a hot
 * path) but is its own instance/file — a raw-SQL-against-the-warehouse trail is a distinct
 * concern from the AI surface's audit trail.
 */
public class SqlWorkbenchAuditLog {

    private static final Logger log = LoggerFactory.getLogger(SqlWorkbenchAuditLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** System property to disable auditing entirely (default ON — compliance). */
    public static final String PROP_ENABLED = "sqlworkbench.audit.enabled";
    /** System property to override the audit file location. */
    public static final String PROP_FILE = "sqlworkbench.audit.file";

    private final Path file;
    private final boolean enabled;
    private final ReentrantLock writeLock = new ReentrantLock();

    public SqlWorkbenchAuditLog() {
        this(resolveDefaultFile(), !"false".equalsIgnoreCase(System.getProperty(PROP_ENABLED, "true")));
        if (enabled) {
            log.info("SQL workbench audit log ENABLED -> {}", file);
        } else {
            log.info("SQL workbench audit log DISABLED ({}=false)", PROP_ENABLED);
        }
    }

    /** Explicit constructor for tests / programmatic wiring. */
    public SqlWorkbenchAuditLog(Path file, boolean enabled) {
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
        return base.resolve("logs").resolve("sql-workbench-audit.jsonl");
    }

    /**
     * Append one audit record. Stamps {@code ts} (UTC, second precision) if the caller left it
     * null. Never throws — a failure to audit must not break the query it audits, though a write
     * failure is itself logged loudly as it is a compliance gap.
     */
    public void record(SqlWorkbenchAuditEntry entry) {
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
                    "SQL workbench audit WRITE FAILED for {} {} — entry lost: {}",
                    entry.datasource,
                    entry.user,
                    e.toString());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Read recent audit entries newest-first, optionally filtered by user.
     *
     * @param limit max entries to return (clamped to [1, 5000]); 0/negative -> 100
     * @param offset entries to skip from the newest end (for pagination)
     * @param userFilter only entries for this user when non-blank
     */
    public List<SqlWorkbenchAuditEntry> recent(int limit, int offset, String userFilter) {
        int cap = limit <= 0 ? 100 : Math.min(limit, 5000);
        int skip = Math.max(0, offset);
        List<SqlWorkbenchAuditEntry> all = readAll();
        Collections.reverse(all);
        List<SqlWorkbenchAuditEntry> out = new ArrayList<>();
        int seen = 0;
        for (SqlWorkbenchAuditEntry e : all) {
            if (userFilter != null && !userFilter.isBlank() && !userFilter.equals(e.user)) {
                continue;
            }
            if (seen++ < skip) {
                continue;
            }
            out.add(e);
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    /** Total number of audit lines on disk (for the read endpoint's paging meta). */
    public long count() {
        return readAll().size();
    }

    private List<SqlWorkbenchAuditEntry> readAll() {
        List<SqlWorkbenchAuditEntry> out = new ArrayList<>();
        if (!Files.isReadable(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readValue(line, SqlWorkbenchAuditEntry.class));
                } catch (Exception parse) {
                    log.debug("Skipping unparseable audit line: {}", parse.toString());
                }
            }
        } catch (IOException e) {
            log.warn("SQL workbench audit read failed for {}: {}", file, e.toString());
        }
        return out;
    }
}
