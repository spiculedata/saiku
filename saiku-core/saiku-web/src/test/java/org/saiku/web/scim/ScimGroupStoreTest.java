/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Unit tests for {@link ScimGroupStore} file-backed persistence and lookups. */
public class ScimGroupStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ScimGroupStore store(String home) {
        return new ScimGroupStore(home);
    }

    @Test
    public void createPersistsAndReloadsAcrossInstances() {
        String home = folder.getRoot().getAbsolutePath();
        ScimGroupStore first = store(home);
        ScimGroupStore.GroupRecord r = first.create("Data Team", List.of("ana", "bob"), 100L);
        assertNotNull(r.id);
        assertEquals("Data Team", r.displayName);
        assertEquals(100L, r.created);

        ScimGroupStore second = store(home);
        ScimGroupStore.GroupRecord loaded = second.load(r.id);
        assertNotNull(loaded);
        assertEquals("Data Team", loaded.displayName);
        assertEquals(List.of("ana", "bob"), loaded.members);
    }

    @Test
    public void loadRejectsUnsafeIds() {
        ScimGroupStore s = store(folder.getRoot().getAbsolutePath());
        assertNull(s.load("../escape"));
        assertNull(s.load(null));
        assertNull(s.load("missing-id"));
    }

    @Test
    public void listAllSortsByDisplayNameCaseInsensitively() {
        String home = folder.getRoot().getAbsolutePath();
        ScimGroupStore s = store(home);
        s.create("zeta", List.of(), 1L);
        s.create("Alpha", List.of(), 2L);
        s.create("midway", List.of(), 3L);

        List<ScimGroupStore.GroupRecord> all = s.listAll();
        assertEquals(3, all.size());
        assertEquals("Alpha", all.get(0).displayName);
        assertEquals("midway", all.get(1).displayName);
        assertEquals("zeta", all.get(2).displayName);
    }

    @Test
    public void listAllSkipsUnreadableRecords() throws Exception {
        String home = folder.getRoot().getAbsolutePath();
        ScimGroupStore s = store(home);
        s.create("Good", List.of(), 1L);
        java.nio.file.Files.write(
                java.nio.file.Paths.get(home, "scim-groups", "bad.json"),
                "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(1, s.listAll().size());
    }

    @Test
    public void findByDisplayNameIsCaseInsensitiveAndNullSafe() {
        String home = folder.getRoot().getAbsolutePath();
        ScimGroupStore s = store(home);
        s.create("Data Team", List.of(), 1L);

        assertNotNull(s.findByDisplayName("data team"));
        assertNull(s.findByDisplayName("nope"));
        assertNull(s.findByDisplayName(null));
        assertNull(s.findByDisplayName(" "));
    }

    @Test
    public void saveUpdatesAndDeleteRemoves() {
        String home = folder.getRoot().getAbsolutePath();
        ScimGroupStore s = store(home);
        ScimGroupStore.GroupRecord r = s.create("Crew", List.of("ana"), 1L);

        r.members.add("bob");
        r.lastModified = 5L;
        s.save(r);

        ScimGroupStore.GroupRecord reloaded = s.load(r.id);
        assertEquals(List.of("ana", "bob"), reloaded.members);
        // lastModified is stamped with the wall clock on save, not trusted from the caller.
        assertTrue(reloaded.lastModified >= reloaded.created);

        assertTrue(s.delete(r.id));
        assertFalse(s.delete(r.id));
        assertNull(s.load(r.id));
    }

    @Test
    public void nullHomeFallsBackToMemory() {
        ScimGroupStore s = store(null);
        ScimGroupStore.GroupRecord r = s.create("Mem", List.of(), 1L);
        assertEquals(1, s.listAll().size());
        assertNotNull(s.load(r.id));
        assertTrue(s.delete(r.id));
        assertEquals(0, s.listAll().size());
    }

    @Test
    public void aHomeThatCannotBeCreatedFallsBackToMemory() throws Exception {
        // A path whose parent is a FILE can never become a directory.
        java.nio.file.Path blocker = java.nio.file.Paths.get(folder.getRoot().getAbsolutePath(), "blocker");
        java.nio.file.Files.write(blocker, "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String home = blocker.resolve("home").toString();

        ScimGroupStore s = store(home);
        // Constructor must fall back to memory instead of throwing.
        ScimGroupStore.GroupRecord r = s.create("X", List.of(), 1L);
        assertNotNull(r);
        assertEquals(1, s.listAll().size());
    }
}
