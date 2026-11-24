/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.repository.DataSource;
import org.saiku.repository.FilesystemRepositoryManager;
import org.saiku.repository.ScopedRepo;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Regression coverage for saiku#2533: DELETE /rest/saiku/admin/datasources/{id} answered 200
 * while the descriptor (and with it the datasource, its credentials and its connection) stayed
 * in place. Two faults stacked:
 *
 * <ol>
 *   <li>the in-memory cache was keyed two ways (the plain loaded name, and the decorated
 *       {@code <workspace>_<name>} key {@code addDatasource} caches under), but removal only
 *       ever dropped one spelling — so the datasource stayed listed and usable;</li>
 *   <li>{@code removeDatasource} stripped the configured datadir off the descriptor's real path
 *       and let {@code deleteFile} re-resolve the remainder against the workspace root, which
 *       produced {@code <datadir>/unknown/unknown/…} — a silent no-op that a later discover
 *       refresh used to resurrect the datasource. And {@code deleteFile} ignored
 *       {@link File#delete()}'s result entirely.</li>
 * </ol>
 *
 * <p>Every test drives the REAL call sites: a real {@link FilesystemRepositoryManager} over a
 * temporary home (seeded the way {@code FilesystemRepositoryManagerSaveFilePathTest} seeds), a
 * real {@link RepositoryDatasourceManager} wired to it, and the real admin-facing
 * {@code removeDatasource(String)} entry point.
 */
public class RepositoryDatasourceManagerDeleteTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File datadir;
    private File repoRoot;
    private FilesystemRepositoryManager frm;
    private RepositoryDatasourceManager rdManager;
    private MockConnectionManager connectionManager;

    @Before
    public void setUp() throws Exception {
        resetSingleton();

        datadir = tmp.newFolder("repo");
        frm = newManager(datadir.getAbsolutePath());

        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(frm, us);
        frm.start(us);

        repoRoot = new File(datadir, "unknown");
        assertTrue("seed must have created the repository root", repoRoot.isDirectory());

        connectionManager = new MockConnectionManager();
        rdManager = new RepositoryDatasourceManager();
        rdManager.setConnectionManager(connectionManager);
        rdManager.setRepositoryManager(frm);
        rdManager.setType("classpath");
        rdManager.setWorkspaces("false");
        rdManager.setDatadir(datadir.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    /**
     * AC1: after the delete the datasource is absent from the cache under BOTH key spellings,
     * its .sds file is gone, and a second delete of the same id answers "not found".
     */
    @Test
    public void delete_created_datasource_removes_file_and_both_cache_spellings() throws Exception {
        SaikuDatasource ds = new SaikuDatasource("del-2533", SaikuDatasource.Type.OLAP, props("del-id-2533"));
        rdManager.addDatasource(ds);

        File sds = new File(repoRoot, "datasources/del-2533.sds");
        assertTrue("the descriptor must be written on disk", sds.exists());
        // Baseline: the admin list carries it (under both key spellings the cache uses).
        assertTrue(rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).containsKey("del-2533"));
        assertTrue(rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).containsKey("unknown_del-2533"));

        assertTrue(rdManager.removeDatasource("del-id-2533"));

        assertFalse("the .sds file must be deleted", sds.exists());
        assertFalse(
                "the datasource must be gone from the admin list (plain key)",
                rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).containsKey("del-2533"));
        assertFalse(
                "the datasource must be gone from the admin list (decorated key)",
                rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).containsKey("unknown_del-2533"));

        assertEquals(
                "the cached connection of the deleted datasource must be evicted under both names",
                java.util.Arrays.asList("del-2533", "unknown_del-2533"),
                connectionManager.removedConnections);

        assertFalse("a second delete of the same id must report not found", rdManager.removeDatasource("del-id-2533"));
    }

    /**
     * AC1 (seeded descriptor): a datasource that was already on disk before the manager ever
     * loaded it — the foodmart.sds shape — must delete just the same.
     */
    @Test
    public void delete_seeded_datasource_removes_file_and_cache_entry() throws Exception {
        // Seed the descriptor the way the launcher does: on disk before anyone loads it.
        DataSource seeded = new DataSource();
        seeded.setName("seeded-2533");
        seeded.setType("OLAP");
        seeded.setId("seeded-id-2533");
        seeded.setDriver("mondrian.olap4j.MondrianOlap4jDriver");
        seeded.setLocation("jdbc:mondrian:Jdbc=jdbc:h2:mem:seed;MODE=MySQL;Catalog=mondrian://seeded-2533.xml");
        frm.saveDataSource(seeded, "/datasources/seeded-2533.sds", "fixme");

        File sds = new File(repoRoot, "datasources/seeded-2533.sds");
        assertTrue(sds.exists());

        assertTrue(rdManager.removeDatasource("seeded-id-2533"));
        assertFalse("the seeded .sds file must be deleted", sds.exists());
        assertFalse(
                "the seeded datasource must be gone from the admin list",
                rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).containsKey("seeded-2533"));
        assertEquals(
                "a seeded connection uses the bare name, which must be evicted too",
                java.util.Arrays.asList("seeded-2533", "unknown_seeded-2533"),
                connectionManager.removedConnections);
    }

    /**
     * AC2: when the descriptor cannot be removed, removeDatasource raises the typed error the
     * REST layer turns into a 5xx — it never reports success. The delete is fail-closed: the
     * cache (and so the admin list and the connection set) stays untouched, exactly as it was
     * before the failed call. The descriptor is made undeletable the POSIX way: its parent
     * directory loses the write bit, so {@link File#delete()} is refused while the loader can
     * still list and read the file. A probe assertion keeps the test honest (and skipped) on
     * an environment where the write bit would not bind anyway (e.g. root).
     */
    @Test
    public void delete_failure_raises_typed_error_and_leaves_cache_untouched() throws Exception {
        SaikuDatasource ds =
                new SaikuDatasource("del-2533-stuck", SaikuDatasource.Type.OLAP, props("del-id-stuck-2533"));
        rdManager.addDatasource(ds);

        File sds = new File(repoRoot, "datasources/del-2533-stuck.sds");
        assertTrue(sds.exists());
        File dir = sds.getParentFile();

        // Probe: if the OS still lets us delete inside the directory (root ignores the write
        // bit), the scenario is not reproducible here — skip rather than lie.
        File probe = File.createTempFile("probe", ".tmp", dir);
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        boolean probeStillThere = !probe.delete();
        if (probeStillThere) {
            probe.delete(); // ignored while read-only; cleaned up once restored
        }
        try {
            assumeTrue("filesystem honours the directory write bit here", probeStillThere);

            try {
                rdManager.removeDatasource("del-id-stuck-2533");
                fail("a delete that cannot remove the descriptor must not answer success");
            } catch (SaikuServiceException expected) {
                assertTrue(expected.getMessage().contains("Could not delete the datasource descriptor file"));
            }

            assertTrue(
                    "the failed delete must leave the datasource listed (fail closed)",
                    rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).containsKey("del-2533-stuck"));
            assertTrue(
                    "the failed delete must leave the datasource listed (decorated key)",
                    rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).containsKey("unknown_del-2533-stuck"));
            assertTrue(
                    "no connection eviction may happen for a failed delete",
                    connectionManager.removedConnections.isEmpty());
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            probe.delete();
        }
    }

    /** Repository-layer contract: deleteFile now reports whether the removal actually happened. */
    @Test
    public void filesystemManager_deleteFile_reports_its_outcome() throws Exception {
        File f = new File(repoRoot, "datasources/probe.txt");
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), "X".getBytes(StandardCharsets.UTF_8));

        assertTrue("an existing file must be reported deleted", frm.deleteFile(f.getAbsolutePath()));
        assertFalse("the file must really be gone", f.exists());
        assertFalse("a missing path must be reported as NOT deleted", frm.deleteFile(f.getAbsolutePath()));
    }

    /** A descriptor without an id must not blow up the admin list path; only exact ids delete. */
    @Test
    public void delete_of_an_unknown_id_returns_false_without_side_effects() throws Exception {
        SaikuDatasource ds = new SaikuDatasource("del-2533-keep", SaikuDatasource.Type.OLAP, props("del-id-keep-2533"));
        rdManager.addDatasource(ds);

        assertFalse(rdManager.removeDatasource("no-such-id-2533"));
        assertTrue(
                "an unrelated delete must not touch the file",
                new File(repoRoot, "datasources/del-2533-keep.sds").exists());
        assertNotNull(
                "an unrelated delete must not touch the cache",
                rdManager.getDatasources(new String[] {"ROLE_ADMIN"}).get("del-2533-keep"));
    }

    // ---- helpers ------------------------------------------------------

    private static Properties props(String id) {
        Properties props = new Properties();
        props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
        props.setProperty("location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:test;Catalog=mondrian://x.xml;");
        props.setProperty("username", "sa");
        props.setProperty("password", "");
        props.setProperty("id", id);
        props.setProperty("security.enabled", "false");
        props.setProperty("type", "OLAP");
        return props;
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
