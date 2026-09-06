/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.user.UserService;

/**
 * Regression coverage for #937: {@link FilesystemRepositoryManager#moveFile}
 * used to be an empty stub, so {@code RepositoryDatasourceManager.moveFile}
 * returned "Move Okay" while nothing actually moved — catalogue folder
 * rename/move silently reverted on reload. These tests pin the real move plus
 * its authorization (canWrite on the source, mirroring removeFile) and
 * path-traversal safety on the target.
 */
public class FilesystemRepositoryManagerMoveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FilesystemRepositoryManager manager;
    private File datadir;
    private File adminHomeDir;

    private static final List<String> ROLES_USER = Collections.singletonList("ROLE_USER");
    private static final List<String> ROLES_ADMIN = Arrays.asList("ROLE_USER", "ROLE_ADMIN");

    @Before
    public void setUp() throws Exception {
        resetSingleton();

        datadir = tmp.newFolder("repo");
        // <datadir>/unknown/etc bootstrap markers so getDatadir()'s lazy
        // setup branch is skipped (same trick the ACL / traversal tests use);
        // repo paths like "/homes/admin/x" resolve under <datadir>/unknown.
        File unknownEtc = new File(datadir, "unknown/etc");
        if (!unknownEtc.mkdirs()) {
            throw new IllegalStateException("Could not create " + unknownEtc);
        }
        adminHomeDir = new File(datadir, "unknown/homes/admin");
        if (!adminHomeDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + adminHomeDir);
        }
        // PRIVATE acl.json pinning ownership to admin, so non-owners are denied.
        writeAclJson(adminHomeDir, adminHomeDir.getPath(), "PRIVATE", "admin");

        manager = newManager(datadir.getAbsolutePath());
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(manager, us);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    @Test
    public void moveFile_relocatesFileAndPreservesContent() throws Exception {
        File src = seed("q3.saikudash", "DASH_BODY");
        // Move into a not-yet-existing sub-folder (folder rename creates the dest).
        manager.moveFile("/homes/admin/q3.saikudash", "/homes/admin/Archive/q3.saikudash", "admin", ROLES_USER);

        assertFalse("source must no longer exist after the move", src.exists());
        File dest = new File(adminHomeDir, "Archive/q3.saikudash");
        assertTrue("destination must exist after the move", dest.exists());
        assertEquals(
                "content must be preserved across the move",
                "DASH_BODY",
                new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void moveFile_nonOwner_nonAdmin_is_denied_on_PRIVATE_folder() throws Exception {
        File src = seed("owned.saikudash", "OWNED_BY_ADMIN");
        try {
            manager.moveFile("/homes/admin/owned.saikudash", "/homes/admin/stolen.saikudash", "bob", ROLES_USER);
            fail("moveFile must reject a non-owner / non-admin move out of a PRIVATE folder");
        } catch (Exception expected) {
            // SaikuServiceException (or wrapped) — any abort is acceptable.
        }
        assertTrue("source must be untouched after a denied move", src.exists());
        assertFalse(
                "destination must not be created by a denied move",
                new File(adminHomeDir, "stolen.saikudash").exists());
    }

    @Test
    public void moveFile_admin_override_can_move_owner_PRIVATE_file() throws Exception {
        File src = seed("admin-move.saikudash", "X");
        manager.moveFile("/homes/admin/admin-move.saikudash", "/homes/admin/moved.saikudash", "admin", ROLES_ADMIN);
        assertFalse(src.exists());
        assertTrue(new File(adminHomeDir, "moved.saikudash").exists());
    }

    @Test
    public void moveFile_rejects_when_target_already_exists() throws Exception {
        File src = seed("src.saikudash", "SRC");
        File existing = seed("dest.saikudash", "DEST");
        try {
            manager.moveFile("/homes/admin/src.saikudash", "/homes/admin/dest.saikudash", "admin", ROLES_USER);
            fail("moveFile must not clobber an existing target");
        } catch (Exception expected) {
            // RepositoryException — target-exists guard.
        }
        assertTrue("source must survive a rejected move", src.exists());
        assertEquals(
                "existing target must not be overwritten",
                "DEST",
                new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void moveFile_rejects_path_traversal_target() throws Exception {
        File src = seed("safe.saikudash", "SAFE");
        try {
            manager.moveFile("/homes/admin/safe.saikudash", "/../../../../escape.saikudash", "admin", ROLES_ADMIN);
            fail("moveFile must reject a target that escapes the datadir");
        } catch (Exception expected) {
            // SaikuServiceException from resolveWithinDatadir.
        }
        assertTrue("source must survive a rejected traversal move", src.exists());
        assertFalse("nothing must be written outside the datadir", new File(datadir, "escape.saikudash").exists());
    }

    /**
     * saiku#1907 N1 (#1934): a non-admin move whose destination parent does NOT yet exist must be
     * gated on the nearest EXISTING ancestor — a non-admin cannot move a file into a brand-new
     * subfolder of the admin-only /dashboards (or /queries, /datasources) tree. RED pre-fix
     * (missing parent skipped the ACL check, then mkdirs()'d it).
     */
    @Test
    public void moveFile_nonAdmin_cannot_move_into_new_admin_only_folder() throws Exception {
        seedSkeleton();
        File bobHome = bobHomeWithSource();

        try {
            manager.moveFile("/homes/bob/x.saikudash", "/dashboards/newfolder/x.saikudash", "bob", ROLES_USER);
            fail("non-admin move into a new admin-only /dashboards subfolder must be denied");
        } catch (Exception expected) {
            // SaikuServiceException — denied on the nearest existing ancestor (/dashboards).
        }
        assertFalse(
                "no folder may be created under the admin-only tree by a denied move",
                new File(datadir, "unknown/dashboards/newfolder").exists());
        assertTrue("source must survive the denied move", new File(bobHome, "x.saikudash").exists());
    }

    /**
     * saiku#1907 N1 (#1934): a non-admin cannot plant a NEW direct child of /homes (another user's
     * home) via moveFile. RED pre-fix (missing /homes/Alice skipped the check, then mkdirs()'d it —
     * which, with the old F5 throw, would have locked Alice out on her first login).
     */
    @Test
    public void moveFile_nonAdmin_cannot_create_another_users_home() throws Exception {
        seedSkeleton();
        File bobHome = bobHomeWithSource();

        try {
            manager.moveFile("/homes/bob/x.saikudash", "/homes/Alice/x.saikudash", "bob", ROLES_USER);
            fail("non-admin must not be able to plant another user's /homes/<name>");
        } catch (Exception expected) {
            // SaikuServiceException — nearest-ancestor gate on /homes (READ-only) + foreign-home guard.
        }
        assertFalse(
                "another user's home must not be created by a non-admin move",
                new File(datadir, "unknown/homes/Alice").exists());
        assertTrue(new File(bobHome, "x.saikudash").exists());
    }

    // ---- helpers ------------------------------------------------------

    private void seedSkeleton() throws Exception {
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(manager, us);
        manager.start(us); // seeds /homes (SECURED READ), /dashboards, /queries, ... (SECURED admin)
    }

    private File bobHomeWithSource() throws Exception {
        File bobHome = new File(datadir, "unknown/homes/bob");
        assertTrue(bobHome.mkdirs());
        writeAclJson(bobHome, bobHome.getPath(), "PRIVATE", "bob");
        Files.write(new File(bobHome, "x.saikudash").toPath(), "X".getBytes(StandardCharsets.UTF_8));
        return bobHome;
    }

    private File seed(String name, String body) throws Exception {
        File f = new File(adminHomeDir, name);
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static void writeAclJson(File dir, String pathKey, String aclType, String owner) throws Exception {
        String escapedPath = pathKey.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"" + escapedPath + "\":{\"owner\":\"" + owner + "\",\"type\":\"" + aclType
                + "\",\"roles\":null,\"users\":null}}";
        Files.write(new File(dir, "acl.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static FilesystemRepositoryManager newManager(String path) throws Exception {
        Constructor<FilesystemRepositoryManager> ctor = FilesystemRepositoryManager.class.getDeclaredConstructor(
                String.class, String.class, ScopedRepo.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(path, "ROLE_USER", new ScopedRepo(), false);
    }

    private static void injectUserService(FilesystemRepositoryManager mgr, UserService us) throws Exception {
        Field f = FilesystemRepositoryManager.class.getDeclaredField("userService");
        f.setAccessible(true);
        f.set(mgr, us);
    }

    private static void resetSingleton() throws Exception {
        Field ref = FilesystemRepositoryManager.class.getDeclaredField("ref");
        ref.setAccessible(true);
        ref.set(null, null);
    }
}
