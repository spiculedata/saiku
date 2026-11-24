/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Regression coverage for saiku#2525: the {@code acl.json} sidecar that {@link Acl2} keeps inside
 * the folder it protect, keyed by absolute path, used to be readable and writable as ordinary
 * repository content, and a folder move left every entry keyed under the old absolute path —
 * dropping the protection behind it. A user with WRITE (but no GRANT) on a shared folder could
 * take ownership of it, read files the owner marked PRIVATE and lock the owner out; any reader
 * could read the sidecar and learn the server's absolute saiku-home path, every grantee and the
 * names of private files.
 *
 * <p>Post-fix: the sidecar is reserved on every content door of the manager (save, internal
 * saves, remove, internal remove, read, internal reads, ACL API, folder delete, both ends of
 * move); a move of a node that carries ACL state needs GRANT on the source and re-keys every
 * entry under the new path; a moved file's per-file entry migrates to the destination parent's
 * sidecar; a delete drops the stale entry; and listings never contain the sidecar.
 */
public class FilesystemRepositoryManagerAclSidecarTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final TypeReference<Map<String, AclEntry>> ACL_MAP_TYPE =
            new TypeReference<Map<String, AclEntry>>() {};

    private FilesystemRepositoryManager manager;
    private File datadir;
    private File adminHomeDir;

    private static final List<String> ROLES_USER = Collections.singletonList("ROLE_USER");
    private static final List<String> ROLES_ADMIN = Arrays.asList("ROLE_USER", "ROLE_ADMIN");

    @Before
    public void setUp() throws Exception {
        resetSingleton();

        datadir = tmp.newFolder("repo");
        // <datadir>/unknown/etc bootstrap markers so getDatadir()'s lazy setup branch is skipped
        // (same trick the ACL / traversal tests use); repo paths like "/homes/admin/x" resolve
        // under <datadir>/unknown.
        File unknownEtc = new File(datadir, "unknown/etc");
        if (!unknownEtc.mkdirs()) {
            throw new IllegalStateException("Could not create " + unknownEtc);
        }
        adminHomeDir = new File(datadir, "unknown/homes/admin");
        if (!adminHomeDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + adminHomeDir);
        }
        writeAcl(adminHomeDir, Map.of(adminHomeDir.getPath(), new AclEntry("admin", AclType.PRIVATE, null, null)));

        manager = newManager(datadir.getAbsolutePath());
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(manager, us);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    // ---- the sidecar is never content ----------------------------------------------------

    @Test
    public void sidecar_save_is_refused_even_for_the_owner() throws Exception {
        String before = sidecarContent(adminHomeDir);
        try {
            manager.saveFile("x", "/homes/admin/acl.json", "admin", "nt:saikufiles", ROLES_ADMIN);
            fail("writing the sidecar as content must be refused even for the owner/admin");
        } catch (SaikuServiceException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("reserved"));
        }
        assertEquals("the sidecar must be untouched", before, sidecarContent(adminHomeDir));

        // Case and spelling variants are refused too.
        for (String path : new String[] {"/homes/admin/ACL.JSON", "/homes/admin/sub/acl.json/"}) {
            try {
                manager.saveFile("x", path, "admin", "nt:saikufiles", ROLES_ADMIN);
                fail("the sidecar reservation must not be case/spelling-sensitive: " + path);
            } catch (SaikuServiceException expected) {
                // expected
            }
        }
        assertFalse("the case variant must not have been created", new File(adminHomeDir, "ACL.JSON").exists());
        assertFalse("no acl.json may be created in a subfolder", new File(adminHomeDir, "sub/acl.json").exists());

        // The folder-create branch (null content) is refused as well.
        try {
            manager.saveFile(null, "/homes/admin/acl.json", "admin", "nt:saikufiles", ROLES_ADMIN);
            fail("creating a FOLDER named acl.json must be refused");
        } catch (SaikuServiceException expected) {
            // expected
        }
        // Ordinary saves still work.
        manager.saveFile("ok", "/homes/admin/ordinary.txt", "admin", "nt:saikufiles", ROLES_ADMIN);
        assertEquals("ok", new String(Files.readAllBytes(new File(adminHomeDir, "ordinary.txt").toPath())));
    }

    @Test
    public void sidecar_internal_save_is_refused_even_for_the_owner() throws Exception {
        try {
            manager.saveInternalFile("x", "/homes/admin/acl.json", "nt:saikufiles");
            fail("the dashboards/apps/notebooks save door must refuse a sidecar write");
        } catch (SaikuServiceException expected) {
            // expected
        }
        try {
            manager.saveBinaryInternalFile(
                    new java.io.ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                    "/homes/admin/ACL.JSON",
                    "nt:saikufiles");
            fail("the binary save door must refuse a sidecar write (any case)");
        } catch (SaikuServiceException expected) {
            // expected
        }
    }

    @Test
    public void sidecar_read_is_refused_even_for_the_owner() throws Exception {
        for (String path : new String[] {"/homes/admin/acl.json", "/homes/admin/Acl.Json"}) {
            try {
                manager.getFile(path, "admin", ROLES_ADMIN);
                fail("reading the sidecar as content must be refused even for the owner/admin: " + path);
            } catch (Exception expected) {
                // RepositoryException / SaikuServiceException — any abort is acceptable.
            }
            try {
                manager.getInternalFile(path);
                fail("the internal read door must refuse the sidecar: " + path);
            } catch (Exception expected) {
                // expected
            }
            try {
                manager.getBinaryInternalFile(path);
                fail("the binary read door must refuse the sidecar: " + path);
            } catch (Exception expected) {
                // expected
            }
        }
    }

    @Test
    public void sidecar_remove_is_refused_even_for_the_owner() throws Exception {
        String before = sidecarContent(adminHomeDir);
        try {
            manager.removeFile("/homes/admin/acl.json", "admin", ROLES_ADMIN);
            fail("deleting the sidecar (wiping every share) must be refused");
        } catch (SaikuServiceException expected) {
            // expected
        }
        try {
            manager.removeInternalFile("/homes/admin/acl.json");
            fail("the internal remove door must refuse the sidecar");
        } catch (SaikuServiceException expected) {
            // expected
        }
        try {
            manager.deleteFolder("/homes/admin/acl.json");
            fail("deleteFolder must refuse the sidecar");
        } catch (SaikuServiceException expected) {
            // expected
        }
        assertEquals("the sidecar must survive every refused delete", before, sidecarContent(adminHomeDir));
    }

    @Test
    public void sidecar_cannot_be_move_source_or_target() throws Exception {
        try {
            manager.moveFile("/homes/admin/acl.json", "/homes/admin/taken.json", "admin", ROLES_ADMIN);
            fail("moving the sidecar away must be refused (it strips the folder's entries)");
        } catch (SaikuServiceException expected) {
            // expected
        }
        manager.saveFile("x", "/homes/admin/ordinary.txt", "admin", "nt:saikufiles", ROLES_ADMIN);
        try {
            manager.moveFile("/homes/admin/ordinary.txt", "/homes/admin/acl.json", "admin", ROLES_ADMIN);
            fail("overwriting the sidecar through a move target must be refused");
        } catch (SaikuServiceException expected) {
            // expected
        }
        assertTrue(new File(adminHomeDir, "acl.json").exists());
        assertEquals("x", new String(Files.readAllBytes(new File(adminHomeDir, "ordinary.txt").toPath())));
    }

    @Test
    public void listing_never_contains_the_sidecar() throws Exception {
        File shared = new File(adminHomeDir, "shared");
        assertTrue(shared.mkdirs());
        writeAcl(shared, Map.of(shared.getPath(), new AclEntry("admin", AclType.PRIVATE, null, null)));

        // A non-empty type filter is what makes FILES appear in the listing (folders are always
        // listed): .json matches the sidecar's name, so a regression would list it.
        List<IRepositoryObject> objects = manager.getAllFiles(Collections.singletonList(".json"), "admin", ROLES_ADMIN);
        assertNotNull(objects);
        for (IRepositoryObject o : objects) {
            assertFalse("the sidecar must never be listed: " + o.getName(), "acl.json".equalsIgnoreCase(o.getName()));
        }
        try {
            manager.getAllFiles(Collections.emptyList(), "admin", ROLES_ADMIN, "/homes/admin/acl.json");
            fail("a listing aimed at the sidecar must be refused");
        } catch (Exception expected) {
            // expected
        }
    }

    // ---- the ACL API is the only way to change ACLs ---------------------------------------

    @Test
    public void write_only_user_cannot_reacl_the_folder() throws Exception {
        seedSkeleton();
        File shared = sharedFolderForBob();

        // bob has READ+WRITE on the folder — but no GRANT — so his attempt to take ownership is a
        // silent no-op (setACL only acts for callers with GRANT).
        manager.setACL(
                "/homes/admin/shared",
                new ObjectMapper().writeValueAsString(new AclEntry("bob", AclType.PRIVATE, null, null)),
                "bob",
                ROLES_USER);
        Map<String, AclEntry> sidecar = readAcl(new File(shared, "acl.json"));
        assertEquals(
                "a WRITE-only grantee must not be able to take ownership",
                "admin",
                lookupEntry(sidecar, shared.getPath()).getOwner());

        assertNull(
                "a WRITE-only grantee cannot even read the ACL state",
                manager.getACL("/homes/admin/shared", "bob", ROLES_USER));
    }

    @Test
    public void owner_can_still_read_and_reacl_own_folder() throws Exception {
        seedSkeleton();
        File shared = sharedFolderForBob();
        Files.write(new File(shared, "doc.txt").toPath(), "SHARED_DOC".getBytes(StandardCharsets.UTF_8));

        AclEntry acl = manager.getACL("/homes/admin/shared", "admin", ROLES_ADMIN);
        assertNotNull("the owner must still be able to read their folder's ACL", acl);
        assertEquals("admin", acl.getOwner());
        assertEquals("SHARED_DOC", manager.getFile("/homes/admin/shared/doc.txt", "admin", ROLES_ADMIN));

        // And the owner can re-ACL it.
        manager.setACL(
                "/homes/admin/shared",
                new ObjectMapper().writeValueAsString(new AclEntry("admin", AclType.SECURED, null, null)),
                "admin",
                ROLES_ADMIN);
        assertEquals(
                "admin",
                manager.getACL("/homes/admin/shared", "admin", ROLES_ADMIN).getOwner());
    }

    // ---- moves -----------------------------------------------------------------------------

    @Test
    public void write_only_user_cannot_move_protected_folder() throws Exception {
        seedSkeleton();
        File shared = sharedFolderForBob();
        File inner = new File(shared, "inner");
        assertTrue(inner.mkdirs());
        writeAcl(inner, Map.of(inner.getPath(), new AclEntry("admin", AclType.PRIVATE, null, null)));
        Files.write(new File(inner, "deep.txt").toPath(), "PRIVATE".getBytes(StandardCharsets.UTF_8));

        try {
            manager.moveFile("/homes/admin/shared", "/homes/bob/stolen", "bob", ROLES_USER);
            fail("a WRITE-only grantee must not be able to move a folder that carries ACL entries");
        } catch (Exception expected) {
            // SaikuServiceException — GRANT required on the source.
        }
        assertTrue("the folder must be untouched after a denied move", new File(shared, "acl.json").exists());
        assertTrue("the PRIVATE subfolder must be untouched after a denied move", new File(inner, "acl.json").exists());
        assertFalse(
                "the denied move must not have planted anything in bob's home",
                new File(new File(datadir, "unknown/homes/bob"), "stolen").exists());
    }

    @Test
    public void owner_move_keeps_protection_and_rekeys_entries() throws Exception {
        seedSkeleton();
        File shared = sharedFolderForBob();
        File inner = new File(shared, "inner");
        assertTrue(inner.mkdirs());
        writeAcl(inner, Map.of(inner.getPath(), new AclEntry("admin", AclType.PRIVATE, null, null)));
        Files.write(new File(inner, "deep.txt").toPath(), "PRIVATE".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(shared, "doc.txt").toPath(), "SHARED_DOC".getBytes(StandardCharsets.UTF_8));

        manager.moveFile("/homes/admin/shared", "/homes/admin/shared2", "admin", ROLES_ADMIN);

        File shared2 = new File(adminHomeDir, "shared2");
        assertTrue("the moved folder must exist", new File(shared2, "doc.txt").exists());
        assertFalse("the old folder must be gone", shared.exists());
        assertTrue("the sidecar must have travelled with the folder", new File(shared2, "acl.json").exists());

        // Entries are re-keyed under the new path — the folder's own entry, and the subfolder's.
        Map<String, AclEntry> sidecar = readAcl(new File(shared2, "acl.json"));
        AclEntry movedEntry = lookupEntry(sidecar, shared2.getPath());
        assertNotNull("the folder's own entry must be re-keyed to the new path", movedEntry);
        assertEquals("admin", movedEntry.getOwner());
        assertEquals(AclType.SECURED, movedEntry.getType());
        assertNull("the old key must be gone", lookupEntry(sidecar, shared.getPath()));
        Map<String, AclEntry> innerSidecar = readAcl(new File(shared2, "inner/acl.json"));
        assertNotNull(
                "the subfolder's entry must be re-keyed under the new path",
                lookupEntry(innerSidecar, new File(shared2, "inner").getPath()));

        // ... and the protection is still in force at the new location: bob (SECURED READ) reads
        // the shared file, but the PRIVATE subfolder stays locked.
        assertEquals("SHARED_DOC", manager.getFile("/homes/admin/shared2/doc.txt", "bob", ROLES_USER));
        try {
            manager.getFile("/homes/admin/shared2/inner/deep.txt", "bob", ROLES_USER);
            fail("the moved PRIVATE subfolder must still deny a non-owner");
        } catch (Exception expected) {
            // expected
        }
        assertEquals(
                "the owner still reads their moved PRIVATE subfolder",
                "PRIVATE",
                manager.getFile("/homes/admin/shared2/inner/deep.txt", "admin", ROLES_ADMIN));
    }

    @Test
    public void moved_file_keeps_its_per_file_entry() throws Exception {
        // saveFile stamps every new file under a home with a PRIVATE per-file entry.
        manager.saveFile("BODY", "/homes/admin/movable.txt", "admin", "nt:saikufiles", ROLES_ADMIN);
        manager.moveFile("/homes/admin/movable.txt", "/homes/admin/moved.txt", "admin", ROLES_ADMIN);

        Map<String, AclEntry> sidecar = readAcl(new File(adminHomeDir, "acl.json"));
        AclEntry moved = lookupEntry(sidecar, new File(adminHomeDir, "moved.txt").getPath());
        assertNotNull("the per-file entry must follow the file to the new path", moved);
        assertEquals("admin", moved.getOwner());
        assertEquals(AclType.PRIVATE, moved.getType());
        assertNull(
                "the stale key for the old path must be gone",
                lookupEntry(sidecar, new File(adminHomeDir, "movable.txt").getPath()));

        try {
            manager.getFile("/homes/admin/moved.txt", "bob", ROLES_USER);
            fail("the moved file must still deny a non-owner (its PRIVATE entry came along)");
        } catch (Exception expected) {
            // expected
        }
    }

    @Test
    public void delete_removes_the_stale_entry() throws Exception {
        manager.saveFile("BODY", "/homes/admin/temp.txt", "admin", "nt:saikufiles", ROLES_ADMIN);
        File temp = new File(adminHomeDir, "temp.txt");
        Map<String, AclEntry> before = readAcl(new File(adminHomeDir, "acl.json"));
        assertNotNull(lookupEntry(before, temp.getPath()));

        manager.removeFile("/homes/admin/temp.txt", "admin", ROLES_ADMIN);

        assertFalse(temp.exists());
        Map<String, AclEntry> after = readAcl(new File(adminHomeDir, "acl.json"));
        assertNull(
                "the deleted file's entry must not linger in the parent's sidecar", lookupEntry(after, temp.getPath()));
        assertNotNull("the home folder's own entry must survive", lookupEntry(after, adminHomeDir.getPath()));
    }

    // ---- helpers ----------------------------------------------------------------------

    /** A SECURED folder owned by admin, shared to bob with READ+WRITE (no GRANT), plus bob's home. */
    private File sharedFolderForBob() throws Exception {
        File bobHome = new File(datadir, "unknown/homes/bob");
        assertTrue(bobHome.mkdirs());
        writeAcl(bobHome, Map.of(bobHome.getPath(), new AclEntry("bob", AclType.PRIVATE, null, null)));

        File shared = new File(adminHomeDir, "shared");
        assertTrue(shared.mkdirs());
        writeAcl(
                shared,
                Map.of(
                        shared.getPath(),
                        new AclEntry(
                                "admin",
                                AclType.SECURED,
                                null,
                                Map.of("bob", Arrays.asList(AclMethod.READ, AclMethod.WRITE)))));
        return shared;
    }

    private void seedSkeleton() throws Exception {
        // start() seeds /homes (SECURED READ), /dashboards, /queries, ... and assigns the user service
        manager.start(freshUserService());
    }

    private static UserService freshUserService() {
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        return us;
    }

    private static String sidecarContent(File dir) throws Exception {
        return new String(Files.readAllBytes(new File(dir, "acl.json").toPath()), StandardCharsets.UTF_8);
    }

    private static void writeAcl(File dir, Map<String, AclEntry> entries) throws Exception {
        new ObjectMapper().writeValue(new File(dir, "acl.json"), entries);
    }

    private static Map<String, AclEntry> readAcl(File sidecar) throws Exception {
        return new ObjectMapper().readValue(sidecar, ACL_MAP_TYPE);
    }

    /** Sidecar lookup that tolerates raw and canonical key forms, as {@link Acl2#lookup} does. */
    private static AclEntry lookupEntry(Map<String, AclEntry> map, String path) {
        AclEntry entry = map.get(path);
        if (entry != null) {
            return entry;
        }
        String canonical = new File(path).getAbsoluteFile().toPath().normalize().toString();
        entry = map.get(canonical);
        if (entry != null) {
            return entry;
        }
        try {
            return map.get(new File(path).getCanonicalPath());
        } catch (Exception e) {
            return null;
        }
    }

    private static FilesystemRepositoryManager newManager(String path) throws Exception {
        Constructor<FilesystemRepositoryManager> ctor = FilesystemRepositoryManager.class.getDeclaredConstructor(
                String.class, String.class, ScopedRepo.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(path, "ROLE_USER", new ScopedRepo(), false);
    }

    private static UserService injectUserService(FilesystemRepositoryManager mgr, UserService us) throws Exception {
        Field f = FilesystemRepositoryManager.class.getDeclaredField("userService");
        f.setAccessible(true);
        f.set(mgr, us);
        return us;
    }

    private static void resetSingleton() throws Exception {
        Field ref = FilesystemRepositoryManager.class.getDeclaredField("ref");
        ref.setAccessible(true);
        ref.set(null, null);
    }
}
