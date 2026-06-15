/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** saiku#906 — unit coverage for the AI audit log core. */
public class AiAuditLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AiAuditLog newLog() {
        return new AiAuditLog(tmp.getRoot().toPath().resolve("logs").resolve("ai-audit.jsonl"), true);
    }

    private static AiAuditEntry entry(String user, String outcome) {
        AiAuditEntry e = new AiAuditEntry();
        e.user = user;
        e.endpoint = "/saiku/api/ai/query";
        e.policy = "schema-only";
        e.policyDecision = AiAuditEntry.DECISION_ALLOW;
        e.cube = "Sales";
        e.schemaFingerprint = AiAuditLog.fingerprint(Arrays.asList("Store Sales", "Product Family"));
        e.promptTokens = 412;
        e.latencyMs = 2103L;
        e.outcome = outcome;
        return e;
    }

    @Test
    public void records_and_reads_back_newest_first() {
        AiAuditLog auditLog = newLog();
        auditLog.record(entry("alice", AiAuditEntry.OUTCOME_SUCCESS));
        auditLog.record(entry("bob", AiAuditEntry.OUTCOME_DENIED));

        List<AiAuditEntry> recent = auditLog.recent(10, 0, null, null);
        assertEquals(2, recent.size());
        assertEquals("bob", recent.get(0).user); // newest first
        assertEquals("alice", recent.get(1).user);
        assertEquals(2L, auditLog.count());
    }

    @Test
    public void stamps_ts_when_absent() {
        AiAuditLog auditLog = newLog();
        auditLog.record(entry("alice", AiAuditEntry.OUTCOME_SUCCESS));
        AiAuditEntry got = auditLog.recent(1, 0, null, null).get(0);
        assertNotNull("ts is stamped on write", got.ts);
        assertTrue("ISO-8601 UTC", got.ts.endsWith("Z"));
    }

    @Test
    public void append_only_never_overwrites() throws Exception {
        AiAuditLog auditLog = newLog();
        for (int i = 0; i < 5; i++) {
            auditLog.record(entry("u" + i, AiAuditEntry.OUTCOME_SUCCESS));
        }
        Path f = auditLog.getFile();
        long lines = Files.readAllLines(f).stream().filter(l -> !l.isBlank()).count();
        assertEquals("every record is its own appended line", 5, lines);
    }

    @Test
    public void never_logs_prompt_or_cell_content() throws Exception {
        // The audit line must carry shape only — no prompt text, no values.
        AiAuditLog auditLog = newLog();
        auditLog.record(entry("alice", AiAuditEntry.OUTCOME_SUCCESS));
        String raw = String.join("\n", Files.readAllLines(auditLog.getFile()));
        // The serialised entry has no field that could carry content.
        assertFalse(raw.toLowerCase().contains("prompt\":\"")); // no prompt text field
        assertTrue("fingerprint is a hash, not the names", raw.contains("sha256:"));
        // The raw schema names are NOT present — only their hash.
        assertFalse("schema names must not appear, only the fingerprint", raw.contains("Product Family"));
    }

    @Test
    public void filters_by_user_and_since() {
        AiAuditLog auditLog = newLog();
        AiAuditEntry old = entry("alice", AiAuditEntry.OUTCOME_SUCCESS);
        old.ts = "2026-01-01T00:00:00Z";
        auditLog.record(old);
        AiAuditEntry recentA = entry("alice", AiAuditEntry.OUTCOME_SUCCESS);
        recentA.ts = "2026-06-15T10:00:00Z";
        auditLog.record(recentA);
        AiAuditEntry recentB = entry("bob", AiAuditEntry.OUTCOME_SUCCESS);
        recentB.ts = "2026-06-15T11:00:00Z";
        auditLog.record(recentB);

        assertEquals(2, auditLog.recent(10, 0, "alice", null).size());
        assertEquals(1, auditLog.recent(10, 0, "bob", null).size());
        // since filter keeps only the two June entries
        assertEquals(2, auditLog.recent(10, 0, null, "2026-06-01T00:00:00Z").size());
    }

    @Test
    public void respects_limit_and_offset() {
        AiAuditLog auditLog = newLog();
        for (int i = 0; i < 5; i++) {
            AiAuditEntry e = entry("u" + i, AiAuditEntry.OUTCOME_SUCCESS);
            e.ts = "2026-06-15T10:0" + i + ":00Z";
            auditLog.record(e);
        }
        List<AiAuditEntry> page = auditLog.recent(2, 1, null, null); // skip newest, take 2
        assertEquals(2, page.size());
        assertEquals("u3", page.get(0).user); // newest is u4; offset 1 -> u3
        assertEquals("u2", page.get(1).user);
    }

    @Test
    public void disabled_is_a_noop() {
        AiAuditLog auditLog = new AiAuditLog(tmp.getRoot().toPath().resolve("off.jsonl"), false);
        auditLog.record(entry("alice", AiAuditEntry.OUTCOME_SUCCESS));
        assertEquals(0L, auditLog.count());
        assertTrue(auditLog.recent(10, 0, null, null).isEmpty());
    }

    @Test
    public void fingerprint_is_order_insensitive_and_deduped() {
        String a = AiAuditLog.fingerprint(Arrays.asList("Store Sales", "Product Family", "Year"));
        String b = AiAuditLog.fingerprint(Arrays.asList("Year", "Product Family", "Store Sales", "Year"));
        assertEquals("same name set -> same fingerprint regardless of order/dupes", a, b);
        assertTrue(a.startsWith("sha256:"));
        String c = AiAuditLog.fingerprint(Arrays.asList("Store Sales", "Product Family"));
        assertFalse("different name set -> different fingerprint", a.equals(c));
    }

    @Test
    public void fingerprint_null_or_empty_is_null() {
        assertNull(AiAuditLog.fingerprint(null));
        assertNull(AiAuditLog.fingerprint(java.util.Collections.emptyList()));
    }
}
