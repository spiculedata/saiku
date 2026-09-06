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
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Regression coverage for saiku#895 (saveFile missing canWrite check) and
 * saiku#896 (removeFile checking canRead instead of canWrite).
 *
 * <p>Pre-fix: any authenticated user could overwrite or delete any file in
 * the repository because {@link FilesystemRepositoryManager#saveFile} did
 * not consult the {@link Acl2} it constructed, and
 * {@link FilesystemRepositoryManager#removeFile} gated on the structurally-
 * wrong {@code canRead} predicate (which is satisfied by every authenticated
 * user on un-ACL'd nodes via the default {@code rootMethod=WRITE} trust
 * posture).
 *
 * <p>Post-fix: both paths consult {@code canWrite}, which honours PRIVATE
 * {@code acl.json} entries the owner has placed on a folder. Admin role
 * short-circuits {@link Acl2#getMethods} (returns GRANT regardless of the
 * entry) so admins retain delete capability even for owner-PRIVATE folders.
 */
public class FilesystemRepositoryManagerAclTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FilesystemRepositoryManager manager;
    private File datadir;
    private File adminHomeDir;
    private File adminPrivateFile;

    private static final List<String> ROLES_USER = Collections.singletonList("ROLE_USER");
    private static final List<String> ROLES_ADMIN = Arrays.asList("ROLE_USER", "ROLE_ADMIN");

    @Before
    public void setUp() throws Exception {
        resetSingleton();

        datadir = tmp.newFolder("repo");
        // <datadir>/unknown/etc bootstrap markers so getDatadir()'s lazy
        // setup branch is skipped; same trick the path-traversal test uses.
        File unknownEtc = new File(datadir, "unknown/etc");
        if (!unknownEtc.mkdirs()) {
            throw new IllegalStateException("Could not create " + unknownEtc);
        }

        // Build admin's home with a PRIVATE acl.json pinning ownership to admin.
        adminHomeDir = new File(datadir, "unknown/homes/admin");
        if (!adminHomeDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + adminHomeDir);
        }
        adminPrivateFile = new File(adminHomeDir, "private.saiku");
        Files.write(adminPrivateFile.toPath(), "OWNED_BY_ADMIN".getBytes(StandardCharsets.UTF_8));

        writeAclJson(adminHomeDir, adminHomeDir.getPath(), "PRIVATE", "admin");

        manager = newManager(datadir.getAbsolutePath());
        // saveFile/removeFile call userService.getAdminRoles(); wire a real
        // UserService configured with ROLE_ADMIN so admin's role-based
        // override fires correctly.
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(manager, us);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    // ---- saiku#1903: datasource descriptors are admin-only, wherever they land ----

    @Test
    public void saveFile_nonAdmin_cannot_write_sds_into_their_own_home() throws Exception {
        // bob owns his home (PRIVATE), so the plain ACL would allow the write — the
        // descriptor guard must refuse it regardless, because the loader lists *.sds
        // recursively and would connect its URL on the next refresh.
        File bobHome = new File(datadir, "unknown/homes/bob");
        assertTrue(bobHome.mkdirs());
        writeAclJson(bobHome, bobHome.getPath(), "PRIVATE", "bob");
        try {
            manager.saveFile("<dataSource/>", "/homes/bob/evil.sds", "bob", "nt:saikufiles", ROLES_USER);
            fail("non-admin .sds write must be refused");
        } catch (SaikuServiceException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("administrator"));
        }
        assertFalse("nothing may be written", new File(bobHome, "evil.sds").exists());
        // Case must not matter: the guard is what stops a descriptor, not the listing's case rules.
        try {
            manager.saveFile("<dataSource/>", "/homes/bob/evil.SDS", "bob", "nt:saikufiles", ROLES_USER);
            fail("non-admin .SDS write must be refused");
        } catch (SaikuServiceException expected) {
            // expected
        }
    }

    @Test
    public void saveFile_nonAdmin_cannot_write_anything_under_datasources() throws Exception {
        try {
            manager.saveFile("x", "/datasources/schema.xml", "bob", "nt:saikufiles", ROLES_USER);
            fail("non-admin write under /datasources must be refused");
        } catch (SaikuServiceException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("administrator"));
        }
    }

    @Test
    public void saveFile_admin_can_still_write_sds() throws Exception {
        manager.saveFile("<dataSource/>", "/homes/admin/tool.sds", "admin", "nt:saikufiles", ROLES_ADMIN);
        assertEquals(
                "<dataSource/>",
                new String(Files.readAllBytes(new File(adminHomeDir, "tool.sds").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void saveFile_nonAdmin_can_still_write_ordinary_files_in_their_home() throws Exception {
        File bobHome = new File(datadir, "unknown/homes/bob");
        assertTrue(bobHome.mkdirs());
        writeAclJson(bobHome, bobHome.getPath(), "PRIVATE", "bob");
        manager.saveFile("{}", "/homes/bob/report.saiku", "bob", "nt:saikufiles", ROLES_USER);
        assertTrue("ordinary saves are untouched by the descriptor guard", new File(bobHome, "report.saiku").exists());
    }

    @Test
    public void moveFile_nonAdmin_cannot_rename_into_sds() throws Exception {
        File bobHome = new File(datadir, "unknown/homes/bob");
        assertTrue(bobHome.mkdirs());
        writeAclJson(bobHome, bobHome.getPath(), "PRIVATE", "bob");
        Files.write(new File(bobHome, "innocent.txt").toPath(), "<dataSource/>".getBytes(StandardCharsets.UTF_8));
        try {
            manager.moveFile("/homes/bob/innocent.txt", "/homes/bob/evil.sds", "bob", ROLES_USER);
            fail("renaming a file into *.sds is a descriptor write and must be refused for non-admins");
        } catch (SaikuServiceException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("administrator"));
        }
        assertFalse(new File(bobHome, "evil.sds").exists());
        assertTrue(new File(bobHome, "innocent.txt").exists());
    }

    @Test
    public void isDatasourceDescriptorPath_recognisesTheShapesTheLoaderConsumes() {
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/x.sds"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("x.SDS"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/datasources/schema.xml"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("datasources/schema.xml"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("./datasources/schema.xml"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("\\datasources\\schema.xml"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/tenant/datasources/x.xml"));
        assertFalse(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/report.saiku"));
        assertFalse(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/my-datasources-notes.txt"));
        assertFalse(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/x.sds.bak"));
        assertFalse(FilesystemRepositoryManager.isDatasourceDescriptorPath(null));
    }

    @Test
    public void isDatasourceDescriptorPath_seesThroughWindowsFilenameNormalisation() {
        // saiku#1903 SEC follow-up: Win32 strips trailing dots/spaces and treats an NTFS ADS suffix
        // as the base file, so these all land on disk as evil.sds and must be recognised.
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/evil.sds."));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/evil.sds.. "));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/evil.sds "));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/EVIL.SDS."));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/evil.sds::$DATA"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/evil.sds:$DATA"));
        assertTrue(FilesystemRepositoryManager.isDatasourceDescriptorPath("/homes/bob/evil.sds:stream"));
    }

    @Test
    public void saveFile_nonAdmin_cannot_write_sds_with_trailing_dot() throws Exception {
        File bobHome = new File(datadir, "unknown/homes/bob");
        assertTrue(bobHome.mkdirs());
        writeAclJson(bobHome, bobHome.getPath(), "PRIVATE", "bob");
        try {
            manager.saveFile("<dataSource/>", "/homes/bob/evil.sds.", "bob", "nt:saikufiles", ROLES_USER);
            fail("a trailing-dot .sds write must be refused (Win32 would drop the dot)");
        } catch (SaikuServiceException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("administrator"));
        }
    }

    // ---- saiku#895: saveFile authorization ----------------------------

    @Test
    public void saveFile_nonOwner_nonAdmin_is_denied_on_PRIVATE_folder() throws Exception {
        try {
            manager.saveFile("HIJACKED", "/homes/admin/private.saiku", "bob", "nt:saikufiles", ROLES_USER);
            fail("saveFile must reject a non-owner / non-admin write to a PRIVATE folder");
        } catch (Exception expected) {
            // SaikuServiceException is wrapped/rethrown — accept anything that aborts the write.
        }
        String preserved = new String(Files.readAllBytes(adminPrivateFile.toPath()), StandardCharsets.UTF_8);
        assertEquals(
                "file contents must not have been overwritten by an unauthorised caller", "OWNED_BY_ADMIN", preserved);
    }

    @Test
    public void saveFile_admin_can_write_to_owner_PRIVATE_folder() throws Exception {
        manager.saveFile("ADMIN_REWRITE", "/homes/admin/private.saiku", "admin", "nt:saikufiles", ROLES_ADMIN);
        assertEquals(
                "admin write must succeed (admin role overrides PRIVATE owner check)",
                "ADMIN_REWRITE",
                new String(Files.readAllBytes(adminPrivateFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void saveFile_owner_can_write_to_their_PRIVATE_folder() throws Exception {
        manager.saveFile("OWNER_REWRITE", "/homes/admin/private.saiku", "admin", "nt:saikufiles", ROLES_USER);
        assertEquals(
                "owner write must succeed (PRIVATE entry grants the owner GRANT, which implies WRITE)",
                "OWNER_REWRITE",
                new String(Files.readAllBytes(adminPrivateFile.toPath()), StandardCharsets.UTF_8));
    }

    // ---- saiku#896: removeFile authorization --------------------------

    @Test
    public void removeFile_nonOwner_nonAdmin_is_denied_on_PRIVATE_folder() throws Exception {
        try {
            manager.removeFile("/homes/admin/private.saiku", "bob", ROLES_USER);
            fail("removeFile must reject a non-owner / non-admin delete in a PRIVATE folder");
        } catch (Exception expected) {
            // Accept either SaikuServiceException or RepositoryException — both are
            // valid "denied" signals depending on which catch block at the REST
            // layer eats the throw.
        }
        assertTrue("PRIVATE file must not have been deleted by an unauthorised caller", adminPrivateFile.exists());
    }

    @Test
    public void removeFile_admin_can_delete_owner_PRIVATE_file() throws Exception {
        manager.removeFile("/homes/admin/private.saiku", "admin", ROLES_ADMIN);
        assertTrue("admin must be able to delete a PRIVATE file (role override)", !adminPrivateFile.exists());
    }

    // ---- helpers ------------------------------------------------------

    /**
     * Write an {@code acl.json} into {@code dir} encoding a single PRIVATE
     * entry keyed by {@code pathKey}. JSON shape matches what
     * {@link Acl2#serialize(File)} produces via Jackson on an {@link AclEntry}.
     */
    private static void writeAclJson(File dir, String pathKey, String aclType, String owner) throws Exception {
        // Escape the path for JSON (Windows backslashes / spaces). On unix
        // there's nothing to escape, but keep it correct.
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
