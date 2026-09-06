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
     * saiku#1907 F4: createUser must REUSE a pre-existing case-variant home and RENAME it to the
     * canonical name, so /homes/<canonical> becomes the single authoritative home (the UI addresses
     * homes by the canonical identity). Content and ownership survive the rename.
     */
    @Test
    public void createUser_reuses_and_renames_case_variant_home_to_canonical() throws Exception {
        // Two case-variant home dirs can only coexist on a case-sensitive filesystem; on
        // Windows /homes/admin and /homes/Admin are the same directory (rename is a no-op).
        org.junit.Assume.assumeFalse(
                "requires a case-sensitive filesystem",
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        File variant = new File(datadir, "unknown/homes/Admin");
        assertTrue(variant.mkdirs());
        writeFolderAclJson(variant, "PRIVATE", "Admin");
        Files.write(
                new File(variant, "note.saikudash").toPath(), "N".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        manager.createUser("admin");

        File canonical = new File(datadir, "unknown/homes/admin");
        assertTrue("the home must be renamed to the canonical name", canonical.isDirectory());
        assertFalse("the case-variant directory must be gone after the rename", variant.exists());
        assertTrue("content must survive the rename", new File(canonical, "note.saikudash").exists());
        // getAllFiles(/homes/admin) — the UI's canonical home path — must now resolve (not null).
        assertNotNull(manager.getAllFiles(java.util.Arrays.asList("saikudash"), "admin", ROLES_ADMIN, "/homes/admin"));
        AclEntry entry = manager.getACL("/homes/admin", "admin", ROLES_ADMIN);
        assertNotNull("the folder ACL must survive the rename (re-keyed)", entry);
        assertTrue("ownership preserved", entry.getOwner() != null && "admin".equalsIgnoreCase(entry.getOwner()));
    }

    /**
     * saiku#1907 N2: a username that canonically ALIASES onto another (entry-less) home must be
     * rejected — the resolved home's real on-disk name must match the identity. Exercised on a
     * POSIX FS with a symlink (the portable stand-in for Win32 8.3 short-name / trailing-dot / ADS
     * aliasing); the same canonical-name guard rejects all of them.
     */
    @Test
    public void createUser_rejects_canonical_name_aliasing() throws Exception {
        org.junit.Assume.assumeFalse(
                "symlink creation needs privileges on Windows; 8.3 aliasing is Win-only",
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        File homes = new File(datadir, "unknown/homes");
        assertTrue(homes.mkdirs());
        File real = new File(homes, "realhome");
        assertTrue(real.mkdirs());
        java.nio.file.Files.createSymbolicLink(new File(homes, "aliaslink").toPath(), real.toPath());

        try {
            manager.createUser("aliaslink");
            fail("createUser must reject a name that canonically aliases onto another home");
        } catch (RuntimeException expected) {
            // fail-closed — home name does not match the resolved identity
        }
    }

    /**
     * saiku#1907 F5 (redesigned): the folder NAME is the ownership authority. When a namesake home
     * carries an entry owned by someone ELSE (planted state, or a stale key), createUser RESTORES
     * ownership to the namesake and self-heals — it does NOT throw (the old throw was silently
     * swallowed and made bad state permanent). Works on both a case-sensitive FS (rename to
     * canonical) and a case-insensitive one (same dir).
     */
    @Test
    public void createUser_restores_ownership_of_a_namesake_home_owned_by_someone_else() throws Exception {
        File adminHome = new File(datadir, "unknown/homes/Admin");
        assertTrue(adminHome.mkdirs());
        writeFolderAclJson(adminHome, "PRIVATE", "mallory"); // foreign owner planted

        manager.createUser("admin"); // must NOT throw — restores ownership to the namesake

        AclEntry entry = manager.getACL("/homes/admin", "admin", ROLES_ADMIN);
        assertNotNull("the home must still have an ACL entry", entry);
        assertTrue(
                "ownership must be restored to the namesake 'admin' (was 'mallory')",
                entry.getOwner() != null && "admin".equalsIgnoreCase(entry.getOwner()));
    }

    /**
     * saiku#1907 (SEC round-3 polish): a SECURED share in a NESTED subfolder of a mixed-case home
     * must survive the one-time rename-to-canonical — the acl.json re-key recurses over the whole
     * renamed subtree, not just the root. RED before the recursive re-key (the nested share's key
     * kept its old absolute path → dropped → sharee denied).
     */
    @Test
    public void nested_subfolder_share_survives_rename_to_canonical() throws Exception {
        org.junit.Assume.assumeFalse(
                "requires a case-sensitive filesystem",
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));

        File variant = new File(datadir, "unknown/homes/Admin");
        assertTrue(variant.mkdirs());
        writeFolderAclJson(variant, "PRIVATE", "Admin"); // home root owned by Admin

        File reports = new File(variant, "reports");
        assertTrue(reports.mkdirs());
        File shared = new File(reports, "shared.saikudash");
        Files.write(shared.toPath(), "SHARED".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Nested per-file SECURED share (ROLE_USER READ), keyed by the file's OLD canonical path.
        String key = shared.getCanonicalPath().replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"" + key
                + "\":{\"owner\":\"Admin\",\"type\":\"SECURED\",\"roles\":{\"ROLE_USER\":[\"READ\"]},\"users\":null}}";
        Files.write(new File(reports, "acl.json").toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        manager.createUser("admin"); // renames /homes/Admin -> /homes/admin and recursively re-keys

        String body = manager.getFile("/homes/admin/reports/shared.saikudash", "bob", ROLES_USER);
        assertEquals("a nested subfolder share must survive the rename-to-canonical", "SHARED", body);
    }

    /**
     * saiku#1907 (SEC round-3 polish): direct, platform-independent coverage of the recursive
     * re-key — a NESTED subfolder's acl.json keys are rewritten too, not only the root's. RED
     * before the recursion (nested acl.json keeps the old prefix).
     */
    @Test
    public void rekeyAclPaths_recurses_into_nested_acl_json() throws Exception {
        File home = new File(datadir, "rk/home");
        assertTrue(home.mkdirs());
        File sub = new File(home, "sub");
        assertTrue(sub.mkdirs());
        Files.write(
                new File(home, "acl.json").toPath(),
                ("{\"/old/home\":{\"owner\":\"a\",\"type\":\"PRIVATE\",\"roles\":null,\"users\":null},"
                                + "\"/old/home/x.saikudash\":{\"owner\":\"a\",\"type\":\"PRIVATE\",\"roles\":null,\"users\":null}}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                new File(sub, "acl.json").toPath(),
                "{\"/old/home/sub/y.saikudash\":{\"owner\":\"a\",\"type\":\"PRIVATE\",\"roles\":null,\"users\":null}}"
                        .getBytes(StandardCharsets.UTF_8));

        Acl2.rekeyAclPaths(home, "/old/home", "/new/home");

        String root = new String(Files.readAllBytes(new File(home, "acl.json").toPath()), StandardCharsets.UTF_8);
        String nested = new String(Files.readAllBytes(new File(sub, "acl.json").toPath()), StandardCharsets.UTF_8);
        assertTrue("root keys must be re-keyed", root.contains("/new/home") && !root.contains("/old/home"));
        assertTrue(
                "NESTED acl.json keys must be re-keyed (recursion)",
                nested.contains("/new/home/sub/y.saikudash") && !nested.contains("/old/home"));
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
