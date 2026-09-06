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
 * Regression coverage for saiku#1907 (CWE-178) — username case desync at the ACL
 * layer.
 *
 * <p>The account store matches usernames case-insensitively, so {@code admin} and
 * {@code Admin} are the same account. But {@link Acl2}'s owner check used a
 * case-sensitive {@code equals}, so an ACL owned by {@code admin} was NOT honoured
 * for a caller principal {@code Admin} — locking the real owner out of their own
 * resources (and, symmetrically, a mixed-case home could desync ownership). The
 * fix compares owners/principals case-insensitively.
 *
 * <p>These tests exercise the enforcing invariant directly: an ACL owned by
 * {@code admin} is honoured for a caller {@code Admin}, both on an explicit
 * PRIVATE entry (the {@code sameUser} change) and on a home folder resolved by
 * name (proving {@code Admin} and {@code admin} map to one home identity), while
 * a genuinely different user is still denied.
 */
public class FilesystemRepositoryManagerCaseOwnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FilesystemRepositoryManager manager;
    private File datadir;

    private static final List<String> ROLES_USER = Collections.singletonList("ROLE_USER");
    private static final List<String> ROLES_ADMIN = Arrays.asList("ROLE_USER", "ROLE_ADMIN");

    @Before
    public void setUp() throws Exception {
        resetSingleton();

        datadir = tmp.newFolder("repo");
        manager = newManager(datadir.getAbsolutePath());

        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(manager, us);

        manager.start(us);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    /**
     * An ACL owned by {@code admin} (lower-case, as persisted) must be honoured for
     * a caller whose principal is {@code Admin}. RED pre-fix (case-sensitive equals
     * denies the owner), GREEN post-fix. Reversion-sensitive.
     */
    @Test
    public void owner_reads_own_private_file_when_caller_case_differs() throws Exception {
        File adminHome = new File(datadir, "unknown/homes/admin");
        assertTrue(adminHome.mkdirs());
        writeFolderAclJson(adminHome, "PRIVATE", "admin");
        Files.write(new File(adminHome, "secret.saikudash").toPath(), "SECRET".getBytes(StandardCharsets.UTF_8));

        String body = manager.getFile("/homes/admin/secret.saikudash", "Admin", ROLES_USER);
        assertEquals("an ACL owned by 'admin' must be honoured for caller 'Admin'", "SECRET", body);
    }

    /**
     * The owner may write into their own PRIVATE folder despite a case-different
     * caller principal. RED pre-fix (owner denied WRITE), GREEN post-fix.
     */
    @Test
    public void owner_writes_own_private_folder_when_caller_case_differs() throws Exception {
        File adminHome = new File(datadir, "unknown/homes/admin");
        assertTrue(adminHome.mkdirs());
        writeFolderAclJson(adminHome, "PRIVATE", "admin");

        manager.saveFile("{}", "/homes/admin/new.saikudash", "Admin", "nt:saikufiles", ROLES_USER);
        assertTrue("owner (case-variant) write must succeed", new File(adminHome, "new.saikudash").exists());
    }

    /**
     * {@code Admin} and {@code admin} map to the SAME home identity: a caller
     * {@code Admin} is treated as owner of home {@code admin} (even with no
     * acl.json — ownership derived by folder name, matched case-insensitively),
     * while a genuinely different user (bob) is denied.
     */
    @Test
    public void caller_case_variant_maps_to_same_home_identity() throws Exception {
        File adminHome = new File(datadir, "unknown/homes/admin");
        assertTrue(adminHome.mkdirs());
        Files.write(new File(adminHome, "secret.saikudash").toPath(), "SECRET".getBytes(StandardCharsets.UTF_8));
        // No acl.json — ownership is derived from the home folder name.

        String body = manager.getFile("/homes/admin/secret.saikudash", "Admin", ROLES_USER);
        assertEquals("caller 'Admin' must resolve to the 'admin' home", "SECRET", body);

        try {
            String leaked = manager.getFile("/homes/admin/secret.saikudash", "bob", ROLES_USER);
            fail("a genuinely different user must still be denied; leaked: " + leaked);
        } catch (RepositoryException | org.saiku.service.util.exception.SaikuServiceException denied) {
            // expected
        }
    }

    /**
     * saiku#1907 F4: createUser must REUSE an existing case-variant home rather than create a
     * divergent canonical duplicate, so a pre-existing /homes/Admin stays the single home for
     * identity "admin" and its content stays reachable.
     */
    @Test
    public void createUser_reuses_existing_case_variant_home() throws Exception {
        // Two case-variant home dirs can only coexist on a case-sensitive filesystem; on
        // Windows /homes/admin and /homes/Admin are the same directory (reuse is automatic).
        org.junit.Assume.assumeFalse(
                "requires a case-sensitive filesystem",
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        File adminHome = new File(datadir, "unknown/homes/Admin");
        assertTrue(adminHome.mkdirs());
        writeFolderAclJson(adminHome, "PRIVATE", "Admin");

        manager.createUser("admin");

        assertTrue("the existing case-variant home must be reused", adminHome.isDirectory());
        assertFalse(
                "a divergent canonical duplicate must NOT be created",
                new File(datadir, "unknown/homes/admin").exists());
    }

    /**
     * saiku#1907 F5: if a case-variant home is owned by a DIFFERENT principal, createUser must
     * fail closed rather than conflate the two identities. (Unreachable on the shipped
     * case-insensitive store; defends a mis-configured case-sensitive external store.)
     */
    @Test
    public void createUser_fails_closed_on_case_variant_home_of_different_principal() throws Exception {
        File adminHome = new File(datadir, "unknown/homes/Admin");
        assertTrue(adminHome.mkdirs());
        // Folder name is a case-variant of "admin" but it is owned by a different principal.
        writeFolderAclJson(adminHome, "PRIVATE", "mallory");

        try {
            manager.createUser("admin");
            fail("createUser must fail closed when a case-variant home is owned by a different principal");
        } catch (RuntimeException expected) {
            // fail-closed (SaikuServiceException)
        }
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
