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
