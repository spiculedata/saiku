/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertEquals;
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
 * Regression coverage for saiku#1660: a dashboard save that returns HTTP 500 on
 * a freshly seeded home.
 *
 * <p>The REST surface {@code POST /rest/saiku/api/dashboards/{path:.+}} hands
 * {@link FilesystemRepositoryManager#saveFile} the captured path verbatim. When
 * a client posts a bare filename (no folder segment, e.g.
 * {@code welcome.saikudash}), {@code path.lastIndexOf("/")} is {@code -1} and the
 * pre-fix code called {@code path.substring(0, -1)} — a
 * {@link StringIndexOutOfBoundsException} that surfaced as an opaque HTTP 500,
 * NEVER reaching the ACL gate. This is the fresh-home 500 in #1660. The fix
 * treats a separatorless path as living at the repository root (mirroring the
 * folder-create branch) so a real parent folder resolves and the {@code canWrite}
 * gate still runs.
 *
 * <p>Also covers the ACL abspath-key hardening (#1660): the default {@code
 * acl.json} on the seeded SECURED surfaces is written through the real seed path,
 * whose entries are keyed by the writer's absolute-path form. {@link Acl2} now
 * canonicalises keys on both write and lookup so the seeded role grant is found
 * regardless of the platform-specific path-form divergence between the seed
 * writer and the save-time lookup. The non-admin negative control must still be
 * denied post-fix.
 *
 * <p>All tests drive the REAL call-site: seed via {@link
 * FilesystemRepositoryManager#start}, then {@link
 * FilesystemRepositoryManager#saveFile}.
 */
public class FilesystemRepositoryManagerFreshHomeSaveTest {

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

        // Drive the real seed (seedSkeleton) — writes the default acl.json for
        // /dashboards, /queries, etc. exactly as a first boot does.
        manager.start(us);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    /**
     * The #1660 500 repro. A bare filename (no folder segment) — exactly what
     * JAX-RS captures for {@code POST /rest/saiku/api/dashboards/welcome.saikudash}
     * — used to throw StringIndexOutOfBoundsException in {@code saveFile} and
     * surface as a 500. RED pre-fix, GREEN post-fix.
     */
    @Test
    public void save_with_bare_filename_does_not_throw_and_persists() throws Exception {
        String path = "welcome.saikudash";
        String body = "{\"layout\":{\"cols\":12},\"tiles\":[]}";

        // Pre-fix this line throws StringIndexOutOfBoundsException.
        manager.saveFile(body, path, "admin", "nt:saikufiles", ROLES_ADMIN);

        File written = new File(datadir, "unknown/welcome.saikudash");
        assertTrue("bare-filename dashboard must have been written at repo root", written.exists());
        assertEquals(
                "written body must match what was sent",
                body,
                new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * Admin saves a dashboard into the freshly seeded {@code /dashboards} folder.
     * Exercises the seeded SECURED acl.json + the ACL canonical-key lookup.
     */
    @Test
    public void admin_can_save_dashboard_into_freshly_seeded_folder() throws Exception {
        String path = "/dashboards/repro-1660.saikudash";
        String body = "{\"layout\":{\"cols\":12},\"tiles\":[]}";

        manager.saveFile(body, path, "admin", "nt:saikufiles", ROLES_ADMIN);

        File written = new File(datadir, "unknown/dashboards/repro-1660.saikudash");
        assertTrue("dashboard file must have been written under the seeded folder", written.exists());
    }

    /**
     * ACL abspath-key hardening: a non-admin (ROLE_USER) whose role grant is
     * expressed ONLY through the seeded SECURED entry (no admin short-circuit)
     * must be GRANTED write when that entry grants their role — proving the
     * canonical-key lookup actually finds the seeded entry. We seed a bespoke
     * folder whose acl.json grants ROLE_USER WRITE, keyed exactly as the seed
     * writer keys it (folder absolute path).
     */
    @Test
    public void nonAdmin_with_seeded_role_grant_can_write() throws Exception {
        // Create a folder + seed a SECURED acl.json granting ROLE_USER WRITE,
        // keyed by the folder's own path form (the seed writer's key form).
        File shared = new File(datadir, "unknown/shared");
        assertTrue(shared.mkdirs());
        String key = shared.getPath();
        String escaped = key.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"" + escaped
                + "\":{\"owner\":\"admin\",\"type\":\"SECURED\",\"roles\":{\"ROLE_USER\":[\"WRITE\",\"READ\"]},\"users\":null}}";
        Files.write(new File(shared, "acl.json").toPath(), json.getBytes(StandardCharsets.UTF_8));

        manager.saveFile("{\"layout\":{},\"tiles\":[]}", "/shared/u.saikudash", "bob", "nt:saikufiles", ROLES_USER);

        assertTrue(
                "a ROLE_USER caller granted WRITE by the seeded SECURED entry must succeed",
                new File(shared, "u.saikudash").exists());
    }

    /**
     * Negative control: a non-admin caller with no grant on the SECURED
     * {@code /dashboards} folder must STILL be denied. Ensures the fix does not
     * loosen the ACL gate.
     */
    @Test
    public void nonAdmin_is_still_denied_on_seeded_SECURED_dashboards() throws Exception {
        String path = "/dashboards/hijack-1660.saikudash";
        try {
            manager.saveFile("HIJACK", path, "bob", "nt:saikufiles", ROLES_USER);
            fail("a non-admin with no grant must be denied a write into the SECURED /dashboards folder");
        } catch (Exception expected) {
            // SaikuServiceException (or a wrapper) — any abort of the write is acceptable.
        }
        File written = new File(datadir, "unknown/dashboards/hijack-1660.saikudash");
        assertTrue("unauthorised write must NOT have created a file", !written.exists());
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
