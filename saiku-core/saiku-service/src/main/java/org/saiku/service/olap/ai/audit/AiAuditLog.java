/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit log of every AI API call (saiku#906). One JSON line per
 * call written to {@code ${saiku.home}/logs/ai-audit.jsonl}, capturing the
 * call's <em>shape</em> ({@link AiAuditEntry}) — never the prompt content or any
 * cell values. Audit-trail is a compliance requirement (SOC 2, ISO 27001, NHS
 * DSPT, HIPAA): without it the answer to "what did your AI feature send
 * yesterday?" is "we don't know".
 *
 * <p><b>Append-only by design.</b> Records are never silently pruned or
 * overwritten — an audit log that drops its own entries fails the audit.
 * Daily rotation / cold archival is a deliberate follow-up (the issue's Phase
 * 2: a queryable structured store); v1 keeps a single growing JSONL that ops
 * rotate at the filesystem/log4j level.
 *
 * <p>Thread-safe: a per-instance lock serialises appends. {@link #recent} reads
 * the file fresh each call (audit reads are admin/ops, not hot-path). Failures
 * to write are logged but never thrown — auditing must not break the call it
 * audits, though a write failure is itself logged loudly as it is a compliance
 * gap.
 */
public class AiAuditLog {

    private static final Logger log = LoggerFactory.getLogger(AiAuditLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** System property to disable auditing entirely (default ON — compliance). */
    public static final String PROP_ENABLED = "ai.audit.enabled";
    /** System property to override the audit file location. */
    public static final String PROP_FILE = "ai.audit.file";

    private final Path file;
    private final boolean enabled;
    private final ReentrantLock writeLock = new ReentrantLock();

    /** Production constructor — resolves the file path + enabled flag from
     *  system properties / {@code saiku.home}, and logs the posture once. */
    public AiAuditLog() {
        this(resolveDefaultFile(), !"false".equalsIgnoreCase(System.getProperty(PROP_ENABLED, "true")));
        if (enabled) {
            log.info("AI audit log ENABLED -> {}", file);
        } else {
            log.info("AI audit log DISABLED ({}=false)", PROP_ENABLED);
        }
    }

    /** Explicit constructor for tests / programmatic wiring. */
    public AiAuditLog(Path file, boolean enabled) {
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
        return base.resolve("logs").resolve("ai-audit.jsonl");
    }

    /**
     * Append one audit record. Stamps {@code ts} (UTC, second precision) if the
     * caller left it null. Never throws — a failure to audit is logged but must
     * not break the AI call being audited.
     */
    public void record(AiAuditEntry entry) {
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
            // Loud: a missing audit entry is a compliance gap, not noise.
            log.error("AI audit WRITE FAILED for {} {} — entry lost: {}", entry.endpoint, entry.user, e.toString());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Read recent audit entries newest-first, optionally filtered. The file is
     * append-chronological, so we read all, filter, reverse, then page. Intended
     * for the admin read endpoint (low frequency); not a hot path.
     *
     * @param limit max entries to return (clamped to [1, 5000]); 0/negative → 100
     * @param offset entries to skip from the newest end (for pagination)
     * @param userFilter only entries for this user when non-blank
     * @param sinceIso only entries with {@code ts >= sinceIso} (ISO-8601 UTC) when non-blank
     */
    public List<AiAuditEntry> recent(int limit, int offset, String userFilter, String sinceIso) {
        int cap = limit <= 0 ? 100 : Math.min(limit, 5000);
        int skip = Math.max(0, offset);
        List<AiAuditEntry> all = readAll();
        Collections.reverse(all); // newest first
        List<AiAuditEntry> out = new ArrayList<>();
        int seen = 0;
        for (AiAuditEntry e : all) {
            if (userFilter != null && !userFilter.isBlank() && !userFilter.equals(e.user)) {
                continue;
            }
            if (sinceIso != null && !sinceIso.isBlank() && (e.ts == null || e.ts.compareTo(sinceIso) < 0)) {
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

    private List<AiAuditEntry> readAll() {
        List<AiAuditEntry> out = new ArrayList<>();
        if (!Files.isReadable(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readValue(line, AiAuditEntry.class));
                } catch (Exception parse) {
                    log.debug("Skipping unparseable audit line: {}", parse.toString());
                }
            }
        } catch (IOException e) {
            log.warn("AI audit read failed for {}: {}", file, e.toString());
        }
        return out;
    }

    /**
     * Fingerprint a set of schema names (dims / hierarchies / levels / measures)
     * sent to the AI surface. Sorted + de-duped so the digest is order-stable,
     * then SHA-256. Returns {@code sha256:<hex>}, or null for an empty/null set.
     * The point is to prove <em>what shape</em> of metadata crossed without
     * storing the metadata itself.
     */
    public static String fingerprint(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                sorted.add(n);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS — unreachable.
            return null;
        }
    }
}
