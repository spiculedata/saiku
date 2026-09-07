/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for {@link JobStore} (saiku#1809, PR1 — dormant job model + file-backed store).
 *
 * <p>Locks the FS behaviour mirrored from {@code ShareTokenStore}: round-trip save/load, owner-scoped
 * listing, delete, enable-flip, unguessable+traversal-safe ids, in-memory fallback, and atomic-write
 * hygiene (no {@code .tmp} left behind). Nothing here runs or sends a job — there is no engine.
 */
class JobStoreTest {

    private JobStore store(Path home) {
        return new JobStore(home.toString());
    }

    private JobSchedule everyMinute() {
        return new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "Europe/London");
    }

    private ScheduledJobFile createTypical(JobStore s, String owner) {
        return s.create(
                owner,
                List.of("ROLE_ADMIN"),
                everyMinute(),
                "EMAIL_SUBSCRIPTION",
                Map.of("dashboard", "/homes/admin/q.saikudash", "format", "pdf"),
                true);
    }

    @Test
    void listByOwner_matches_case_insensitively(@TempDir Path home) {
        // saiku#1907 F4: owner identity is compared case-insensitively, so a job created by
        // "admin" is manageable/listable by the canonical identity regardless of the case a
        // pre-existing record was stored under.
        JobStore s = store(home);
        createTypical(s, "admin");
        assertEquals(1, s.listByOwner("Admin").size(), "listByOwner must match owner case-insensitively");
        assertEquals(1, s.listByOwner("admin").size());
        assertTrue(s.listByOwner("someoneelse").isEmpty(), "a different owner must not match");
    }

    @Test
    void create_then_load_roundtrips(@TempDir Path home) {
        JobStore s = store(home);
        ScheduledJobFile j = createTypical(s, "admin");
        assertNotNull(j.getId());
        assertTrue(j.getId().matches("^[A-Za-z0-9_-]{16,64}$"), "job id must be URL-safe and long");

        ScheduledJobFile got = s.load(j.getId());
        assertNotNull(got, "minted job must load back");
        assertEquals("admin", got.getOwnerUsername());
        assertEquals(List.of("ROLE_ADMIN"), got.getOwnerRolesSnapshot());
        assertEquals("EMAIL_SUBSCRIPTION", got.getType());
        assertEquals(JobSchedule.Kind.FIXED_INTERVAL, got.getSchedule().getKind());
        assertEquals(60_000L, got.getSchedule().getIntervalMillis());
        assertEquals("Europe/London", got.getSchedule().getTimezone());
        assertNull(got.getSchedule().getCronExpression(), "cron field is a forward-compat placeholder, unset");
        assertEquals("/homes/admin/q.saikudash", got.getPayload().get("dashboard"));
        assertTrue(got.isEnabled());
        assertTrue(got.getCreatedAt() > 0);
        // Dormant: no engine ran it, so run-bookkeeping is untouched.
        assertEquals(0, got.getLastRunAt());
        assertNull(got.getLastStatus());
        assertNull(got.getLastError());
        assertEquals(0, got.getConsecutiveFailures());
    }

    @Test
    void listByOwner_scopes_to_owner(@TempDir Path home) {
        JobStore s = store(home);
        createTypical(s, "admin");
        createTypical(s, "admin");
        createTypical(s, "bob");

        List<ScheduledJobFile> adminJobs = s.listByOwner("admin");
        assertEquals(2, adminJobs.size());
        assertTrue(adminJobs.stream().allMatch(j -> "admin".equals(j.getOwnerUsername())));
        assertEquals(1, s.listByOwner("bob").size());
        assertEquals(3, s.listAll().size());
    }

    @Test
    void save_persists_updates(@TempDir Path home) {
        JobStore s = store(home);
        ScheduledJobFile j = createTypical(s, "admin");
        j.setType("THRESHOLD_ALERT");
        j.getSchedule().setIntervalMillis(120_000L);
        s.save(j);

        ScheduledJobFile got = s.load(j.getId());
        assertEquals("THRESHOLD_ALERT", got.getType());
        assertEquals(120_000L, got.getSchedule().getIntervalMillis());
    }

    @Test
    void setEnabled_flips_and_persists(@TempDir Path home) {
        JobStore s = store(home);
        ScheduledJobFile j = createTypical(s, "admin");
        assertTrue(j.isEnabled());

        ScheduledJobFile updated = s.setEnabled(j.getId(), false);
        assertNotNull(updated);
        assertFalse(updated.isEnabled());
        assertFalse(s.load(j.getId()).isEnabled(), "disabled state must persist");
        assertNull(s.setEnabled("doesnotexistbutvalidlen0001", true), "unknown id → null");
    }

    @Test
    void delete_removes_job(@TempDir Path home) {
        JobStore s = store(home);
        ScheduledJobFile j = createTypical(s, "admin");
        assertTrue(s.delete(j.getId()));
        assertNull(s.load(j.getId()));
        assertFalse(s.delete(j.getId()), "delete of already-gone id is false");
    }

    @Test
    void load_and_delete_reject_traversal_and_malformed_ids(@TempDir Path home) {
        JobStore s = store(home);
        for (String bad : new String[] {
            "../secret",
            "..\\secret",
            "a/b",
            "a\\b",
            "%2F%2Fetc",
            "/etc/passwd",
            "C:\\windows",
            "with space",
            "",
            "short",
            "a.b",
            "../../etc/passwd"
        }) {
            assertNull(s.load(bad), "malformed/traversal id must not resolve: " + bad);
            assertFalse(s.delete(bad), "malformed/traversal id must not delete: " + bad);
        }
    }

    @Test
    void setEnabled_rejects_traversal_and_malformed_ids(@TempDir Path home) {
        JobStore s = store(home);
        Path dir = home.resolve("jobs");
        for (String bad : new String[] {"../escape", "..\\escape", "a/b", "/etc/passwd", "C:\\windows", "", "short"}) {
            // A malformed id must be rejected outright — no record, no path escape, no throw.
            assertNull(s.setEnabled(bad, true), "malformed/traversal id must not toggle: " + bad);
        }
        // Belt-and-braces: nothing was written into (or above) the jobs dir by the rejected calls.
        try (Stream<Path> paths = Files.list(dir)) {
            assertEquals(0, paths.count(), "rejected setEnabled ids must not have written any file");
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    void id_regex_length_boundaries(@TempDir Path home) throws Exception {
        JobStore s = store(home);
        Path dir = home.resolve("jobs");
        // JOB_ID is {16,64}. Plant a real record at a valid-length id, then probe the four boundaries.
        String base = "A".repeat(64);
        // 15 chars → rejected (below the min).
        assertNull(s.load(base.substring(0, 15)), "15-char id is below the {16,64} min and must be rejected");
        assertFalse(s.delete(base.substring(0, 15)));
        // 65 chars → rejected (above the max) — this branch previously had zero coverage.
        assertNull(s.load(base + "A"), "65-char id is above the {16,64} max and must be rejected");
        assertFalse(s.delete(base + "A"));
        // 16 chars → accepted: a real file at that id loads back.
        String id16 = "B".repeat(16);
        Files.writeString(dir.resolve(id16 + ".json"), "{\"id\":\"" + id16 + "\",\"ownerUsername\":\"admin\"}");
        assertNotNull(s.load(id16), "16-char id is the {16,64} min and must be accepted");
        // 64 chars → accepted.
        String id64 = "C".repeat(64);
        Files.writeString(dir.resolve(id64 + ".json"), "{\"id\":\"" + id64 + "\",\"ownerUsername\":\"admin\"}");
        assertNotNull(s.load(id64), "64-char id is the {16,64} max and must be accepted");
    }

    @Test
    void corruptFile_load_returnsNull_and_listAll_skips_neverThrows(@TempDir Path home) throws Exception {
        JobStore s = store(home);
        Path dir = home.resolve("jobs");
        // A valid, loadable job so we can prove listAll returns the good ones while skipping the bad.
        ScheduledJobFile good = createTypical(s, "admin");

        // Plant a garbage .json at a syntactically valid id — unparseable content.
        String corruptId = "D".repeat(20);
        Files.writeString(dir.resolve(corruptId + ".json"), "this is not json {{{");

        // load() on the corrupt id swallows the parse error and returns null — never throws.
        assertNull(assertDoesNotThrow(() -> s.load(corruptId)), "corrupt file must load as null, not throw");

        // listAll() skips the unreadable file and still returns the good record — never throws.
        List<ScheduledJobFile> all = assertDoesNotThrow(s::listAll);
        assertEquals(1, all.size(), "listAll must skip the corrupt file and keep the good one");
        assertEquals(good.getId(), all.get(0).getId());
    }

    @Test
    void save_rejects_missing_or_invalid_id(@TempDir Path home) {
        JobStore s = store(home);
        ScheduledJobFile noId = new ScheduledJobFile();
        assertThrows(IllegalArgumentException.class, () -> s.save(noId));
        assertThrows(IllegalArgumentException.class, () -> s.save(null));
        ScheduledJobFile badId = new ScheduledJobFile();
        badId.setId("../escape");
        assertThrows(IllegalArgumentException.class, () -> s.save(badId));
    }

    @Test
    void persist_leaves_no_tmp_files(@TempDir Path home) throws Exception {
        JobStore s = store(home);
        createTypical(s, "admin");
        Path dir = home.resolve("jobs");
        try (Stream<Path> paths = Files.list(dir)) {
            assertTrue(
                    paths.noneMatch(p -> p.toString().endsWith(".tmp")),
                    "no .tmp file must be left behind after an atomic write");
        }
    }

    @Test
    void in_memory_fallback_when_no_home() {
        JobStore s = new JobStore((String) null);
        ScheduledJobFile j = createTypical(s, "admin");
        assertNotNull(s.load(j.getId()));
        assertEquals(1, s.listByOwner("admin").size());
        assertNotNull(s.setEnabled(j.getId(), false));
        assertFalse(s.load(j.getId()).isEnabled());
        assertTrue(s.delete(j.getId()));
        assertNull(s.load(j.getId()));
    }

    @Test
    void view_omits_payload_values_but_keeps_keys(@TempDir Path home) {
        JobStore s = store(home);
        ScheduledJobFile j = createTypical(s, "admin");
        ScheduledJobView view = ScheduledJobView.of(j);
        assertEquals(j.getId(), view.id());
        assertEquals("admin", view.ownerUsername());
        assertEquals("EMAIL_SUBSCRIPTION", view.type());
        // Payload values must never appear in the redacted view — only the key names.
        assertTrue(view.payloadKeys().containsAll(List.of("dashboard", "format")));
        assertFalse(view.toString().contains("/homes/admin/q.saikudash"), "payload values must not leak via the view");
    }
}
