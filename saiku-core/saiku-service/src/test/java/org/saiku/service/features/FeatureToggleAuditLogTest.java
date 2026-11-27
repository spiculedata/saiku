/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#2618 — unit coverage for the feature-toggle audit log (mirrors
 * {@link org.saiku.service.sqlworkbench.SqlWorkbenchAuditLogTest}): one JSON
 * line per admin change, timestamp stamped, never throwing on write, read-back
 * capped and skipping unparseable lines.
 */
public class FeatureToggleAuditLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FeatureToggleAuditLog newLog() {
        return new FeatureToggleAuditLog(
                tmp.getRoot().toPath().resolve("logs").resolve("feature-toggle-audit.jsonl"), true);
    }

    private static FeatureToggleAuditEntry entry(String actor, String feature, List<String> before, List<String> after) {
        FeatureToggleAuditEntry e = new FeatureToggleAuditEntry();
        e.actor = actor;
        e.feature = feature;
        e.action = after == null
                ? FeatureToggleAuditEntry.ACTION_RESET
                : FeatureToggleAuditEntry.ACTION_UPDATE;
        e.before = before;
        e.after = after;
        return e;
    }

    @Test
    public void records_and_reads_back() {
        FeatureToggleAuditLog auditLog = newLog();
        auditLog.record(entry("admin", "notebooks", null, List.of("ROLE_NOTEBOOKS")));
        auditLog.record(entry("admin", "notebooks", List.of("ROLE_NOTEBOOKS"), List.of("ROLE_USER")));

        List<FeatureToggleAuditEntry> recent = auditLog.readRecent();
        assertEquals(2, recent.size());
        assertEquals("notebooks", recent.get(0).feature);
        assertEquals("admin", recent.get(0).actor);
        assertNull(recent.get(0).before);
        assertEquals(List.of("ROLE_USER"), recent.get(1).after);
    }

    @Test
    public void stamps_ts_when_absent() {
        FeatureToggleAuditLog auditLog = newLog();
        auditLog.record(entry("admin", "apps", null, List.of()));
        FeatureToggleAuditEntry got = auditLog.readRecent().get(0);
        assertNotNull("ts is stamped on write", got.ts);
        assertTrue("ISO-8601 UTC", got.ts.endsWith("Z"));
    }

    @Test
    public void write_failure_never_propagates() {
        // The audit file path is a DIRECTORY: every write fails. record() must
        // swallow that (logging it loudly), never fail the admin write it records.
        java.nio.file.Path dir = tmp.getRoot().toPath().resolve("taken");
        dir.toFile().mkdirs();
        FeatureToggleAuditLog auditLog = new FeatureToggleAuditLog(dir, true);
        auditLog.record(entry("admin", "notebooks", null, List.of("ROLE_NOTEBOOKS")));
        assertTrue("an unwritable trail reads as empty, never throws", auditLog.readRecent().isEmpty());
    }

    @Test
    public void disabled_log_writes_nothing() {
        FeatureToggleAuditLog auditLog =
                new FeatureToggleAuditLog(tmp.getRoot().toPath().resolve("audit.jsonl"), false);
        auditLog.record(entry("admin", "notebooks", null, List.of("ROLE_NOTEBOOKS")));
        assertTrue(auditLog.readRecent().isEmpty());
    }

    @Test
    public void unparseable_lines_are_skipped_not_fatal() throws Exception {
        java.nio.file.Path file = tmp.getRoot().toPath().resolve("audit.jsonl");
        FeatureToggleAuditLog auditLog = new FeatureToggleAuditLog(file, true);
        auditLog.record(entry("admin", "apps", null, List.of()));
        Files.writeString(file, Files.readString(file) + "garbage line\n");
        auditLog.record(entry("admin", "dashboards", null, List.of("ROLE_VIEWER")));
        List<FeatureToggleAuditEntry> recent = auditLog.readRecent();
        assertEquals("the garbage line is skipped, the good lines survive", 2, recent.size());
        assertEquals("dashboards", recent.get(1).feature);
    }
}
