/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.sqlworkbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** saiku#1107 — unit coverage for the SQL workbench audit log core, mirroring AiAuditLogTest. */
public class SqlWorkbenchAuditLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SqlWorkbenchAuditLog newLog() {
        return new SqlWorkbenchAuditLog(
                tmp.getRoot().toPath().resolve("logs").resolve("sql-workbench-audit.jsonl"), true);
    }

    private static SqlWorkbenchAuditEntry entry(String user, String outcome) {
        SqlWorkbenchAuditEntry e = new SqlWorkbenchAuditEntry();
        e.user = user;
        e.datasource = "foodmart";
        e.sql = "SELECT 1";
        e.rowCount = 1;
        e.latencyMs = 12L;
        e.outcome = outcome;
        return e;
    }

    @Test
    public void records_and_reads_back_newest_first() {
        SqlWorkbenchAuditLog auditLog = newLog();
        auditLog.record(entry("alice", SqlWorkbenchAuditEntry.OUTCOME_SUCCESS));
        auditLog.record(entry("bob", SqlWorkbenchAuditEntry.OUTCOME_REJECTED));

        List<SqlWorkbenchAuditEntry> recent = auditLog.recent(10, 0, null);
        assertEquals(2, recent.size());
        assertEquals("bob", recent.get(0).user);
        assertEquals("alice", recent.get(1).user);
        assertEquals(2L, auditLog.count());
    }

    @Test
    public void stamps_ts_when_absent() {
        SqlWorkbenchAuditLog auditLog = newLog();
        auditLog.record(entry("alice", SqlWorkbenchAuditEntry.OUTCOME_SUCCESS));
        SqlWorkbenchAuditEntry got = auditLog.recent(1, 0, null).get(0);
        assertNotNull("ts is stamped on write", got.ts);
        assertTrue("ISO-8601 UTC", got.ts.endsWith("Z"));
    }

    @Test
    public void filters_by_user() {
        SqlWorkbenchAuditLog auditLog = newLog();
        auditLog.record(entry("alice", SqlWorkbenchAuditEntry.OUTCOME_SUCCESS));
        auditLog.record(entry("bob", SqlWorkbenchAuditEntry.OUTCOME_SUCCESS));

        List<SqlWorkbenchAuditEntry> recent = auditLog.recent(10, 0, "bob");
        assertEquals(1, recent.size());
        assertEquals("bob", recent.get(0).user);
    }

    @Test
    public void disabled_log_records_nothing() {
        SqlWorkbenchAuditLog auditLog = new SqlWorkbenchAuditLog(
                tmp.getRoot().toPath().resolve("logs").resolve("sql-workbench-audit.jsonl"), false);
        auditLog.record(entry("alice", SqlWorkbenchAuditEntry.OUTCOME_SUCCESS));
        assertEquals(0L, auditLog.count());
    }

    @Test
    public void retains_the_sql_text_unlike_the_ai_audit_log() {
        // The SQL workbench trail deliberately keeps the SQL text (see SqlWorkbenchAuditEntry) —
        // that's the point of "what ran against the warehouse", unlike the AI audit log which
        // withholds prompt content.
        SqlWorkbenchAuditLog auditLog = newLog();
        SqlWorkbenchAuditEntry e = entry("alice", SqlWorkbenchAuditEntry.OUTCOME_SUCCESS);
        e.sql = "SELECT * FROM customer WHERE id = 42";
        auditLog.record(e);
        assertEquals("SELECT * FROM customer WHERE id = 42", auditLog.recent(1, 0, null).get(0).sql);
    }
}
