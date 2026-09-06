/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Regression coverage for the path-traversal vector in {@code FilesystemRepositoryManager}:
 * relative paths containing {@code ../} sequences are concatenated with the data directory
 * and read by {@link java.nio.file.Files#readAllBytes(Path)}, which resolves the traversal
 * and lets an authenticated caller exfiltrate files outside the repo root.
 */
public class FilesystemRepositoryManagerPathTraversalTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FilesystemRepositoryManager manager;
    private File datadir;
    private File outsideSecret;

    @Before
    public void setUp() throws Exception {
        // Reset the singleton so each test gets a fresh manager pointed at its temp dir.
        resetSingleton();

        datadir = tmp.newFolder("repo");
        // Pre-create unknown/etc so getDatadir()'s lazy-bootstrap branch is skipped.
        File unknownEtc = new File(datadir, "unknown/etc");
        if (!unknownEtc.mkdirs()) {
            throw new IllegalStateException("Could not create unknown/etc: " + unknownEtc);
        }

        // Plant a "secret" file OUTSIDE the repo root the manager is bound to.
        File secretRoot = tmp.newFolder("outside");
        outsideSecret = new File(secretRoot, "passwd-like-secret.txt");
        Files.write(outsideSecret.toPath(), "TOP_SECRET".getBytes(StandardCharsets.UTF_8));

        manager = newManager(datadir.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    @Test
    public void getInternalFile_rejects_dotdot_traversal() throws Exception {
        // Build a relative path that climbs out of <datadir>/unknown/ into our outside secret.
        // getDatadir() returns "<datadir>/unknown/" so prefixing with "../../outside/<file>"
        // resolves to the planted secret.
        String relativeTraversal = "../../outside/" + outsideSecret.getName();

        String contents;
        try {
            contents = manager.getInternalFile(relativeTraversal);
        } catch (RepositoryException expected) {
            // Acceptable: the manager refuses to read paths that escape its root.
            return;
        }

        // If no exception was thrown the only safe outcome is "no content read".
        if (contents != null && contents.contains("TOP_SECRET")) {
            fail("path traversal succeeded: getInternalFile returned the contents of "
                    + outsideSecret.getAbsolutePath()
                    + " when given relative path "
                    + relativeTraversal);
        }
        // Defensive: null is also acceptable (means read failed cleanly).
        assertFalse(
                "getInternalFile must not return secret contents for traversal input",
                contents != null && contents.contains("TOP_SECRET"));
    }

    @Test
    public void getBinaryInternalFile_rejects_dotdot_traversal() throws Exception {
        String relativeTraversal = "../../outside/" + outsideSecret.getName();

        InputStream stream;
        try {
            stream = manager.getBinaryInternalFile(relativeTraversal);
        } catch (RepositoryException expected) {
            return;
        }

        if (stream == null) {
            return; // safe fallback
        }
        byte[] read = stream.readAllBytes();
        String body = new String(read, StandardCharsets.UTF_8);
        if (body.contains("TOP_SECRET")) {
            fail("path traversal succeeded: getBinaryInternalFile returned the contents of "
                    + outsideSecret.getAbsolutePath());
        }
    }

    @Test
    public void saveBinaryInternalFile_writes_inside_datadir_not_jvm_cwd() throws Exception {
        // Pre-fix bug: saveBinaryInternalFile used `new FileOutputStream(new File("./" + basename))`,
        // landing the bytes in the JVM's working directory instead of the repo root. The intended
        // target (resNode from createNode()) was constructed but never written to.
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        String repoPath = "/etc/test-binary-write.bin";

        manager.saveBinaryInternalFile(new java.io.ByteArrayInputStream(payload), repoPath, "application/octet-stream");

        // Expected: file lands at <datadir>/unknown/etc/test-binary-write.bin (getDatadir() resolves
        // to <datadir>/unknown/ when there's no workspace session).
        File expected = new File(datadir, "unknown/etc/test-binary-write.bin");
        if (!expected.exists()) {
            fail("saveBinaryInternalFile must write inside the repo. Expected at "
                    + expected.getAbsolutePath()
                    + " but the file is missing.");
        }
        byte[] readBack = Files.readAllBytes(expected.toPath());
        if (!new String(readBack, StandardCharsets.UTF_8).equals("hello")) {
            fail("saveBinaryInternalFile wrote unexpected bytes to " + expected.getAbsolutePath());
        }

        // Also assert the bug-vector file does NOT exist at CWD.
        File cwdLeak = new File("test-binary-write.bin");
        if (cwdLeak.exists()) {
            try {
                cwdLeak.delete();
            } catch (Exception ignored) {
            }
            fail("saveBinaryInternalFile leaked bytes into the JVM working directory (" + cwdLeak.getAbsolutePath()
                    + ")");
        }
    }

    @Test
    public void saveBinaryInternalFile_rejects_dotdot_traversal_write() throws Exception {
        // Mirror of saveBinaryInternalFile_writes_inside_datadir_not_jvm_cwd above, but the
        // repo-relative path climbs out of <datadir>/unknown/ via ../../ instead of staying
        // inside it. createNode() must resolve strictly inside the datadir (the same guard
        // getNode()/resolveWithinDatadir already apply on the read side) instead of naively
        // concatenating the datadir with the caller-supplied path.
        byte[] payload = "evil".getBytes(StandardCharsets.UTF_8);
        String traversalPath = "../../outside/evil.sds";

        try {
            manager.saveBinaryInternalFile(
                    new java.io.ByteArrayInputStream(payload), traversalPath, "application/octet-stream");
        } catch (RuntimeException expected) {
            // Acceptable: the createNode guard fails closed (throws SaikuServiceException, a
            // RuntimeException) rather than silently writing outside the datadir.
        }

        // Whichever way the call above resolved, the escaped file must never land on disk.
        // The traversal targets the same "outside" folder planted in setUp() for outsideSecret.
        File escaped = new File(outsideSecret.getParentFile(), "evil.sds");
        if (escaped.exists()) {
            try {
                Files.delete(escaped.toPath());
            } catch (Exception ignored) {
            }
            fail("saveBinaryInternalFile must not write outside the repo root; found: " + escaped.getAbsolutePath());
        }
    }

    @Test
    public void getInternalFile_absolute_path_outside_datadir_is_rejected() throws Exception {
        // An absolute path that doesn't start with the datadir must not be readable either.
        String absoluteOutside = outsideSecret.getAbsolutePath();

        String contents;
        try {
            contents = manager.getInternalFile(absoluteOutside);
        } catch (RepositoryException expected) {
            return;
        }

        assertNull(
                "absolute path outside the datadir must not be readable, got: " + contents,
                contents == null ? null : (contents.contains("TOP_SECRET") ? contents : null));
    }

    @Test
    public void saveDataSource_rejects_dotdot_traversal_write() throws Exception {
        // The actual #1906 sink, end to end. RepositoryDatasourceManager.addDatasource builds
        // this exact path shape -- separator + "datasources" + separator + <name> + ".sds" --
        // and hands it straight to saveDataSource. This manager has no knowledge of that
        // caller's own name allowlist, so createNode() has to be the backstop here: it must
        // resolve strictly inside the datadir instead of naively concatenating the datadir with
        // the caller-supplied path.
        DataSource ds = new DataSource();
        ds.setName("evil");
        String traversalPath = "/datasources/../../outside/evil.sds";

        try {
            manager.saveDataSource(ds, traversalPath, "fixme");
        } catch (RuntimeException expected) {
            // Acceptable: the createNode guard fails closed (throws SaikuServiceException, a
            // RuntimeException) rather than silently writing outside the datadir.
        }

        // Per resolveWithinDatadir's own arithmetic: base is <datadir>/unknown/, and
        // "/datasources/../../outside/evil.sds" resolved against it cancels "datasources" then
        // "unknown", landing back at <datadir> itself before descending into a brand new
        // "outside" folder -- i.e. <datadir>/outside/evil.sds, a sibling of the "unknown"
        // workspace directory the repo is actually scoped to. Pre-fix, createNode() built this
        // same string via `new File(getDatadir(), filename)` with no bounds check, and
        // FileWriter would have created exactly this file; that sibling must never be created.
        File escaped = new File(datadir, "outside/evil.sds");
        if (escaped.exists()) {
            try {
                Files.delete(escaped.toPath());
            } catch (Exception ignored) {
            }
            fail("saveDataSource must not write outside the repo root; found: " + escaped.getAbsolutePath());
        }
    }

    @Test
    public void createUser_rejects_dotdot_traversal_in_username() throws Exception {
        // createFolder() is private; createUser() is the nearest public caller that reaches it
        // directly with no ACL/session wiring needed -- every login / admin add-user path calls
        // it with "/homes/" + username, so a username of "../../evil" is exactly the
        // caller-controlled input createFolder() must guard against.
        try {
            manager.createUser("../../evil");
        } catch (RuntimeException expected) {
            // Acceptable: createFolder's guard fails closed (throws SaikuServiceException).
        }

        // Per resolveWithinDatadir's arithmetic: base is <datadir>/unknown/, and
        // "/homes/../../evil" resolved against it cancels "homes" then "unknown", landing back
        // at <datadir> itself before descending into a new "evil" folder -- i.e.
        // <datadir>/evil, a sibling of the "unknown" workspace directory. Pre-fix,
        // createFolder() built this same string via `fixPath(getDatadir() + path)` with no
        // bounds check and unconditionally called mkdirs() on it, which would have created
        // exactly this directory; that sibling must never be created.
        File escaped = new File(datadir, "evil");
        if (escaped.exists()) {
            escaped.delete();
            fail("createUser must not create a folder outside the repo root; found: " + escaped.getAbsolutePath());
        }
    }

    @Test
    public void createUser_creates_legitimate_home_folder_inside_datadir() throws Exception {
        // Positive control for the traversal guard above: the guard must not false-positive on
        // ordinary, non-traversal input -- an ordinary username must still get its home folder.
        manager.createUser("normaluser");

        File expected = new File(datadir, "unknown/homes/normaluser");
        if (!expected.isDirectory()) {
            fail("createUser must create the user's home folder inside the datadir. Expected at "
                    + expected.getAbsolutePath()
                    + " but it is missing.");
        }
    }

    // --- helpers -------------------------------------------------------------

    private static FilesystemRepositoryManager newManager(String path) throws Exception {
        Constructor<FilesystemRepositoryManager> ctor = FilesystemRepositoryManager.class.getDeclaredConstructor(
                String.class, String.class, ScopedRepo.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(path, "ROLE_USER", new ScopedRepo(), false);
    }

    private static void resetSingleton() throws Exception {
        Field ref = FilesystemRepositoryManager.class.getDeclaredField("ref");
        ref.setAccessible(true);
        ref.set(null, null);
    }
}
