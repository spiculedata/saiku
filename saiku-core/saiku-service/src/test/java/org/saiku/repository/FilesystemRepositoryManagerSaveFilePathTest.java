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
 * Regression coverage for saiku#1936: two path-resolution bugs in
 * {@link FilesystemRepositoryManager#saveFile}, both pre-existing and surfaced
 * during the #1907 SEC review.
 *
 * <p>(a) The file branch ran {@code check = getNode("./" + basename); check.delete();}
 * before writing. {@code "./<basename>"} resolves to the DATADIR ROOT, not the
 * file's real parent, so saving {@code /homes/<u>/foo.json} silently deleted an
 * unrelated {@code <datadir>/foo.json}.
 *
 * <p>(b) The null-content branch created the folder via
 * {@code createFolder("./" + lastSegment)}, so {@code saveFile(null, "/a/b/c")}
 * made {@code <datadir>/c} instead of {@code <datadir>/a/b/c}.
 *
 * <p>Both now resolve against the caller's path through the same
 * {@code resolveWithinDatadir}-anchored resolution the write itself uses. Every
 * test drives the REAL call site: seed via {@link FilesystemRepositoryManager#start},
 * then {@link FilesystemRepositoryManager#saveFile}.
 */
public class FilesystemRepositoryManagerSaveFilePathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FilesystemRepositoryManager manager;
    private File datadir;
    /** The real repository root after seeding: {@code <datadir>/unknown}. */
    private File repoRoot;

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

        repoRoot = new File(datadir, "unknown");
        assertTrue("seed must have created the repository root", repoRoot.isDirectory());
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    /**
     * (a) The root file must survive a save of a same-basename file nested deeper
     * in the tree. Pre-fix, the {@code getNode("./foo.json").delete()} side effect
     * removed it. RED pre-fix, GREEN post-fix.
     */
    @Test
    public void save_does_not_delete_same_named_file_at_datadir_root() throws Exception {
        File rootFile = new File(repoRoot, "foo.json");
        Files.write(rootFile.toPath(), "ROOT-CONTENT".getBytes(StandardCharsets.UTF_8));

        manager.saveFile("NESTED-CONTENT", "/homes/alice/foo.json", "admin", "nt:saikufiles", ROLES_ADMIN);

        assertTrue(
                "an unrelated file at the datadir root must NOT be deleted by saving a nested same-named file",
                rootFile.exists());
        assertEquals(
                "the root file's content must be untouched",
                "ROOT-CONTENT",
                new String(Files.readAllBytes(rootFile.toPath()), StandardCharsets.UTF_8));

        File nested = new File(repoRoot, "homes/alice/foo.json");
        assertTrue("the requested file must have been written at its real path", nested.exists());
        assertEquals("NESTED-CONTENT", new String(Files.readAllBytes(nested.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * (a, control): overwriting the file at its OWN path still works and does not
     * leave the old content behind — i.e. dropping the misdirected pre-delete did
     * not break the ordinary overwrite path (FileWriter truncates).
     */
    @Test
    public void save_overwrites_the_file_at_its_own_path() throws Exception {
        manager.saveFile("FIRST", "/homes/alice/over.json", "admin", "nt:saikufiles", ROLES_ADMIN);
        manager.saveFile("SECOND", "/homes/alice/over.json", "admin", "nt:saikufiles", ROLES_ADMIN);

        File written = new File(repoRoot, "homes/alice/over.json");
        assertTrue(written.exists());
        assertEquals("SECOND", new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * (b) {@code saveFile(null, "/a/b/c")} must create the folder {@code c} under
     * {@code /a/b}, not at the datadir root. Pre-fix it created {@code <datadir>/c}.
     */
    @Test
    public void null_content_folder_create_lands_under_the_requested_parent() throws Exception {
        // Give the caller a writable home so the ACL gate passes, then nest the
        // folder create two levels down inside it.
        manager.saveFile("x", "/homes/alice/seed.txt", "admin", "nt:saikufiles", ROLES_ADMIN);

        manager.saveFile(null, "/homes/alice/a/b/c", "admin", "nt:saikufiles", ROLES_ADMIN);

        assertTrue(
                "the folder must be created under the requested parent",
                new File(repoRoot, "homes/alice/a/b/c").isDirectory());
        assertFalse("the folder must NOT be created at the repository root", new File(repoRoot, "c").exists());
    }

    /**
     * (b, control): a root-level (separatorless) folder create still lands at the
     * datadir root, as it always has.
     */
    @Test
    public void null_content_root_level_folder_create_still_lands_at_root() throws Exception {
        manager.saveFile(null, "rootfolder", "admin", "nt:saikufiles", ROLES_ADMIN);

        assertTrue(
                "a separatorless folder create must still resolve at the repository root",
                new File(repoRoot, "rootfolder").isDirectory());
    }

    /**
     * Negative control: the #895 ACL gate still runs on the null-content branch —
     * a non-admin with no grant on a folder must be denied, and no folder created.
     */
    @Test
    public void null_content_folder_create_still_honours_the_acl_gate() throws Exception {
        try {
            manager.saveFile(null, "/dashboards/denied", "bob", "nt:saikufiles", ROLES_USER);
            fail("a non-admin with no grant must be denied a folder create in the SECURED /dashboards folder");
        } catch (Exception expected) {
            // SaikuServiceException (or a wrapper) — any abort is acceptable.
        }
        assertFalse(
                "unauthorised folder create must NOT have created the folder",
                new File(repoRoot, "dashboards/denied").exists());
        assertFalse("and must not have leaked it to the repository root either", new File(repoRoot, "denied").exists());
    }

    // ---- helpers ------------------------------------------------------

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
