/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * Regression coverage for saiku#940 — per-resource (per-dashboard) ACLs.
 *
 * <p>Pre-fix the ACL get/set REST surface operated on {@code null} (it used
 * {@code getFolderNode → getAllFoldersInCurrentDirectory}, an unimplemented
 * stub returning {@code null}) and the ACL store was directory-granular:
 * {@link Acl2#serialize} wrote {@code new File(node,"acl.json")} and
 * {@link Acl2#getMethods} read it back the same way, so a regular file's ACL
 * could never be stored, read, or enforced. Setting a per-dashboard ACL was a
 * silent no-op (the REST call returned 200 but persisted nothing).
 *
 * <p>Post-fix a file's ACL lives in its parent folder's {@code acl.json}
 * keyed by the file's absolute path; {@code getMethods} consults it (falling
 * back to folder inheritance), and read/edit/delete enforcement honour it.
 */
public class FilesystemRepositoryManagerFileAclTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FilesystemRepositoryManager manager;
    private File datadir;
    private File adminHomeDir;

    private static final List<String> ROLES_USER = Collections.singletonList("ROLE_USER");
    private static final List<String> ROLES_ADMIN = Arrays.asList("ROLE_USER", "ROLE_ADMIN");

    // A bare AclEntry JSON (NOT the {path: entry} map shape) — this is what
    // the REST layer feeds setACL, deserialised via mapper.readValue(.., AclEntry).
    private static final String SECURED_ROLE_USER_READ =
            "{\"owner\":\"admin\",\"type\":\"SECURED\",\"roles\":{\"ROLE_USER\":[\"READ\"]},\"users\":null}";

    @Before
    public void setUp() throws Exception {
        resetSingleton();

        datadir = tmp.newFolder("repo");
        File unknownEtc = new File(datadir, "unknown/etc");
        if (!unknownEtc.mkdirs()) {
            throw new IllegalStateException("Could not create " + unknownEtc);
        }

        // admin's home is PRIVATE (owner=admin) — a non-owner/non-admin would
        // be denied everything inside it by folder inheritance. The per-file
        // ACL tests below open up a single file within it.
        adminHomeDir = new File(datadir, "unknown/homes/admin");
        if (!adminHomeDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + adminHomeDir);
        }
        writeFolderAclJson(adminHomeDir, "PRIVATE", "admin");

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
    public void setACL_then_getACL_roundtrips_a_per_file_acl() throws Exception {
        File f = new File(adminHomeDir, "shared.saikudash");
        Files.write(f.toPath(), "DASH".getBytes(StandardCharsets.UTF_8));

        manager.setACL("/homes/admin/shared.saikudash", SECURED_ROLE_USER_READ, "admin", ROLES_ADMIN);

        AclEntry got = manager.getACL("/homes/admin/shared.saikudash", "admin", ROLES_ADMIN);
        assertNotNull("getACL must read back the per-file ACL that setACL persisted", got);
        assertEquals(AclType.SECURED, got.getType());
        assertNotNull("roles must round-trip", got.getRoles());
        assertTrue(
                "ROLE_USER must have READ on the file",
                got.getRoles()
                        .getOrDefault("ROLE_USER", Collections.emptyList())
                        .contains(AclMethod.READ));
    }

    @Test
    public void per_file_acl_grants_read_where_the_PRIVATE_folder_would_deny() throws Exception {
        File f = new File(adminHomeDir, "report.saikudash");
        Files.write(f.toPath(), "REPORT_DATA".getBytes(StandardCharsets.UTF_8));

        // bob is denied by the PRIVATE folder before any per-file ACL exists.
        try {
            manager.getFile("/homes/admin/report.saikudash", "bob", ROLES_USER);
            fail("bob must be denied read while only the PRIVATE folder ACL applies");
        } catch (Exception expectedDenied) {
            // expected — folder inheritance denies a non-owner
        }

        manager.setACL("/homes/admin/report.saikudash", SECURED_ROLE_USER_READ, "admin", ROLES_ADMIN);

        // …now the per-file SECURED ROLE_USER:READ entry lets bob read it.
        String body = manager.getFile("/homes/admin/report.saikudash", "bob", ROLES_USER);
        assertEquals("bob must read the file once the per-file ACL grants ROLE_USER READ", "REPORT_DATA", body);
    }

    @Test
    public void per_file_read_only_acl_still_denies_write_and_delete() throws Exception {
        File f = new File(adminHomeDir, "locked.saikudash");
        Files.write(f.toPath(), "ORIGINAL".getBytes(StandardCharsets.UTF_8));
        manager.setACL("/homes/admin/locked.saikudash", SECURED_ROLE_USER_READ, "admin", ROLES_ADMIN);

        try {
            manager.saveFile("HACK", "/homes/admin/locked.saikudash", "bob", "nt:saikufiles", ROLES_USER);
            fail("bob has only READ on the file — write must be denied");
        } catch (Exception expected) {
            // expected
        }
        assertEquals(
                "file must be untouched after a denied write",
                "ORIGINAL",
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));

        try {
            manager.removeFile("/homes/admin/locked.saikudash", "bob", ROLES_USER);
            fail("bob has only READ on the file — delete must be denied");
        } catch (Exception expected) {
            // expected
        }
        assertTrue("file must survive a denied delete", f.exists());
    }

    @Test
    public void setACL_merges_and_does_not_clobber_the_folder_entry() throws Exception {
        File f = new File(adminHomeDir, "merged.saikudash");
        Files.write(f.toPath(), "X".getBytes(StandardCharsets.UTF_8));

        manager.setACL("/homes/admin/merged.saikudash", SECURED_ROLE_USER_READ, "admin", ROLES_ADMIN);

        // The folder's own PRIVATE entry must still be intact alongside the
        // new per-file entry (serialize merges, it does not overwrite).
        AclEntry folder = manager.getACL("/homes/admin", "admin", ROLES_ADMIN);
        assertNotNull(folder);
        assertEquals("folder ACL must be preserved", AclType.PRIVATE, folder.getType());
        assertEquals("admin", folder.getOwner());

        AclEntry file = manager.getACL("/homes/admin/merged.saikudash", "admin", ROLES_ADMIN);
        assertNotNull(file);
        assertEquals(AclType.SECURED, file.getType());
        assertFalse(
                "the two entries are distinct keys in the same acl.json",
                folder.getType().equals(file.getType()));
    }

    // ---- helpers ------------------------------------------------------

    private static void writeFolderAclJson(File dir, String aclType, String owner) throws Exception {
        String escapedPath = dir.getPath().replace("\\", "\\\\").replace("\"", "\\\"");
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
