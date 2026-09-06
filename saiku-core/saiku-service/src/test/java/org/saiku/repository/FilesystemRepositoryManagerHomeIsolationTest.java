/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertEquals;
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
 * Regression coverage for saiku#1907 (HIGH) — home-folder isolation.
 *
 * <p>Files saved under a user's home carried no per-file ACL entry, so access
 * rested entirely on the ancestor {@code /homes/<user>} PRIVATE entry resolving
 * by canonical key. On the datadir / home-path seam (a home folder that never
 * received its {@code acl.json}, e.g. because {@code createUser} stamped a
 * differently-named path, or a home predating ACLs, or one seeded via
 * {@code saveInternalFile}) {@link Acl2#getMethods} walked all the way up to
 * {@code /homes}, which is SECURED with {@code defaultRole -> READ} for every
 * ROLE_USER — a cross-user READ of another user's saved queries/dashboards.
 *
 * <p>The fix is two-fold: {@code getMethods} now fails closed for a home folder
 * with no naming ACL entry (only the owner-by-folder-name or an admin may
 * enter), and {@link FilesystemRepositoryManager#saveFile} stamps a per-file
 * PRIVATE entry on every new file saved under {@code /homes}. These tests drive
 * the REAL call sites ({@code start} to seed, {@code getFile} / {@code saveFile}).
 */
public class FilesystemRepositoryManagerHomeIsolationTest {

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

        // Drive the real seed: writes /homes SECURED (defaultRole=ROLE_USER -> READ),
        // exactly as a first boot does. This is the permissive default a home file
        // must never fall through to for a non-owner.
        manager.start(us);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    /**
     * Build alice's home the way the seam leaves it in the wild: the home folder
     * and a file exist, but the folder carries no {@code acl.json} of its own.
     */
    private File seedAliceHomeFileWithoutFolderAcl() throws Exception {
        File aliceHome = new File(datadir, "unknown/homes/alice");
        assertTrue("could not create alice's home", aliceHome.mkdirs());
        File secret = new File(aliceHome, "secret.saikudash");
        Files.write(secret.toPath(), "SECRET".getBytes(StandardCharsets.UTF_8));
        // Deliberately NO acl.json in aliceHome — this is the seam.
        return secret;
    }

    /**
     * THE cross-user leak. A ROLE_USER (bob) must NOT read a file inside another
     * user's home when the home folder has no ACL naming him. RED pre-fix (the
     * walk-up reaches /homes' defaultRole READ and returns the bytes), GREEN
     * post-fix (getMethods fails closed under another user's home). Reversion-
     * sensitive.
     */
    @Test
    public void crossUser_read_of_another_users_home_file_is_denied() throws Exception {
        seedAliceHomeFileWithoutFolderAcl();

        try {
            String body = manager.getFile("/homes/alice/secret.saikudash", "bob", ROLES_USER);
            fail("bob must not be able to read alice's home file; leaked: " + body);
        } catch (RepositoryException | org.saiku.service.util.exception.SaikuServiceException denied) {
            // expected — access is denied
        }
    }

    /**
     * The owner must NOT be locked out by the fail-closed guard, even when the
     * home folder has no {@code acl.json} (ownership is derived from the home
     * folder name). Guards against the fix over-restricting.
     */
    @Test
    public void owner_can_read_their_own_home_file() throws Exception {
        seedAliceHomeFileWithoutFolderAcl();

        String body = manager.getFile("/homes/alice/secret.saikudash", "alice", ROLES_USER);
        assertEquals("the home owner must still read their own file", "SECRET", body);
    }

    /**
     * ROLE_ADMIN must retain access to any user's home file (the admin role
     * short-circuits getMethods to GRANT). Guards against the fix locking admins out.
     */
    @Test
    public void admin_can_read_another_users_home_file() throws Exception {
        seedAliceHomeFileWithoutFolderAcl();

        String body = manager.getFile("/homes/alice/secret.saikudash", "admin", ROLES_ADMIN);
        assertEquals("an admin must be able to read any user's home file", "SECRET", body);
    }

    /**
     * A ROLE_USER may not read another user's home when the home carries the {@code home:}-prefixed
     * folder name (the legacy/JCR spelling): the owner-by-folder-name derivation strips the prefix,
     * so "bob" is still denied and only "alice" (owner) is allowed. Uses a REAL {@code home:alice}
     * directory — the colon is illegal in a Windows filename, so this runs on a POSIX FS only.
     */
    @Test
    public void crossUser_read_denied_for_home_prefixed_folder_name() throws Exception {
        org.junit.Assume.assumeFalse(
                "':' is illegal in a Windows filename",
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));

        File aliceHome = new File(datadir, "unknown/homes/home:alice");
        assertTrue(aliceHome.mkdirs());
        Files.write(new File(aliceHome, "secret.saikudash").toPath(), "SECRET".getBytes(StandardCharsets.UTF_8));

        try {
            String body = manager.getFile("/homes/home:alice/secret.saikudash", "bob", ROLES_USER);
            fail("bob must not read a file under another user's home; leaked: " + body);
        } catch (RepositoryException | org.saiku.service.util.exception.SaikuServiceException denied) {
            // expected
        }
        // The owner (folder-name minus the "home:" prefix) is still allowed.
        assertEquals("alice", "SECRET", manager.getFile("/homes/home:alice/secret.saikudash", "alice", ROLES_USER));
    }

    /**
     * F10: cross-user READ is denied for a file nested several levels deep under another user's
     * home (no acl.json — the seam), and the owner is still allowed at depth.
     */
    @Test
    public void nested_depth_cross_user_read_is_denied() throws Exception {
        File deep = new File(datadir, "unknown/homes/alice/sub/dir");
        assertTrue(deep.mkdirs());
        Files.write(new File(deep, "secret.saikudash").toPath(), "DEEP".getBytes(StandardCharsets.UTF_8));

        try {
            String body = manager.getFile("/homes/alice/sub/dir/secret.saikudash", "bob", ROLES_USER);
            fail("nested-depth cross-user read must be denied; leaked: " + body);
        } catch (RepositoryException | org.saiku.service.util.exception.SaikuServiceException denied) {
            // expected
        }
        assertEquals(
                "the owner must still read their own file at depth",
                "DEEP",
                manager.getFile("/homes/alice/sub/dir/secret.saikudash", "alice", ROLES_USER));
    }

    /**
     * F10: the same isolation guarantees must hold when getDatadir() resolves to a workspace
     * subdir (multi-tenant) rather than the default {@code unknown/} — bob denied, owner and admin
     * allowed under {@code <datadir>/tenantA/homes/}.
     */
    @Test
    public void cross_user_read_is_denied_under_a_workspace_datadir() throws Exception {
        resetSingleton();
        ScopedRepo repo = new ScopedRepo();
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("workspace", "tenantA");
        repo.setSession(new org.saiku.service.datasource.MockHttpSession(attrs));

        FilesystemRepositoryManager wsMgr = newManager(datadir.getAbsolutePath(), repo, true);
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(wsMgr, us);
        wsMgr.start(us); // seeds <datadir>/tenantA/homes (SECURED, defaultRole READ)

        File aliceHome = new File(datadir, "tenantA/homes/alice");
        assertTrue(aliceHome.mkdirs());
        Files.write(new File(aliceHome, "secret.saikudash").toPath(), "WS".getBytes(StandardCharsets.UTF_8));

        try {
            String body = wsMgr.getFile("/homes/alice/secret.saikudash", "bob", ROLES_USER);
            fail("cross-user read under a workspace datadir must be denied; leaked: " + body);
        } catch (RepositoryException | org.saiku.service.util.exception.SaikuServiceException denied) {
            // expected
        }
        assertEquals("WS", wsMgr.getFile("/homes/alice/secret.saikudash", "alice", ROLES_USER));
        assertEquals("WS", wsMgr.getFile("/homes/alice/secret.saikudash", "admin", ROLES_ADMIN));
    }

    /**
     * saiku#1907 per-file stamp: a NEW file saved under a user's home must be
     * given its own PRIVATE ACL entry (owner = the saver). RED pre-fix (no
     * per-file entry is written, so getACL returns the default PUBLIC entry),
     * GREEN post-fix. Reversion-sensitive.
     */
    @Test
    public void saveFile_stamps_a_private_per_file_acl_under_home() throws Exception {
        // A writable home for alice (PRIVATE, owner alice) via the real createUser.
        manager.createUser("alice");

        manager.saveFile("REPORT", "/homes/alice/report.saikudash", "alice", "nt:saikufiles", ROLES_USER);

        AclEntry entry = manager.getACL("/homes/alice/report.saikudash", "alice", ROLES_USER);
        assertNotNull("a per-file ACL entry must have been persisted", entry);
        assertEquals("the per-file entry must be PRIVATE", AclType.PRIVATE, entry.getType());
        assertTrue(
                "the per-file entry must be owned by the saver",
                entry.getOwner() != null && entry.getOwner().equalsIgnoreCase("alice"));
    }

    /**
     * saiku#1907 F2: a NEW file saved into a SECURED shared home subfolder must NOT be
     * stamped PRIVATE-to-its-saver — it must inherit the shared folder ACL so both the
     * sharee and the owner can read it. RED if the stamp is unconditional (bob denied).
     */
    @Test
    public void new_file_in_secured_shared_subfolder_is_readable_by_sharee_and_owner() throws Exception {
        manager.createUser("alice");
        File team = new File(datadir, "unknown/homes/alice/team");
        assertTrue(team.mkdirs());
        // alice shares the "team" folder: SECURED, ROLE_USER READ+WRITE, owner alice.
        String key = team.getPath().replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"" + key
                + "\":{\"owner\":\"alice\",\"type\":\"SECURED\",\"roles\":{\"ROLE_USER\":[\"WRITE\",\"READ\"]},\"users\":null}}";
        Files.write(new File(team, "acl.json").toPath(), json.getBytes(StandardCharsets.UTF_8));

        manager.saveFile("TEAMDATA", "/homes/alice/team/plan.saikudash", "alice", "nt:saikufiles", ROLES_USER);

        String bobView = manager.getFile("/homes/alice/team/plan.saikudash", "bob", ROLES_USER);
        assertEquals("a sharee must read a file saved in a SECURED shared folder", "TEAMDATA", bobView);

        String aliceView = manager.getFile("/homes/alice/team/plan.saikudash", "alice", ROLES_USER);
        assertEquals("the owner must read it too", "TEAMDATA", aliceView);
    }

    /**
     * saiku#1907 F6: a non-admin catalogue listing must return the owner's own items. The ACL
     * check runs against the ABSOLUTE on-disk file now, so it no longer resolves against the JVM
     * CWD and come back empty on a case-sensitive FS (Linux/CI). RED pre-fix (empty listing).
     */
    @Test
    public void non_admin_listing_returns_owners_items() throws Exception {
        manager.createUser("alice");
        manager.saveFile("Q", "/homes/alice/report.saikudash", "alice", "nt:saikufiles", ROLES_USER);

        List<IRepositoryObject> tree = manager.getAllFiles(Arrays.asList("saikudash"), "alice", ROLES_USER);
        assertNotNull("listing must not be null", tree);
        assertTrue(
                "alice's own home file must appear in her listing",
                flattenPaths(tree).stream().anyMatch(p -> p.contains("report.saikudash")));
    }

    /**
     * saiku#1907 F7: the {@code /homes} guard is anchored to the real datadir container, so a
     * folder literally named "homes" nested inside a user's home cannot impersonate it and grant
     * access by folder name. RED pre-fix (bob reads a file under alice's nested homes/bob).
     */
    @Test
    public void nested_homes_subfolder_cannot_be_abused_by_name() throws Exception {
        manager.createUser("alice");
        File nested = new File(datadir, "unknown/homes/alice/homes/bob");
        assertTrue(nested.mkdirs());
        Files.write(new File(nested, "loot.saikudash").toPath(), "LOOT".getBytes(StandardCharsets.UTF_8));

        try {
            String body = manager.getFile("/homes/alice/homes/bob/loot.saikudash", "bob", ROLES_USER);
            fail("a nested 'homes' folder must not grant access by name; leaked: " + body);
        } catch (RepositoryException | org.saiku.service.util.exception.SaikuServiceException denied) {
            // expected — the guard is anchored to the real /homes container
        }
    }

    // ---- helpers ------------------------------------------------------

    private static List<String> flattenPaths(List<IRepositoryObject> nodes) {
        List<String> out = new java.util.ArrayList<>();
        if (nodes != null) {
            for (IRepositoryObject n : nodes) {
                out.add(n.getName());
                if (n instanceof RepositoryFolderObject) {
                    out.addAll(flattenPaths(((RepositoryFolderObject) n).getRepoObjects()));
                }
            }
        }
        return out;
    }

    private static FilesystemRepositoryManager newManager(String path) throws Exception {
        return newManager(path, new ScopedRepo(), false);
    }

    private static FilesystemRepositoryManager newManager(String path, ScopedRepo repo, boolean workspaces)
            throws Exception {
        Constructor<FilesystemRepositoryManager> ctor = FilesystemRepositoryManager.class.getDeclaredConstructor(
                String.class, String.class, ScopedRepo.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(path, "ROLE_USER", repo, workspaces);
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
